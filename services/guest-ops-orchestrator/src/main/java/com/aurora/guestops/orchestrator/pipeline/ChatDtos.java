package com.aurora.guestops.orchestrator.pipeline;

import com.aurora.guestops.commons.agent.AgentModels.SourceRecord;
import com.aurora.guestops.commons.agent.AgentModels.ToolCallRecord;
import com.aurora.guestops.orchestrator.approvals.PendingAction;
import java.util.List;
import java.util.Map;

public final class ChatDtos {

    private ChatDtos() {
    }

    public record ChatRequest(String conversationId, String message, String confirmationNumber, String staffName) {
    }

    public record Guardrails(boolean inputBlocked, String inputBlockReason, boolean piiMasked,
                             String safetyCategory, boolean safetyDegraded, List<String> outputViolations) {
    }

    public record Plan(List<String> agents, String intent, String urgency, Double confidence, String reasoning,
                       boolean routerFallback, List<String> skipped) {
    }

    public record AgentRun(String agentId, String agentName, String location, String networkZone, String status,
                           long latencyMs, long tokens, String model, double costUsd, List<ToolCallRecord> toolCalls,
                           List<SourceRecord> sources, String output, String error,
                           AgentInvoker.Governance governance) {
    }

    public record Tokens(long total, long budget, double estimatedCostUsd, Map<String, Long> byAgent) {
    }

    public record ChatResponse(String traceId, String conversationId, String answer, boolean blocked,
                               Guardrails guardrails, Plan plan, List<AgentRun> agentRuns,
                               List<PendingAction> approvals, Tokens tokens, List<String> contexts,
                               long latencyMs) {
    }
}
