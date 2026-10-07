package com.aurora.guestops.commons.agent;

import com.aurora.guestops.commons.tokens.TokenUsage;
import java.util.List;
import java.util.Map;

/** Inputs and outputs of one agent run, shared by in-process calls and A2A calls. */
public final class AgentModels {

    private AgentModels() {
    }

    public record AgentRequest(String traceId, String conversationId, String callerAgent, String message,
                               Map<String, Object> context, List<String> history) {
    }

    public record ToolCallRecord(String tool, String input, String outputPreview, long durationMs, String status) {
    }

    public record SourceRecord(String source, String title, String snippet, Double score) {
    }

    public enum Location { IN_PROCESS, A2A }

    public record AgentResult(String agentId, String agentName, Location location, String networkZone,
                              String status, String text, List<ToolCallRecord> toolCalls,
                              List<SourceRecord> sources, List<String> contexts, TokenUsage usage,
                              long latencyMs, String error) {

        public boolean ok() {
            return "completed".equals(status);
        }

        public static AgentResult failed(String agentId, String agentName, Location location, String zone,
                                         String error, long latencyMs) {
            return new AgentResult(agentId, agentName, location, zone, "failed", "", List.of(), List.of(),
                    List.of(), TokenUsage.ZERO, latencyMs, error);
        }

        public static AgentResult rejected(String agentId, String agentName, Location location, String zone,
                                           String reason) {
            return new AgentResult(agentId, agentName, location, zone, "rejected", "", List.of(), List.of(),
                    List.of(), TokenUsage.ZERO, 0, reason);
        }
    }
}
