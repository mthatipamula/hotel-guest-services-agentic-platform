package com.aurora.guestops.commons.a2a;

import com.aurora.guestops.commons.a2a.A2a.AgentCapabilities;
import com.aurora.guestops.commons.a2a.A2a.AgentCard;
import com.aurora.guestops.commons.a2a.A2a.AgentProvider;
import com.aurora.guestops.commons.a2a.A2a.AgentSkill;
import com.aurora.guestops.commons.agent.AgentDefinition;
import java.util.List;
import java.util.Map;

/** Builds an A2A Agent Card from a catalog entry. */
public final class AgentCards {

    private AgentCards() {
    }

    public static String agentUrl(String publicBaseUrl, String agentId) {
        return trim(publicBaseUrl) + "/a2a/" + agentId;
    }

    public static String cardUrl(String publicBaseUrl, String agentId) {
        return agentUrl(publicBaseUrl, agentId) + A2a.WELL_KNOWN_CARD;
    }

    public static AgentCard from(AgentDefinition def, String publicBaseUrl, String organization) {
        List<AgentSkill> skills = def.skills().stream()
                .map(s -> new AgentSkill(s.id(), s.name(), s.description(), s.tags(), s.examples()))
                .toList();
        return new AgentCard(
                A2a.PROTOCOL_VERSION,
                def.name(),
                def.description(),
                agentUrl(publicBaseUrl, def.id()),
                "JSONRPC",
                def.version() == null ? "1.0.0" : def.version(),
                new AgentProvider(organization, publicBaseUrl),
                new AgentCapabilities(false, false, false),
                List.of("text/plain", "application/json"),
                List.of("text/plain", "application/json"),
                skills,
                Map.of("bearer", Map.of("type", "http", "scheme", "bearer",
                        "description", "Shared token locally; Google-signed ID token on Cloud Run")),
                List.of(Map.of("bearer", List.of())));
    }

    private static String trim(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
