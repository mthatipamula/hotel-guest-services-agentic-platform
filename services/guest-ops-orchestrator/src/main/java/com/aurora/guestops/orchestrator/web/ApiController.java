package com.aurora.guestops.orchestrator.web;

import com.aurora.guestops.commons.mcp.McpToolsResolver;
import com.aurora.guestops.commons.registry.RegistryClient;
import com.aurora.guestops.commons.registry.RegistryModels.AgentStatus;
import com.aurora.guestops.commons.registry.RegistryModels.GovernancePolicy;
import com.aurora.guestops.commons.registry.RegistryModels.RegisteredAgent;
import com.aurora.guestops.commons.registry.RegistryModels.RegisteredMcpServer;
import com.aurora.guestops.commons.registry.RegistryModels.StatusChange;
import com.aurora.guestops.orchestrator.approvals.ApprovalService;
import com.aurora.guestops.orchestrator.approvals.PendingAction;
import com.aurora.guestops.orchestrator.memory.ConversationStore;
import com.aurora.guestops.orchestrator.pipeline.ChatDtos.ChatRequest;
import com.aurora.guestops.orchestrator.pipeline.ChatDtos.ChatResponse;
import com.aurora.guestops.orchestrator.pipeline.GuestOpsOrchestrator;
import com.aurora.guestops.orchestrator.tokens.TokenLedger;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** REST API for the operations console and the eval runner. */
@RestController
public class ApiController {

    private final GuestOpsOrchestrator orchestrator;
    private final ApprovalService approvals;
    private final TokenLedger ledger;
    private final RegistryClient registry;
    private final McpToolsResolver mcp;
    private final ConversationStore memory;
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public ApiController(GuestOpsOrchestrator orchestrator, ApprovalService approvals, TokenLedger ledger,
                         RegistryClient registry, McpToolsResolver mcp, ConversationStore memory, JdbcClient jdbc,
                         ObjectMapper mapper) {
        this.orchestrator = orchestrator;
        this.approvals = approvals;
        this.ledger = ledger;
        this.registry = registry;
        this.mcp = mcp;
        this.memory = memory;
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    // ---------- Chat ----------

    @PostMapping("/api/chat")
    public ChatResponse chat(@RequestBody ChatRequest request) {
        return orchestrator.chat(request);
    }

    @DeleteMapping("/api/conversations/{id}")
    public ResponseEntity<Void> clear(@PathVariable String id) {
        memory.clear(id);
        return ResponseEntity.noContent().build();
    }

    // ---------- Human-in-the-loop approvals ----------

    public record Decision(String approver, String note) {
    }

    @GetMapping("/api/approvals")
    public List<PendingAction> approvals(@RequestParam(required = false) String status) {
        return approvals.list(status);
    }

    @PostMapping("/api/approvals/{id}/approve")
    public PendingAction approve(@PathVariable String id, @RequestBody Decision d) {
        return approvals.approve(id, approver(d), d.note());
    }

    @PostMapping("/api/approvals/{id}/reject")
    public PendingAction reject(@PathVariable String id, @RequestBody Decision d) {
        return approvals.reject(id, approver(d), d.note());
    }

    private static String approver(Decision d) {
        // In production this comes from the identity-aware proxy (IAP) header, not the request body.
        return d == null || d.approver() == null || d.approver().isBlank() ? "duty-manager" : d.approver().trim();
    }

    // ---------- Console data ----------

    @GetMapping("/api/console/reservations")
    public JsonNode reservations() throws Exception {
        JsonNode node = mapper.readTree(mcp.call("listActiveReservations", "{}"));
        if (node.isTextual()) {
            node = mapper.readTree(node.asText());
        }
        if (node.isArray() && !node.isEmpty() && node.get(0).has("type") && node.get(0).has("text")) {
            node = mapper.readTree(node.get(0).get("text").asText());
        }
        return node;
    }

    /** Agent registry joined with governance policies, for the registry and governance views. */
    @GetMapping("/api/console/agents")
    public List<Map<String, Object>> agents() {
        registry.invalidateCaches();
        List<RegisteredAgent> agents = registry.listAgents();
        Map<String, GovernancePolicy> policies = new LinkedHashMap<>();
        registry.get("/api/governance/policies", new ParameterizedTypeReference<List<GovernancePolicy>>() {
        }).forEach(p -> policies.put(p.agentId(), p));
        return agents.stream().map(a -> {
            Map<String, Object> m = mapper.convertValue(a, new TypeReference<>() {
            });
            m.remove("card");
            m.put("hasAgentCard", a.card() != null);
            m.put("policy", policies.get(a.agentId()));
            m.put("tokensToday", ledger.usedToday(a.agentId()));
            return m;
        }).toList();
    }

    @GetMapping("/api/console/agents/{id}/card")
    public Object agentCard(@PathVariable String id) {
        return registry.get("/api/registry/agents/" + id, new ParameterizedTypeReference<RegisteredAgent>() {
        }).card();
    }

    @GetMapping("/api/console/mcp-servers")
    public List<RegisteredMcpServer> mcpServers() {
        return registry.get("/api/registry/mcp-servers", new ParameterizedTypeReference<>() {
        });
    }

    @GetMapping("/api/console/summary")
    public Map<String, Object> summary() {
        return registry.get("/api/registry/summary", new ParameterizedTypeReference<>() {
        });
    }

    public record StatusRequest(AgentStatus status, String reason, String changedBy) {
    }

    /** Governance kill switch, e.g. suspend a misbehaving agent without a deployment. */
    @PostMapping("/api/console/agents/{id}/status")
    public RegisteredAgent setStatus(@PathVariable String id, @RequestBody StatusRequest r) {
        RegisteredAgent updated = registry.post("/api/registry/agents/" + id + "/status",
                new StatusChange(r.status(), r.reason(), r.changedBy() == null ? "ops-console" : r.changedBy()),
                RegisteredAgent.class);
        registry.invalidateCaches();
        return updated;
    }

    @GetMapping("/api/console/audit")
    public List<Map<String, Object>> audit(@RequestParam(required = false) String traceId) {
        return registry.get("/api/audit?limit=100" + (traceId == null ? "" : "&traceId=" + traceId),
                new ParameterizedTypeReference<>() {
                });
    }

    @GetMapping("/api/tokens/summary")
    public Map<String, Object> tokens() {
        return ledger.summary();
    }

    // ---------- Evaluation reports ----------

    @PostMapping("/api/evals")
    public Map<String, Object> saveEval(@RequestBody Map<String, Object> report) throws Exception {
        String id = "EVAL-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        Object summary = report.getOrDefault("summary", Map.of());
        boolean passed = Boolean.TRUE.equals(report.get("passed"));
        jdbc.sql("INSERT INTO eval_runs (id, passed, summary, report) VALUES (:id, :p, CAST(:s AS jsonb), CAST(:r AS jsonb))")
                .param("id", id).param("p", passed).param("s", mapper.writeValueAsString(summary))
                .param("r", mapper.writeValueAsString(report)).update();
        return Map.of("id", id);
    }

    @GetMapping("/api/evals")
    public List<Map<String, Object>> evals() {
        return jdbc.sql("SELECT id, created_at, passed, summary::text AS summary FROM eval_runs ORDER BY created_at DESC LIMIT 20")
                .query().listOfRows();
    }

    @GetMapping("/api/evals/latest")
    public ResponseEntity<JsonNode> latestEval() throws Exception {
        List<String> rows = jdbc.sql("SELECT report::text FROM eval_runs ORDER BY created_at DESC LIMIT 1")
                .query(String.class).list();
        return rows.isEmpty() ? ResponseEntity.noContent().build() : ResponseEntity.ok(mapper.readTree(rows.getFirst()));
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<Map<String, String>> badRequest(RuntimeException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", String.valueOf(e.getMessage())));
    }
}
