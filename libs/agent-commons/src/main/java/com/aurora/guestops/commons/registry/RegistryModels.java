package com.aurora.guestops.commons.registry;

import com.aurora.guestops.commons.a2a.A2a.AgentCard;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Wire models for the agent registry, MCP registry, governance policies and audit trail. */
public final class RegistryModels {

    private RegistryModels() {
    }

    public enum Protocol { IN_PROCESS, A2A }

    public enum AgentStatus { ACTIVE, SUSPENDED, UNREACHABLE, DEPRECATED }

    public enum DataClassification {
        /** May receive full guest data (internal agents). */
        CONFIDENTIAL_PII,
        /** Internal, but guest identity is reduced to what the task needs. */
        INTERNAL,
        /** External partner: no guest names, emails, phones or loyalty numbers. */
        PARTNER_SAFE
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AgentRegistration(String agentId, String name, String description, String version,
                                    String ownerTeam, String hostService, String networkZone, Protocol protocol,
                                    String endpointUrl, String cardUrl, List<String> skills, String riskLevel,
                                    AgentCard card) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RegisteredAgent(String agentId, String name, String description, String version,
                                  String ownerTeam, String hostService, String networkZone, Protocol protocol,
                                  String endpointUrl, String cardUrl, List<String> skills, String riskLevel,
                                  AgentStatus status, Instant lastHeartbeat, Instant registeredAt,
                                  AgentCard card) {

        public boolean isActive() {
            return status == AgentStatus.ACTIVE;
        }
    }

    public record McpToolInfo(String name, String description) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record McpServerRegistration(String serverId, String name, String version, String url, String endpoint,
                                        String transport, String networkZone, List<McpToolInfo> tools) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RegisteredMcpServer(String serverId, String name, String version, String url, String endpoint,
                                      String transport, String networkZone, List<McpToolInfo> tools,
                                      String status, Instant lastHeartbeat) {
    }

    public record GovernancePolicy(String agentId, List<String> allowedTools, List<String> allowedCallers,
                                   int maxOutputTokens, int maxTokensPerRequest, long dailyTokenBudget,
                                   boolean requiresHumanApproval, DataClassification dataClassification) {

        public boolean callerAllowed(String caller) {
            return allowedCallers == null || allowedCallers.contains("*") || allowedCallers.contains(caller);
        }

        public boolean toolAllowed(String tool) {
            return allowedTools != null && (allowedTools.contains("*") || allowedTools.contains(tool));
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AuditEvent(String traceId, String actor, String action, String target, String decision,
                             Map<String, Object> details, Instant timestamp) {
    }

    public record StatusChange(AgentStatus status, String reason, String changedBy) {
    }
}
