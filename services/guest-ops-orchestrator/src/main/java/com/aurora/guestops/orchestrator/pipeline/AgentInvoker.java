package com.aurora.guestops.orchestrator.pipeline;

import com.aurora.guestops.commons.a2a.A2a;
import com.aurora.guestops.commons.a2a.A2a.AgentCard;
import com.aurora.guestops.commons.a2a.A2a.Message;
import com.aurora.guestops.commons.a2a.A2a.Part;
import com.aurora.guestops.commons.a2a.A2a.Task;
import com.aurora.guestops.commons.a2a.A2aClient;
import com.aurora.guestops.commons.agent.AgentDefinition;
import com.aurora.guestops.commons.agent.AgentModels.AgentRequest;
import com.aurora.guestops.commons.agent.AgentModels.AgentResult;
import com.aurora.guestops.commons.agent.AgentModels.Location;
import com.aurora.guestops.commons.agent.AgentModels.SourceRecord;
import com.aurora.guestops.commons.agent.AgentModels.ToolCallRecord;
import com.aurora.guestops.commons.agent.ChatClients;
import com.aurora.guestops.commons.agent.LlmAgentExecutor;
import com.aurora.guestops.commons.config.GuestOpsProperties;
import com.aurora.guestops.commons.governance.GovernanceEnforcer;
import com.aurora.guestops.commons.governance.GovernanceEnforcer.Decision;
import com.aurora.guestops.commons.guardrails.PiiRedactor;
import com.aurora.guestops.commons.mcp.ToolRegistry;
import com.aurora.guestops.commons.registry.RegistryClient;
import com.aurora.guestops.commons.registry.RegistryModels.DataClassification;
import com.aurora.guestops.commons.registry.RegistryModels.GovernancePolicy;
import com.aurora.guestops.commons.registry.RegistryModels.RegisteredAgent;
import com.aurora.guestops.commons.tokens.TokenBudget;
import com.aurora.guestops.commons.tokens.TokenUsage;
import com.aurora.guestops.orchestrator.approvals.ActionProposalTools;
import com.aurora.guestops.orchestrator.approvals.ApprovalService;
import com.aurora.guestops.orchestrator.tokens.TokenLedger;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

/**
 * Runs one agent with governance applied, in-process or over A2A. The same checks apply to both:
 * agent status (kill switch), caller allowlist, daily and per-request token budgets, tool allowlist,
 * and data classification (what guest data may leave the core network).
 */
@Component
public class AgentInvoker {

    private static final Logger log = LoggerFactory.getLogger(AgentInvoker.class);
    static final String CALLER = "guest-ops-orchestrator";

    /** What the trace shows about governance for one agent call. */
    public record Governance(String decision, String reason, String dataClassification, List<String> toolsGranted,
                             List<String> toolsDenied, boolean piiStripped) {
    }

    public record Invocation(AgentResult result, Governance governance, String model, double costUsd) {
    }

    private final LlmAgentExecutor executor;
    private final ToolRegistry tools;
    private final RegistryClient registry;
    private final GovernanceEnforcer governance;
    private final ApprovalService approvals;
    private final TokenLedger ledger;
    private final ChatClients chatClients;
    private final A2aClient a2a;
    private final PiiRedactor redactor;
    private final GuestOpsProperties props;
    private final ObjectMapper mapper;
    // A2A discovery result (Agent Card) per agent, refreshed every few minutes.
    private final Cache<String, AgentCard> cards = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(5)).maximumSize(200).build();

    public AgentInvoker(LlmAgentExecutor executor, ToolRegistry tools, RegistryClient registry,
                        GovernanceEnforcer governance, ApprovalService approvals, TokenLedger ledger,
                        ChatClients chatClients, A2aClient a2a, PiiRedactor redactor, GuestOpsProperties props,
                        ObjectMapper mapper) {
        this.executor = executor;
        this.tools = tools;
        this.registry = registry;
        this.governance = governance;
        this.approvals = approvals;
        this.ledger = ledger;
        this.chatClients = chatClients;
        this.a2a = a2a;
        this.redactor = redactor;
        this.props = props;
        this.mapper = mapper;
    }

    public Invocation invokeLocal(AgentDefinition def, AgentRequest request, TokenBudget budget) {
        RegisteredAgent registered = registry.getAgent(def.id()).orElse(null);
        GovernancePolicy policy = policyOrNull(def.id());
        Decision d = check(def.id(), registered, policy, budget);
        if (!d.allowed()) {
            return denied(def.id(), def.name(), Location.IN_PROCESS, props.networkZone(), d, policy, request.traceId());
        }
        List<ToolCallback> requested = new ArrayList<>(tools.resolve(def.tools()));
        if (def.tools().contains("proposeAction")) {
            requested.addAll(new ActionProposalTools(approvals, request.traceId(), request.conversationId(), def.id(),
                    staffSuppliedReservations(request)).callbacks());
        }
        List<ToolCallback> granted = governance.filterTools(policy, requested);
        List<String> grantedNames = granted.stream().map(t -> t.getToolDefinition().name()).toList();
        List<String> deniedNames = requested.stream().map(t -> t.getToolDefinition().name())
                .filter(n -> !grantedNames.contains(n)).toList();
        int maxTokens = Math.min(def.maxOutputTokens() == null ? 2048 : def.maxOutputTokens(), policy.maxOutputTokens());

        AgentResult result = executor.execute(def, request, granted, maxTokens, props.networkZone());
        String model = chatClients.modelName(def.modelTier());
        double cost = account(request.traceId(), def.id(), model, result.usage(), budget);
        registry.audit(request.traceId(), CALLER, "agent.invoke", def.id(), result.status().toUpperCase(),
                Map.of("location", "IN_PROCESS", "tools", result.toolCalls().size(), "tokens", result.usage().totalTokens()));
        return new Invocation(result, new Governance("ALLOWED", d.reason(), policy.dataClassification().name(),
                grantedNames, deniedNames, false), model, cost);
    }

    /** A2A: registry gives the card URL, the Agent Card gives the endpoint, then JSON-RPC message/send. */
    public Invocation invokeRemote(RegisteredAgent agent, AgentRequest request, TokenBudget budget) {
        GovernancePolicy policy = policyOrNull(agent.agentId());
        Decision d = check(agent.agentId(), agent, policy, budget);
        if (!d.allowed()) {
            return denied(agent.agentId(), agent.name(), Location.A2A, agent.networkZone(), d, policy, request.traceId());
        }
        long start = System.nanoTime();
        DataClassification cls = policy.dataClassification();
        Map<String, Object> context = shapeContext(request.context(), cls);
        String message = cls == DataClassification.CONFIDENTIAL_PII ? request.message()
                : depersonalise(request.message(), request.context());
        boolean stripped = cls != DataClassification.CONFIDENTIAL_PII;
        Map<String, Object> data = new LinkedHashMap<>(context);
        if (cls != DataClassification.PARTNER_SAFE) {
            data.put("history", request.history());
        }
        try {
            AgentCard card = cards.get(agent.agentId(), id -> a2a.fetchCard(agent.cardUrl()));
            Task task = a2a.sendMessage(card.url(),
                    Message.user(request.conversationId(), List.of(Part.text(message), Part.data(data))),
                    Map.of("callerAgent", CALLER, "traceId", request.traceId()));
            AgentResult result = fromTask(agent, task, elapsed(start));
            String model = String.valueOf(task.resultData().getOrDefault("model", "remote"));
            double cost = account(request.traceId(), agent.agentId(), model, result.usage(), budget);
            registry.audit(request.traceId(), CALLER, "agent.invoke", agent.agentId(), result.status().toUpperCase(),
                    Map.of("location", "A2A", "zone", String.valueOf(agent.networkZone()), "piiStripped", stripped));
            return new Invocation(result, new Governance("ALLOWED", d.reason(), cls.name(),
                    policy.allowedTools(), List.of(), stripped), model, cost);
        } catch (RuntimeException e) {
            cards.invalidate(agent.agentId());
            log.warn("A2A call to {} failed: {}", agent.agentId(), e.toString());
            registry.audit(request.traceId(), CALLER, "agent.invoke", agent.agentId(), "FAILED",
                    Map.of("location", "A2A", "error", String.valueOf(e.getMessage())));
            return new Invocation(AgentResult.failed(agent.agentId(), agent.name(), Location.A2A, agent.networkZone(),
                    e.getMessage(), elapsed(start)),
                    new Governance("ALLOWED", d.reason(), cls.name(), List.of(), List.of(), stripped), "remote", 0);
        }
    }

    private static final java.util.regex.Pattern CONFIRMATION = java.util.regex.Pattern.compile("(?i)\\bAUR-\\d{5}\\b");

    /**
     * Reservations staff actually identified: the one selected in the console, plus any confirmation number
     * written in the request or recent conversation. Agents may only propose actions for these, so a model
     * that guesses or invents a guest cannot queue an action against the wrong reservation.
     */
    static java.util.Set<String> staffSuppliedReservations(AgentRequest request) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        if (request.context() != null && request.context().get("confirmationNumber") instanceof String c) {
            out.add(c.toUpperCase());
        }
        List<String> texts = new ArrayList<>();
        texts.add(request.message());
        if (request.history() != null) {
            texts.addAll(request.history());
        }
        for (String t : texts) {
            java.util.regex.Matcher m = CONFIRMATION.matcher(t == null ? "" : t);
            while (m.find()) {
                out.add(m.group().toUpperCase());
            }
        }
        return out;
    }

    private Decision check(String agentId, RegisteredAgent registered, GovernancePolicy policy, TokenBudget budget) {
        Decision d = governance.canInvoke(CALLER, registered, policy, budget);
        if (d.allowed() && ledger.usedToday(agentId) >= policy.dailyTokenBudget()) {
            return new Decision(false, "daily token budget of " + policy.dailyTokenBudget() + " exhausted");
        }
        return d;
    }

    private Invocation denied(String id, String name, Location loc, String zone, Decision d, GovernancePolicy policy,
                              String traceId) {
        registry.audit(traceId, CALLER, "agent.invoke", id, "DENIED", Map.of("reason", d.reason()));
        return new Invocation(AgentResult.rejected(id, name, loc, zone, d.reason()),
                new Governance("DENIED", d.reason(), policy == null ? "UNKNOWN" : policy.dataClassification().name(),
                        List.of(), List.of(), false), null, 0);
    }

    private GovernancePolicy policyOrNull(String agentId) {
        try {
            return registry.policy(agentId);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private double account(String traceId, String agentId, String model, TokenUsage usage, TokenBudget budget) {
        budget.consume(usage);
        return ledger.record(traceId, agentId, model, usage).doubleValue();
    }

    /** Data minimisation by classification: partners never receive identity data or confirmation numbers. */
    @SuppressWarnings("unchecked")
    Map<String, Object> shapeContext(Map<String, Object> context, DataClassification cls) {
        Map<String, Object> out = new LinkedHashMap<>(context == null ? Map.of() : context);
        if (cls == DataClassification.CONFIDENTIAL_PII) {
            return out;
        }
        if (out.get("reservation") instanceof Map<?, ?> r) {
            Map<String, Object> res = new LinkedHashMap<>((Map<String, Object>) r);
            res.remove("guest_name");
            res.remove("guest_id");
            if (cls == DataClassification.PARTNER_SAFE) {
                res.remove("confirmation_number");
                res.remove("room_number");
                res.remove("rate_plan");
                res.remove("nightly_rate");
                res.remove("special_requests");
            }
            out.put("reservation", res);
        }
        if (cls == DataClassification.PARTNER_SAFE) {
            out.remove("confirmationNumber");
            out = redactor.partnerSafe(out);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    String depersonalise(String message, Map<String, Object> context) {
        String text = redactor.redact(message).text();
        if (context != null && context.get("reservation") instanceof Map<?, ?> r) {
            Object name = ((Map<String, Object>) r).get("guest_name");
            if (name instanceof String full && !full.isBlank()) {
                text = text.replace(full, "the guest");
                for (String part : full.split("\\s+")) {
                    if (part.length() > 2) {
                        text = text.replaceAll("\\b" + java.util.regex.Pattern.quote(part) + "\\b", "the guest");
                    }
                }
            }
        }
        return text;
    }

    private AgentResult fromTask(RegisteredAgent agent, Task task, long latencyMs) {
        Map<String, Object> data = task.resultData();
        String state = task.status() == null ? "failed" : task.status().state();
        String status = A2a.TaskState.COMPLETED.equals(state) ? "completed"
                : A2a.TaskState.REJECTED.equals(state) ? "rejected" : "failed";
        List<ToolCallRecord> toolCalls = convert(data.get("toolCalls"), new TypeReference<>() {
        });
        List<SourceRecord> sources = convert(data.get("sources"), new TypeReference<>() {
        });
        List<String> contexts = convert(data.get("contexts"), new TypeReference<>() {
        });
        TokenUsage usage = data.get("usage") == null ? TokenUsage.ZERO : mapper.convertValue(data.get("usage"), TokenUsage.class);
        String error = "completed".equals(status) ? null
                : task.status() != null && task.status().message() != null ? task.status().message().firstText()
                : String.valueOf(data.get("error"));
        return new AgentResult(agent.agentId(), agent.name(), Location.A2A, agent.networkZone(), status,
                task.resultText(), toolCalls, sources, contexts, usage, latencyMs, error);
    }

    private <T> List<T> convert(Object value, TypeReference<List<T>> type) {
        return value == null ? List.of() : mapper.convertValue(value, type);
    }

    private static long elapsed(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }
}
