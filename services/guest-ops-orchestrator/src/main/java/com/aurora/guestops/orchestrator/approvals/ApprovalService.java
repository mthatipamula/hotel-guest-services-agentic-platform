package com.aurora.guestops.orchestrator.approvals;

import com.aurora.guestops.commons.mcp.McpToolsResolver;
import com.aurora.guestops.commons.registry.RegistryClient;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Human-in-the-loop workflow. Agents can only propose high-impact actions; a manager approves or
 * rejects them in the console, and only then is the MCP tool called. Proposals are validated here
 * and again by the MCP tool (tier limits, room status), so neither layer trusts the other blindly.
 */
@Service
public class ApprovalService {

    private static final Pattern CONFIRMATION = Pattern.compile("^AUR-\\d{5}$");
    private static final BigDecimal HARD_CAP_USD = new BigDecimal("500");

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final McpToolsResolver mcp;
    private final RegistryClient registry;

    public ApprovalService(JdbcClient jdbc, ObjectMapper mapper, McpToolsResolver mcp, RegistryClient registry) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.mcp = mcp;
        this.registry = registry;
    }

    public record ProposalResult(boolean accepted, String approvalId, String message) {
    }

    public ProposalResult propose(String traceId, String conversationId, String agentId, String actionTypeRaw,
                                  String confirmationNumber, String newRoomNumber, Double creditAmount,
                                  String checkoutTime, String justification) {
        ActionType type;
        try {
            type = ActionType.valueOf(actionTypeRaw == null ? "" : actionTypeRaw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return new ProposalResult(false, null, "actionType must be ROOM_MOVE, FOLIO_CREDIT or LATE_CHECKOUT");
        }
        String conf = confirmationNumber == null ? "" : confirmationNumber.trim().toUpperCase();
        if (!CONFIRMATION.matcher(conf).matches()) {
            return new ProposalResult(false, null, "a valid confirmation number (AUR-#####) is required");
        }
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("confirmationNumber", conf);
        switch (type) {
            case ROOM_MOVE -> {
                if (newRoomNumber == null || newRoomNumber.isBlank()) {
                    return new ProposalResult(false, null, "newRoomNumber is required for ROOM_MOVE");
                }
                args.put("newRoomNumber", newRoomNumber.trim());
                args.put("reason", justification);
            }
            case FOLIO_CREDIT -> {
                if (creditAmount == null || creditAmount <= 0) {
                    return new ProposalResult(false, null, "a positive creditAmount is required for FOLIO_CREDIT");
                }
                BigDecimal amount = BigDecimal.valueOf(creditAmount).setScale(2, java.math.RoundingMode.HALF_UP);
                if (amount.compareTo(HARD_CAP_USD) > 0) {
                    return new ProposalResult(false, null, "credits above $" + HARD_CAP_USD
                            + " need the General Manager and cannot be proposed here");
                }
                args.put("amount", amount);
                args.put("reason", justification);
            }
            case LATE_CHECKOUT -> {
                try {
                    args.put("checkoutTime", LocalTime.parse(checkoutTime.trim()).toString());
                } catch (RuntimeException e) {
                    return new ProposalResult(false, null, "checkoutTime must be HH:mm for LATE_CHECKOUT");
                }
            }
        }
        // Two agents may propose the same action in one request (e.g. room assignment and service recovery).
        Optional<String> existing = jdbc.sql("""
                SELECT id FROM pending_actions WHERE trace_id = :t AND action_type = :a AND confirmation_number = :c""")
                .param("t", traceId).param("a", type.name()).param("c", conf).query(String.class).optional();
        if (existing.isPresent()) {
            return new ProposalResult(true, existing.get(), "already proposed in this request");
        }
        String id = "APR-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        jdbc.sql("""
                INSERT INTO pending_actions (id, trace_id, conversation_id, proposed_by_agent, action_type,
                    confirmation_number, arguments, justification, status)
                VALUES (:id, :t, :conv, :agent, :type, :conf, CAST(:args AS jsonb), :just, 'PENDING')""")
                .param("id", id).param("t", traceId).param("conv", conversationId).param("agent", agentId)
                .param("type", type.name()).param("conf", conf).param("args", json(args))
                .param("just", justification).update();
        registry.audit(traceId, agentId, "approval.propose", id, "PENDING",
                Map.of("actionType", type.name(), "confirmationNumber", conf));
        return new ProposalResult(true, id, "pending manager approval");
    }

    public List<PendingAction> list(String status) {
        String sql = "SELECT *, arguments::text AS args_json, result::text AS result_json FROM pending_actions"
                + (status == null ? "" : " WHERE status = :s") + " ORDER BY created_at DESC LIMIT 100";
        var spec = jdbc.sql(sql);
        if (status != null) {
            spec = spec.param("s", status);
        }
        return spec.query(this::map).list();
    }

    public List<PendingAction> forTrace(String traceId) {
        return jdbc.sql("""
                SELECT *, arguments::text AS args_json, result::text AS result_json FROM pending_actions
                WHERE trace_id = :t ORDER BY created_at""").param("t", traceId).query(this::map).list();
    }

    public Optional<PendingAction> get(String id) {
        return jdbc.sql("SELECT *, arguments::text AS args_json, result::text AS result_json FROM pending_actions WHERE id = :id")
                .param("id", id).query(this::map).optional();
    }

    /** Approve and execute through the MCP tool. The tool can still refuse (e.g. tier credit limit). */
    public PendingAction approve(String id, String approver, String note) {
        PendingAction action = pending(id);
        Map<String, Object> args = new LinkedHashMap<>(action.arguments());
        args.put("approvedBy", approver);
        Map<String, Object> result;
        String status;
        try {
            String raw = mcp.call(action.actionType().mcpTool(), json(args));
            result = parseToolResult(raw);
            status = "executed".equals(result.get("status")) ? "EXECUTED" : "FAILED";
        } catch (RuntimeException e) {
            result = Map.of("status", "error", "reason", String.valueOf(e.getMessage()));
            status = "FAILED";
        }
        decide(id, status, approver, note, result);
        registry.audit(action.traceId(), approver, "approval.approve", id, status,
                Map.of("actionType", action.actionType().name(), "result", result));
        return get(id).orElseThrow();
    }

    public PendingAction reject(String id, String approver, String note) {
        PendingAction action = pending(id);
        decide(id, "REJECTED", approver, note, null);
        registry.audit(action.traceId(), approver, "approval.reject", id, "REJECTED",
                Map.of("actionType", action.actionType().name()));
        return get(id).orElseThrow();
    }

    private PendingAction pending(String id) {
        PendingAction action = get(id).orElseThrow(() -> new IllegalArgumentException("no approval " + id));
        if (!"PENDING".equals(action.status())) {
            throw new IllegalStateException(id + " is already " + action.status());
        }
        return action;
    }

    private void decide(String id, String status, String approver, String note, Map<String, Object> result) {
        jdbc.sql("""
                UPDATE pending_actions SET status = :s, decided_by = :by, decided_at = now(), decision_note = :n,
                    result = CAST(:r AS jsonb) WHERE id = :id AND status = 'PENDING'""")
                .param("s", status).param("by", approver).param("n", note)
                .param("r", result == null ? null : json(result)).param("id", id).update();
    }

    private Map<String, Object> parseToolResult(String raw) {
        try {
            var node = mapper.readTree(raw);
            if (node.isTextual()) {
                node = mapper.readTree(node.asText());
            }
            if (node.isArray() && !node.isEmpty() && node.get(0).has("text")) {
                node = mapper.readTree(node.get(0).get("text").asText());
            }
            return mapper.convertValue(node, new TypeReference<>() {
            });
        } catch (Exception e) {
            return Map.of("status", "unknown", "raw", raw);
        }
    }

    private PendingAction map(ResultSet rs, int row) throws SQLException {
        return new PendingAction(rs.getString("id"), rs.getString("trace_id"), rs.getString("conversation_id"),
                rs.getString("proposed_by_agent"), ActionType.valueOf(rs.getString("action_type")),
                rs.getString("confirmation_number"), readMap(rs.getString("args_json")),
                rs.getString("justification"), rs.getString("status"), rs.getString("decided_by"),
                rs.getTimestamp("decided_at") == null ? null : rs.getTimestamp("decided_at").toInstant(),
                rs.getString("decision_note"), readMap(rs.getString("result_json")),
                rs.getTimestamp("created_at").toInstant());
    }

    private Map<String, Object> readMap(String json) {
        if (json == null) {
            return null;
        }
        try {
            return mapper.readValue(json, new TypeReference<>() {
            });
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String json(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }
}
