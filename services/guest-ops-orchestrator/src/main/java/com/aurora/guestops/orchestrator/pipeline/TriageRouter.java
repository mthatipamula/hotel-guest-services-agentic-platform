package com.aurora.guestops.orchestrator.pipeline;

import com.aurora.guestops.commons.agent.AgentCatalog;
import com.aurora.guestops.commons.agent.AgentDefinition;
import com.aurora.guestops.commons.agent.ChatClients;
import com.aurora.guestops.commons.tokens.TokenUsage;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.vertexai.gemini.VertexAiGeminiChatOptions;
import org.springframework.stereotype.Component;

/**
 * Plans which agents handle a request. The agent directory comes from the registry at request time,
 * so a newly registered remote agent becomes routable without redeploying the orchestrator, and a
 * suspended agent disappears from the plan.
 */
@Component
public class TriageRouter {

    private static final Logger log = LoggerFactory.getLogger(TriageRouter.class);

    public record DirectoryEntry(String agentId, String description, List<String> skills, String location) {
    }

    public record RoutingPlan(List<String> agents, String intent, String urgency, Double confidence, String reasoning) {
    }

    public record Outcome(RoutingPlan plan, TokenUsage usage, String model, boolean fallback) {
    }

    private static final String PROMPT = """
            You are the triage router of a hotel operations multi-agent platform. Staff describe a situation or
            ask a question. Choose the smallest set of specialist agents (1 to %d) that together can fully handle it,
            from the directory below. Prefer fewer agents: every extra agent costs time and tokens.
            Typical combinations:
            - broken room equipment for an occupied room: maintenance-agent, room-assignment-agent, service-recovery-agent
            - guest complaint about service: service-recovery-agent (+ the agent that fixes the cause)
            - "is the room ready": housekeeping-agent
            - pure policy question: policy-advisor-agent only
            - pricing, upgrades for a fee, forecasts, group blocks: the revenue agents
            - car service, tours, restaurants off property, spa: the partner agents
            urgency: LOW, NORMAL, HIGH or CRITICAL (CRITICAL = safety risk or VIP guest unable to use the room).
            intent: 3-8 words. reasoning: at most 20 words.

            DIRECTORY:
            %s""";

    private final ChatClients chatClients;
    private final AgentDefinition def;
    private final BeanOutputConverter<RoutingPlan> converter = new BeanOutputConverter<>(RoutingPlan.class);

    public TriageRouter(ChatClients chatClients, AgentCatalog catalog) {
        this.chatClients = chatClients;
        this.def = catalog.get("triage-router");
    }

    public String agentId() {
        return def.id();
    }

    public Outcome route(String message, List<String> history, List<DirectoryEntry> directory, int maxAgents) {
        Set<String> known = new LinkedHashSet<>();
        StringBuilder dir = new StringBuilder();
        for (DirectoryEntry e : directory) {
            known.add(e.agentId());
            dir.append("- ").append(e.agentId()).append(" [").append(e.location()).append("]: ")
                    .append(e.description()).append(" Skills: ").append(String.join(", ", e.skills())).append('\n');
        }
        String model = chatClients.modelName(def.modelTier());
        StringBuilder user = new StringBuilder();
        if (!history.isEmpty()) {
            user.append("Recent conversation:\n");
            history.forEach(h -> user.append(h).append('\n'));
            user.append('\n');
        }
        user.append("Request:\n").append(message);
        try {
            ChatResponse response = chatClients.forTier(def.modelTier()).prompt()
                    .system(PROMPT.formatted(maxAgents, dir) + "\n" + converter.getFormat())
                    .user(user.toString())
                    .options(VertexAiGeminiChatOptions.builder().model(model).temperature(0.0)
                            .maxOutputTokens(def.maxOutputTokens()).responseMimeType("application/json").build())
                    .call().chatResponse();
            RoutingPlan raw = converter.convert(response.getResult().getOutput().getText());
            List<String> agents = raw == null || raw.agents() == null ? List.of()
                    : raw.agents().stream().filter(known::contains).distinct().limit(maxAgents).toList();
            TokenUsage usage = TokenUsage.of(response.getMetadata().getUsage());
            if (!agents.isEmpty()) {
                return new Outcome(new RoutingPlan(agents, raw.intent(), raw.urgency(), raw.confidence(),
                        raw.reasoning()), usage, model, false);
            }
            log.warn("Router returned no known agents; using keyword fallback");
            return new Outcome(keywordPlan(message, known), usage, model, true);
        } catch (RuntimeException e) {
            log.warn("Router failed, using keyword fallback: {}", e.toString());
            return new Outcome(keywordPlan(message, known), TokenUsage.ZERO, model, true);
        }
    }

    private record Rule(Pattern pattern, String agent) {
    }

    private static final List<Rule> RULES = List.of(
            new Rule(Pattern.compile("\\b(ac|a/c|air ?con|hvac|heat|leak|broken|not working|repair|noise|noisy)\\b"), "maintenance-agent"),
            new Rule(Pattern.compile("\\b(move|another room|different room|swap rooms?)\\b"), "room-assignment-agent"),
            new Rule(Pattern.compile("\\b(complain|complaint|upset|angry|furious|compensat|apolog)"), "service-recovery-agent"),
            new Rule(Pattern.compile("\\b(clean|housekeeping|ready|early arrival|towels?|bedding|turndown)\\b"), "housekeeping-agent"),
            new Rule(Pattern.compile("\\b(folio|bill|charge|minibar|refund|dispute)"), "billing-agent"),
            new Rule(Pattern.compile("\\b(checkout|check-out|check out|reservation|late)\\b"), "reservation-agent"),
            new Rule(Pattern.compile("\\b(loyalty|tier|benefit|platinum|gold|vip|preference)"), "guest-profile-agent"),
            new Rule(Pattern.compile("\\b(dinner|breakfast|restaurant|room service|dining|anniversary|birthday)\\b"), "dining-agent"),
            new Rule(Pattern.compile("\\b(price|rate|quote|cost)\\b"), "pricing-agent"),
            new Rule(Pattern.compile("\\b(upgrade|suite)\\b"), "upsell-agent"),
            new Rule(Pattern.compile("\\b(forecast|occupancy|demand)\\b"), "demand-forecast-agent"),
            new Rule(Pattern.compile("\\b(group|block|wedding|conference)\\b"), "group-sales-agent"),
            new Rule(Pattern.compile("\\b(car|taxi|airport|transfer|ride|limo)\\b"), "ground-transport-agent"),
            new Rule(Pattern.compile("\\b(tour|museum|show|things to do|experience)\\b"), "local-experiences-agent"),
            new Rule(Pattern.compile("\\b(spa|massage|facial|wellness)\\b"), "spa-wellness-agent"),
            new Rule(Pattern.compile("\\b(policy|policies|allowed|rule|pet|cancel)"), "policy-advisor-agent"));

    /** Deterministic fallback so the platform degrades instead of failing when the router model is down. */
    static RoutingPlan keywordPlan(String message, Set<String> known) {
        String m = message.toLowerCase(Locale.ROOT);
        List<String> agents = new ArrayList<>();
        for (Rule r : RULES) {
            if (r.pattern().matcher(m).find() && known.contains(r.agent()) && !agents.contains(r.agent())) {
                agents.add(r.agent());
            }
        }
        if (agents.isEmpty() && known.contains("policy-advisor-agent")) {
            agents.add("policy-advisor-agent");
        }
        return new RoutingPlan(agents.stream().limit(3).toList(), "keyword fallback", "NORMAL", 0.4,
                "router model unavailable; keyword rules used");
    }
}
