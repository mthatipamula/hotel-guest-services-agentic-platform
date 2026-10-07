package com.aurora.guestops.orchestrator.pipeline;

import com.aurora.guestops.commons.agent.AgentCatalog;
import com.aurora.guestops.commons.agent.AgentDefinition;
import com.aurora.guestops.commons.agent.AgentModels.AgentRequest;
import com.aurora.guestops.commons.agent.AgentModels.AgentResult;
import com.aurora.guestops.commons.agent.LlmAgentExecutor;
import com.aurora.guestops.commons.guardrails.InputGuardrail;
import com.aurora.guestops.commons.guardrails.OutputGuardrail;
import com.aurora.guestops.commons.mcp.McpToolsResolver;
import com.aurora.guestops.commons.registry.RegistryClient;
import com.aurora.guestops.commons.registry.RegistryModels.Protocol;
import com.aurora.guestops.commons.registry.RegistryModels.RegisteredAgent;
import com.aurora.guestops.commons.tokens.TokenBudget;
import com.aurora.guestops.orchestrator.OrchestratorProperties;
import com.aurora.guestops.orchestrator.approvals.ApprovalService;
import com.aurora.guestops.orchestrator.approvals.PendingAction;
import com.aurora.guestops.orchestrator.memory.ConversationStore;
import com.aurora.guestops.orchestrator.pipeline.ChatDtos.AgentRun;
import com.aurora.guestops.orchestrator.pipeline.ChatDtos.ChatRequest;
import com.aurora.guestops.orchestrator.pipeline.ChatDtos.ChatResponse;
import com.aurora.guestops.orchestrator.pipeline.ChatDtos.Guardrails;
import com.aurora.guestops.orchestrator.pipeline.ChatDtos.Plan;
import com.aurora.guestops.orchestrator.pipeline.ChatDtos.Tokens;
import com.aurora.guestops.orchestrator.tokens.TokenLedger;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * One staff request through the multi-agent pipeline:
 *
 * <pre>
 * input guardrail -> safety-guard agent -> triage-router (agent directory from the registry)
 *   -> specialists in parallel: in-process agents and remote A2A agents, each behind governance
 *   -> response-composer (skipped when one agent answered: saves a model call)
 *   -> output guardrail -> memory -> response with full trace
 * </pre>
 */
@Service
public class GuestOpsOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(GuestOpsOrchestrator.class);
    /** Agents that run as pipeline stages, never as routed specialists. */
    static final Set<String> PIPELINE_AGENTS = Set.of("triage-router", "safety-guard", "response-composer",
            "quality-judge-agent");

    private final AgentCatalog catalog;
    private final InputGuardrail inputGuardrail;
    private final OutputGuardrail outputGuardrail;
    private final SafetyGuard safetyGuard;
    private final TriageRouter router;
    private final AgentInvoker invoker;
    private final LlmAgentExecutor executor;
    private final RegistryClient registry;
    private final McpToolsResolver mcp;
    private final ApprovalService approvals;
    private final ConversationStore memory;
    private final TokenLedger ledger;
    private final OrchestratorProperties props;
    private final ExecutorService pool;
    private final ObjectMapper mapper;

    public GuestOpsOrchestrator(AgentCatalog catalog, InputGuardrail inputGuardrail, OutputGuardrail outputGuardrail,
                                SafetyGuard safetyGuard, TriageRouter router, AgentInvoker invoker,
                                LlmAgentExecutor executor, RegistryClient registry, McpToolsResolver mcp,
                                ApprovalService approvals, ConversationStore memory, TokenLedger ledger,
                                OrchestratorProperties props, ExecutorService agentExecutor, ObjectMapper mapper) {
        this.catalog = catalog;
        this.inputGuardrail = inputGuardrail;
        this.outputGuardrail = outputGuardrail;
        this.safetyGuard = safetyGuard;
        this.router = router;
        this.invoker = invoker;
        this.executor = executor;
        this.registry = registry;
        this.mcp = mcp;
        this.approvals = approvals;
        this.memory = memory;
        this.ledger = ledger;
        this.props = props;
        this.pool = agentExecutor;
        this.mapper = mapper;
    }

    public ChatResponse chat(ChatRequest req) {
        long start = System.nanoTime();
        String traceId = "tr-" + UUID.randomUUID().toString().substring(0, 12);
        String convId = req.conversationId() == null || req.conversationId().isBlank()
                ? "conv-" + UUID.randomUUID().toString().substring(0, 8) : req.conversationId();
        TokenBudget budget = new TokenBudget(props.requestTokenBudget());
        Map<String, Long> tokensByAgent = new LinkedHashMap<>();
        double[] cost = {0};

        // 1. Deterministic input guardrail
        InputGuardrail.Verdict verdict = inputGuardrail.check(req.message());
        if (!verdict.allowed()) {
            registry.audit(traceId, "staff", "guardrail.input", "request", "BLOCKED", Map.of("reason", verdict.reason()));
            return blocked(traceId, convId, start, blockMessage(verdict.reason()),
                    new Guardrails(true, verdict.reason(), false, null, false, List.of()));
        }
        String text = verdict.sanitizedText();

        // 2. LLM safety classifier
        String safetyCategory = "SKIPPED";
        boolean safetyDegraded = false;
        if (props.llmSafetyCheck()) {
            SafetyGuard.Outcome s = safetyGuard.check(text);
            account(traceId, safetyGuard.agentId(), s.model(), s.usage(), budget, tokensByAgent, cost);
            safetyCategory = s.verdict().category();
            safetyDegraded = s.degraded();
            if (!s.verdict().allowed()) {
                registry.audit(traceId, safetyGuard.agentId(), "guardrail.safety", "request", "BLOCKED",
                        Map.of("category", String.valueOf(s.verdict().category())));
                return blocked(traceId, convId, start, "I can't help with that request: " + s.verdict().reason()
                                + ". If this is a genuine guest need, please involve the duty manager.",
                        new Guardrails(true, "safety:" + s.verdict().category(), verdict.piiMasked(),
                                safetyCategory, false, List.of()));
            }
        }

        // 3. Context: memory + reservation prefetched over MCP (no LLM tokens spent)
        List<String> history = memory.recent(convId, props.historyTurns());
        Map<String, Object> context = buildContext(req);

        // 4. Plan with the triage router over the live agent directory
        Map<String, RegisteredAgent> remote = new LinkedHashMap<>();
        List<TriageRouter.DirectoryEntry> directory = directory(remote);
        TriageRouter.Outcome routed = router.route(text, history, directory, props.maxAgentsPerRequest());
        account(traceId, router.agentId(), routed.model(), routed.usage(), budget, tokensByAgent, cost);

        // 5. Specialists in parallel
        List<String> skipped = new ArrayList<>();
        AgentRequest agentRequest = new AgentRequest(traceId, convId, AgentInvoker.CALLER, text, context, history);
        List<Future<AgentInvoker.Invocation>> futures = new ArrayList<>();
        for (String agentId : routed.plan().agents()) {
            AgentDefinition local = catalog.find(agentId).filter(d -> !PIPELINE_AGENTS.contains(d.id())).orElse(null);
            if (local != null) {
                futures.add(pool.submit(() -> invoker.invokeLocal(local, agentRequest, budget)));
            } else if (remote.containsKey(agentId)) {
                RegisteredAgent r = remote.get(agentId);
                futures.add(pool.submit(() -> invoker.invokeRemote(r, agentRequest, budget)));
            } else {
                skipped.add(agentId + " (not available)");
            }
        }
        List<AgentRun> runs = new ArrayList<>();
        List<AgentResult> successes = new ArrayList<>();
        List<String> contexts = new ArrayList<>();
        for (Future<AgentInvoker.Invocation> f : futures) {
            try {
                AgentInvoker.Invocation inv = f.get(props.agentTimeoutSeconds(), TimeUnit.SECONDS);
                AgentResult r = inv.result();
                tokensByAgent.merge(r.agentId(), r.usage().totalTokens(), Long::sum);
                cost[0] += inv.costUsd();
                runs.add(run(r, inv.model(), inv.costUsd(), inv.governance()));
                if (r.ok() && !r.text().isBlank()) {
                    successes.add(r);
                    contexts.addAll(r.contexts());
                }
            } catch (java.util.concurrent.TimeoutException e) {
                f.cancel(true);
                skipped.add("an agent timed out after " + props.agentTimeoutSeconds() + "s");
            } catch (Exception e) {
                skipped.add("agent error: " + e.getMessage());
            }
        }

        // 6. Compose (only when more than one specialist answered)
        List<PendingAction> proposed = approvals.forTrace(traceId);
        String answer;
        if (successes.isEmpty()) {
            answer = "No specialist agent could complete this request right now. "
                    + (runs.stream().anyMatch(r -> "rejected".equals(r.status()))
                    ? "Governance blocked one or more agents (see trace). " : "")
                    + "Please handle it manually or try again.";
        } else if (successes.size() == 1) {
            answer = successes.getFirst().text();
        } else {
            answer = compose(traceId, convId, text, successes, proposed, budget, tokensByAgent, cost, runs);
        }

        // 7. Output guardrail
        OutputGuardrail.Result out = outputGuardrail.check(answer, false);
        if (!out.violations().isEmpty()) {
            registry.audit(traceId, "output-guardrail", "guardrail.output", "answer", "MODIFIED",
                    Map.of("violations", out.violations()));
        }

        // 8. Memory: store the guarded answer only
        memory.append(convId, "staff", text, traceId);
        memory.append(convId, "assistant", out.text(), traceId);

        long latency = (System.nanoTime() - start) / 1_000_000;
        log.info("trace={} agents={} tokens={} latencyMs={}", traceId, routed.plan().agents(), budget.used(), latency);
        return new ChatResponse(traceId, convId, out.text(), false,
                new Guardrails(false, null, verdict.piiMasked(), safetyCategory, safetyDegraded, out.violations()),
                new Plan(routed.plan().agents(), routed.plan().intent(), routed.plan().urgency(),
                        routed.plan().confidence(), routed.plan().reasoning(), routed.fallback(), skipped),
                runs, proposed, new Tokens(budget.used(), budget.limit(), round(cost[0]), tokensByAgent),
                contexts, latency);
    }

    private String compose(String traceId, String convId, String request, List<AgentResult> results,
                           List<PendingAction> proposed, TokenBudget budget, Map<String, Long> tokensByAgent,
                           double[] cost, List<AgentRun> runs) {
        AgentDefinition composer = catalog.get("response-composer");
        StringBuilder sb = new StringBuilder("STAFF REQUEST:\n").append(request).append("\n\nSPECIALIST OUTPUTS:\n");
        for (AgentResult r : results) {
            sb.append("--- ").append(r.agentName()).append(" (").append(r.location()).append(", ")
                    .append(r.networkZone()).append(")\n").append(r.text()).append("\n\n");
        }
        sb.append("ACTIONS PENDING MANAGER APPROVAL:\n");
        if (proposed.isEmpty()) {
            sb.append("none\n");
        }
        proposed.forEach(p -> sb.append("- ").append(p.id()).append(": ").append(p.actionType()).append(' ')
                .append(p.arguments()).append('\n'));
        sb.append("\nWrite the final answer for the staff member.");
        AgentInvoker.Invocation inv = invoker.invokeLocal(composer,
                new AgentRequest(traceId, convId, AgentInvoker.CALLER, sb.toString(), Map.of(), List.of()), budget);
        tokensByAgent.merge(composer.id(), inv.result().usage().totalTokens(), Long::sum);
        cost[0] += inv.costUsd();
        runs.add(run(inv.result(), inv.model(), inv.costUsd(), inv.governance()));
        if (inv.result().ok() && !inv.result().text().isBlank()) {
            return inv.result().text();
        }
        // Composer unavailable or out of budget: return the specialists' answers as they are.
        StringBuilder fallback = new StringBuilder();
        results.forEach(r -> fallback.append(r.agentName()).append(": ").append(r.text()).append("\n\n"));
        return fallback.toString().strip();
    }

    /** Agents the router may choose: this service's specialists plus ACTIVE remote A2A agents. */
    private List<TriageRouter.DirectoryEntry> directory(Map<String, RegisteredAgent> remoteOut) {
        List<TriageRouter.DirectoryEntry> entries = new ArrayList<>();
        List<RegisteredAgent> registered;
        try {
            registered = registry.listAgents();
        } catch (RuntimeException e) {
            log.warn("Registry unavailable; routing to local agents only: {}", e.toString());
            registered = List.of();
        }
        Set<String> suspended = new java.util.HashSet<>();
        for (RegisteredAgent a : registered) {
            if (!a.isActive()) {
                suspended.add(a.agentId());
            } else if (a.protocol() == Protocol.A2A && !PIPELINE_AGENTS.contains(a.agentId())) {
                remoteOut.put(a.agentId(), a);
                entries.add(new TriageRouter.DirectoryEntry(a.agentId(), a.description(), a.skills(),
                        "remote A2A, " + a.networkZone()));
            }
        }
        for (AgentDefinition d : catalog.all()) {
            if (!PIPELINE_AGENTS.contains(d.id()) && !suspended.contains(d.id())) {
                entries.add(new TriageRouter.DirectoryEntry(d.id(), d.description(), d.skillTags(), "in-process"));
            }
        }
        return entries;
    }

    private Map<String, Object> buildContext(ChatRequest req) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("hotel", "Aurora Grand Chicago");
        ctx.put("today", LocalDate.now().toString());
        if (req.staffName() != null && !req.staffName().isBlank()) {
            ctx.put("staffMember", req.staffName());
        }
        if (req.confirmationNumber() != null && !req.confirmationNumber().isBlank()) {
            String conf = req.confirmationNumber().trim().toUpperCase();
            ctx.put("confirmationNumber", conf);
            try {
                JsonNode node = mapper.readTree(mcp.call("getReservation",
                        mapper.writeValueAsString(Map.of("confirmationNumber", conf))));
                if (node.isTextual()) {
                    node = mapper.readTree(node.asText());
                }
                if (node.isArray() && !node.isEmpty() && node.get(0).has("text")) {
                    node = mapper.readTree(node.get(0).get("text").asText());
                }
                if (!node.has("error")) {
                    ctx.put("reservation", mapper.convertValue(node, new TypeReference<Map<String, Object>>() {
                    }));
                }
            } catch (Exception e) {
                log.warn("Could not prefetch reservation {}: {}", conf, e.toString());
            }
        }
        return ctx;
    }

    private void account(String traceId, String agentId, String model, com.aurora.guestops.commons.tokens.TokenUsage usage,
                         TokenBudget budget, Map<String, Long> byAgent, double[] cost) {
        budget.consume(usage);
        byAgent.merge(agentId, usage.totalTokens(), Long::sum);
        cost[0] += ledger.record(traceId, agentId, model, usage).doubleValue();
    }

    private static AgentRun run(AgentResult r, String model, double cost, AgentInvoker.Governance g) {
        return new AgentRun(r.agentId(), r.agentName(), r.location().name(), r.networkZone(), r.status(),
                r.latencyMs(), r.usage().totalTokens(), model, round(cost), r.toolCalls(), r.sources(), r.text(),
                r.error(), g);
    }

    private ChatResponse blocked(String traceId, String convId, long start, String answer, Guardrails g) {
        return new ChatResponse(traceId, convId, answer, true, g,
                new Plan(List.of(), "blocked", "NORMAL", 1.0, g.inputBlockReason(), false, List.of()),
                List.of(), List.of(), new Tokens(0, props.requestTokenBudget(), 0, Map.of()), List.of(),
                (System.nanoTime() - start) / 1_000_000);
    }

    private static String blockMessage(String reason) {
        return switch (reason) {
            case "prompt_injection" -> "This request looks like an attempt to change how the assistant works, so it "
                    + "was blocked. Please describe the guest or operational issue you need help with.";
            case "data_exfiltration" -> "Bulk or sensitive guest data (contact details, payment or ID numbers) "
                    + "can't be retrieved through the assistant. Use the property system with the right access.";
            case "message_too_long" -> "That message is too long. Please summarise the issue in a few sentences.";
            default -> "Please describe the guest or operational issue you need help with.";
        };
    }

    private static double round(double v) {
        return Math.round(v * 1_000_000d) / 1_000_000d;
    }
}
