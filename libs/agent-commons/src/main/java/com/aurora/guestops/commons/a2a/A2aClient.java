package com.aurora.guestops.commons.a2a;

import com.aurora.guestops.commons.a2a.A2a.AgentCard;
import com.aurora.guestops.commons.a2a.A2a.JsonRpcResponse;
import com.aurora.guestops.commons.a2a.A2a.Message;
import com.aurora.guestops.commons.a2a.A2a.Task;
import com.aurora.guestops.commons.security.ServiceAuth;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Calls remote agents over A2A: fetch Agent Cards and send messages (JSON-RPC over HTTPS). */
@Component
public class A2aClient {

    private final RestClient rest;
    private final ServiceAuth auth;
    private final ObjectMapper mapper;

    public A2aClient(ServiceAuth auth, ObjectMapper mapper) {
        this.auth = auth;
        this.mapper = mapper;
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout(Duration.ofSeconds(5));
        rf.setReadTimeout(Duration.ofSeconds(90));
        this.rest = RestClient.builder().requestFactory(rf).build();
    }

    public AgentCard fetchCard(String cardUrl) {
        return rest.get().uri(cardUrl).headers(h -> authorize(h, cardUrl)).retrieve().body(AgentCard.class);
    }

    public Task sendMessage(String agentUrl, Message message, Map<String, Object> metadata) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("message", message);
        if (metadata != null) {
            params.put("metadata", metadata);
        }
        Map<String, Object> request = Map.of(
                "jsonrpc", "2.0",
                "id", UUID.randomUUID().toString(),
                "method", "message/send",
                "params", params);
        JsonRpcResponse response = rest.post().uri(agentUrl)
                .contentType(MediaType.APPLICATION_JSON)
                .headers(h -> authorize(h, agentUrl))
                .body(request)
                .retrieve()
                .body(JsonRpcResponse.class);
        if (response == null) {
            throw new A2aException(A2a.ErrorCodes.INTERNAL_ERROR, "empty response from " + agentUrl);
        }
        if (response.error() != null) {
            throw new A2aException(response.error().code(), response.error().message());
        }
        return mapper.convertValue(response.result(), Task.class);
    }

    private void authorize(HttpHeaders headers, String url) {
        auth.authorizationHeader(url).ifPresent(v -> headers.set(HttpHeaders.AUTHORIZATION, v));
    }

    public static class A2aException extends RuntimeException {
        private final int code;

        public A2aException(int code, String message) {
            super("A2A error " + code + ": " + message);
            this.code = code;
        }

        public int code() {
            return code;
        }
    }
}
