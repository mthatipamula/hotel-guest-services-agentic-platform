package com.aurora.guestops.orchestrator.pipeline;

import com.aurora.guestops.commons.agent.AgentCatalog;
import com.aurora.guestops.commons.agent.AgentDefinition;
import com.aurora.guestops.commons.agent.ChatClients;
import com.aurora.guestops.commons.tokens.TokenUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.vertexai.gemini.VertexAiGeminiChatOptions;
import org.springframework.stereotype.Component;

/**
 * Second guardrail layer: a cheap LLM classifier for requests the regex rules cannot judge
 * (social engineering, harassment, fraud, out-of-scope). Fails open with a flag, because the
 * deterministic guardrail has already run and tools enforce their own limits.
 */
@Component
public class SafetyGuard {

    private static final Logger log = LoggerFactory.getLogger(SafetyGuard.class);

    public record Verdict(boolean allowed, String category, String reason) {
    }

    public record Outcome(Verdict verdict, TokenUsage usage, String model, boolean degraded) {
    }

    private static final String PROMPT = """
            You are a safety classifier for an internal hotel operations assistant used by hotel staff.
            Decide if the staff request is safe to process.
            NOT allowed:
            - PRIVACY: asking for another guest's personal data, bulk guest data, payment card or ID numbers,
              or whether a named person is staying at the hotel (for a caller or third party).
            - FRAUD: issuing credits, refunds, upgrades or comps with no guest issue, or to oneself or friends.
            - ABUSE: harassment, discrimination or threats.
            - SECURITY: bypassing door locks, disabling cameras or alarms, accessing rooms without authorisation.
            - OUT_OF_SCOPE: clearly unrelated to hotel operations (coding, homework, general chat).
            Normal operational requests (guest complaints, room moves, billing questions, policies, bookings,
            partner services) are allowed even when the guest is upset.
            Short, terse or incomplete requests are allowed: staff often type just a confirmation number
            (e.g. AUR-10021), a room number, a guest name or a few words. Lack of detail is NOT a reason to
            block; the specialist agents will ask for clarification. Block only when the request is clearly
            harmful or clearly unrelated to the hotel.
            category is one of SAFE, PRIVACY, FRAUD, ABUSE, SECURITY, OUT_OF_SCOPE.""";

    private final ChatClients chatClients;
    private final AgentDefinition def;
    private final BeanOutputConverter<Verdict> converter = new BeanOutputConverter<>(Verdict.class);

    public SafetyGuard(ChatClients chatClients, AgentCatalog catalog) {
        this.chatClients = chatClients;
        this.def = catalog.get("safety-guard");
    }

    public String agentId() {
        return def.id();
    }

    public Outcome check(String message) {
        String model = chatClients.modelName(def.modelTier());
        try {
            ChatResponse response = chatClients.forTier(def.modelTier()).prompt()
                    .system(PROMPT + "\n\n" + converter.getFormat())
                    .user("Staff request:\n" + message)
                    .options(VertexAiGeminiChatOptions.builder().model(model).temperature(0.0)
                            .maxOutputTokens(def.maxOutputTokens()).responseMimeType("application/json").build())
                    .call().chatResponse();
            Verdict v = converter.convert(response.getResult().getOutput().getText());
            return new Outcome(v, TokenUsage.of(response.getMetadata().getUsage()), model, false);
        } catch (RuntimeException e) {
            log.warn("Safety classifier unavailable, continuing with deterministic guardrails only: {}", e.toString());
            return new Outcome(new Verdict(true, "UNKNOWN", "safety model unavailable"), TokenUsage.ZERO, model, true);
        }
    }
}
