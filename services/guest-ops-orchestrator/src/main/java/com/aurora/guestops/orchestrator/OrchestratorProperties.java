package com.aurora.guestops.orchestrator;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "guestops.orchestrator")
public record OrchestratorProperties(
        @DefaultValue("4") int maxAgentsPerRequest,
        @DefaultValue("60000") long requestTokenBudget,
        @DefaultValue("90") int agentTimeoutSeconds,
        @DefaultValue("6") int historyTurns,
        @DefaultValue("true") boolean llmSafetyCheck) {
}
