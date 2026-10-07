package com.aurora.guestops.commons.agent;

import java.util.List;

/**
 * Declarative description of an agent. Agents are configuration, not classes: every service loads
 * its definitions from {@code agents/catalog.yml} and runs them on the shared {@link LlmAgentExecutor}.
 * That keeps 20 agents consistent and makes the catalog easy to review and govern.
 */
public record AgentDefinition(
        String id,
        String name,
        String description,
        String version,
        String ownerTeam,
        ModelTier modelTier,
        Integer maxOutputTokens,
        Double temperature,
        RiskLevel riskLevel,
        List<Skill> skills,
        List<String> tools,
        String systemPrompt) {

    public enum RiskLevel { LOW, MEDIUM, HIGH }

    public record Skill(String id, String name, String description, List<String> tags, List<String> examples) {
    }

    public List<String> tools() {
        return tools == null ? List.of() : tools;
    }

    public List<Skill> skills() {
        return skills == null ? List.of() : skills;
    }

    public List<String> skillTags() {
        return skills().stream()
                .flatMap(s -> s.tags() == null ? java.util.stream.Stream.<String>empty() : s.tags().stream())
                .distinct().toList();
    }
}
