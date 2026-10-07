package com.aurora.guestops.eval;

import com.aurora.guestops.commons.agent.AgentCatalog;
import com.aurora.guestops.commons.agent.AgentDefinition;
import com.aurora.guestops.commons.agent.ChatClients;
import com.aurora.guestops.commons.tokens.TokenUsage;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.vertexai.gemini.VertexAiGeminiChatOptions;
import org.springframework.stereotype.Component;

/**
 * The quality-judge-agent's model calls: strongest tier, temperature 0, JSON output only.
 * Token usage is counted so the cost of evaluation itself is visible in the report.
 */
@Component
public class JudgeLlm {

    private final ChatClients chatClients;
    private final AgentDefinition def;
    private final AtomicLong tokens = new AtomicLong();
    private final AtomicLong calls = new AtomicLong();

    public JudgeLlm(ChatClients chatClients, AgentCatalog catalog) {
        this.chatClients = chatClients;
        this.def = catalog.get("quality-judge-agent");
    }

    public <T> T ask(String system, String user, Class<T> type) {
        BeanOutputConverter<T> converter = new BeanOutputConverter<>(type);
        ChatResponse response = chatClients.forTier(def.modelTier()).prompt()
                .system(system + "\n\n" + converter.getFormat())
                .user(user)
                .options(VertexAiGeminiChatOptions.builder()
                        .model(model())
                        .temperature(0.0)
                        .maxOutputTokens(def.maxOutputTokens())
                        .responseMimeType("application/json")
                        .build())
                .call()
                .chatResponse();
        tokens.addAndGet(TokenUsage.of(response.getMetadata().getUsage()).totalTokens());
        calls.incrementAndGet();
        return converter.convert(response.getResult().getOutput().getText());
    }

    public String model() {
        return chatClients.modelName(def.modelTier());
    }

    public long tokensUsed() {
        return tokens.get();
    }

    public long calls() {
        return calls.get();
    }
}
