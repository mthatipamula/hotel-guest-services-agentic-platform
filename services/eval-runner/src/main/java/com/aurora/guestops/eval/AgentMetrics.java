package com.aurora.guestops.eval;

import com.aurora.guestops.eval.GoldenDataset.Case;
import com.aurora.guestops.eval.GoldenDataset.Expected;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic agent evaluation: checks behaviour that code can verify exactly, so no judge is
 * needed. Routing, tool trajectory, human-approval proposals, guardrail decisions, data
 * minimisation for partner agents, required and forbidden content, latency and tokens.
 */
public final class AgentMetrics {

    private AgentMetrics() {
    }

    public static Map<String, Object> evaluate(Case c, JsonNode response) {
        Expected e = c.expected();
        Map<String, Object> m = new LinkedHashMap<>();
        boolean blocked = response.path("blocked").asBoolean(false);
        m.put("blocked", blocked);
        m.put("guardrailCorrect", blocked == e.expectBlocked());

        Set<String> agents = new LinkedHashSet<>();
        response.path("plan").path("agents").forEach(a -> agents.add(a.asText()));
        Set<String> ran = new LinkedHashSet<>();
        Set<String> tools = new LinkedHashSet<>();
        List<String> trajectory = new ArrayList<>();
        Set<String> stripped = new LinkedHashSet<>();
        for (JsonNode run : response.path("agentRuns")) {
            ran.add(run.path("agentId").asText());
            for (JsonNode t : run.path("toolCalls")) {
                tools.add(t.path("tool").asText());
                trajectory.add(run.path("agentId").asText() + ":" + t.path("tool").asText());
            }
            if (run.path("governance").path("piiStripped").asBoolean(false)) {
                stripped.add(run.path("agentId").asText());
            }
        }
        Set<String> approvals = new LinkedHashSet<>();
        response.path("approvals").forEach(a -> approvals.add(a.path("actionType").asText()));

        m.put("plannedAgents", agents);
        m.put("toolsCalled", tools);
        m.put("trajectory", trajectory);
        m.put("approvalsProposed", approvals);

        if (!e.expectBlocked()) {
            m.put("routingRecall", recall(e.agents(), agents));
            m.put("routingPrecision", e.agents().isEmpty() ? null
                    : agents.isEmpty() ? 0.0 : (double) intersect(e.agents(), agents) / agents.size());
            m.put("toolRecall", recall(e.tools(), tools));
            m.put("approvalCorrect", e.approvals().isEmpty() || approvals.containsAll(e.approvals()));
            m.put("piiStrippedCorrect", e.piiStrippedFor().isEmpty() || stripped.containsAll(e.piiStrippedFor()));
            m.put("allAgentsSucceeded", ran.isEmpty() ? null : allSucceeded(response));
        }
        String answer = response.path("answer").asText("").toLowerCase(Locale.ROOT);
        List<String> missing = e.mustContain().stream().filter(s -> !answer.contains(s.toLowerCase(Locale.ROOT))).toList();
        List<String> forbidden = e.mustNotContain().stream().filter(s -> answer.contains(s.toLowerCase(Locale.ROOT))).toList();
        m.put("missingRequiredText", missing);
        m.put("forbiddenTextFound", forbidden);
        m.put("contentCorrect", missing.isEmpty() && forbidden.isEmpty());
        m.put("latencyMs", response.path("latencyMs").asLong());
        m.put("tokens", response.path("tokens").path("total").asLong());
        m.put("costUsd", response.path("tokens").path("estimatedCostUsd").asDouble());
        m.put("routerFallback", response.path("plan").path("routerFallback").asBoolean(false));
        return m;
    }

    private static boolean allSucceeded(JsonNode response) {
        for (JsonNode run : response.path("agentRuns")) {
            if (!"completed".equals(run.path("status").asText())) {
                return false;
            }
        }
        return true;
    }

    /** Expected items that were found; null when nothing was expected (not applicable). */
    static Double recall(List<String> expected, Set<String> actual) {
        if (expected.isEmpty()) {
            return null;
        }
        return (double) intersect(expected, actual) / expected.size();
    }

    private static long intersect(List<String> expected, Set<String> actual) {
        return expected.stream().filter(actual::contains).count();
    }
}
