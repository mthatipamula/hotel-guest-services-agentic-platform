package com.aurora.guestops.commons.agent;

import com.aurora.guestops.commons.config.GuestOpsProperties;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.vertexai.gemini.VertexAiGeminiChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * One ChatClient per model tier, all backed by Vertex AI Gemini. Built lazily so services without an
 * LLM (the registry sets {@code spring.ai.model.chat=none}) can still load the shared library.
 */
@Component
public class ChatClients {

    private final ObjectProvider<ChatModel> chatModel;
    private final Map<ModelTier, String> modelNames = new EnumMap<>(ModelTier.class);
    private final Map<ModelTier, ChatClient> clients = new ConcurrentHashMap<>();

    public ChatClients(ObjectProvider<ChatModel> chatModel, GuestOpsProperties props) {
        this.chatModel = chatModel;
        modelNames.put(ModelTier.FAST, props.models().fast());
        modelNames.put(ModelTier.STANDARD, props.models().standard());
        modelNames.put(ModelTier.JUDGE, props.models().judge());
    }

    public ChatClient forTier(ModelTier tier) {
        ModelTier t = tier == null ? ModelTier.STANDARD : tier;
        return clients.computeIfAbsent(t, key -> ChatClient.builder(chatModel.getObject())
                .defaultOptions(VertexAiGeminiChatOptions.builder()
                        .model(modelNames.get(key))
                        .temperature(key == ModelTier.STANDARD ? 0.2 : 0.0)
                        .build())
                .build());
    }

    /** A builder for components that need their own client, e.g. Spring AI evaluators. */
    public ChatClient.Builder builderForTier(ModelTier tier) {
        ModelTier t = tier == null ? ModelTier.STANDARD : tier;
        return ChatClient.builder(chatModel.getObject())
                .defaultOptions(VertexAiGeminiChatOptions.builder()
                        .model(modelNames.get(t))
                        .temperature(0.0)
                        .build());
    }

    public String modelName(ModelTier tier) {
        return modelNames.get(tier == null ? ModelTier.STANDARD : tier);
    }
}
