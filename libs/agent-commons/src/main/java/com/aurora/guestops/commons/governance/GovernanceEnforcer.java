package com.aurora.guestops.commons.governance;

import com.aurora.guestops.commons.registry.RegistryModels.GovernancePolicy;
import com.aurora.guestops.commons.registry.RegistryModels.RegisteredAgent;
import com.aurora.guestops.commons.tokens.TokenBudget;
import java.util.List;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

/**
 * Central policy checks applied before any agent runs, local or remote:
 * is the agent active (kill switch), may this caller invoke it, which tools may it use, and is
 * there token budget left.
 */
@Component
public class GovernanceEnforcer {

    public record Decision(boolean allowed, String reason) {

        static Decision allow() {
            return new Decision(true, "allowed");
        }

        static Decision deny(String reason) {
            return new Decision(false, reason);
        }
    }

    public Decision canInvoke(String caller, RegisteredAgent target, GovernancePolicy policy, TokenBudget budget) {
        if (target == null) {
            return Decision.deny("agent not registered");
        }
        if (!target.isActive()) {
            return Decision.deny("agent status is " + target.status());
        }
        if (policy == null) {
            return Decision.deny("no governance policy; agents without a policy are denied by default");
        }
        if (!policy.callerAllowed(caller)) {
            return Decision.deny(caller + " is not an allowed caller of " + target.agentId());
        }
        if (budget != null && !budget.canAfford(Math.min(policy.maxTokensPerRequest(), 2_000))) {
            return Decision.deny("request token budget exhausted (" + budget.used() + "/" + budget.limit() + ")");
        }
        return Decision.allow();
    }

    /** Least privilege: an agent only ever sees the tools its policy lists, whatever its catalog asks for. */
    public List<ToolCallback> filterTools(GovernancePolicy policy, List<ToolCallback> requested) {
        if (policy == null) {
            return List.of();
        }
        return requested.stream().filter(t -> policy.toolAllowed(t.getToolDefinition().name())).toList();
    }
}
