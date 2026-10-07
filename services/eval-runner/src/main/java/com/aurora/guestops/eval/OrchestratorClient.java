package com.aurora.guestops.eval;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Black-box client: evaluations go through the same public API the console uses. */
@Component
public class OrchestratorClient {

    private final RestClient rest;

    public OrchestratorClient(EvalProperties props) {
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout(Duration.ofSeconds(5));
        rf.setReadTimeout(Duration.ofMinutes(4));
        this.rest = RestClient.builder().baseUrl(props.orchestratorUrl()).requestFactory(rf).build();
    }

    public JsonNode chat(String conversationId, String message, String confirmationNumber) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("conversationId", conversationId);
        body.put("message", message);
        body.put("staffName", "eval-runner");
        if (confirmationNumber != null) {
            body.put("confirmationNumber", confirmationNumber);
        }
        return rest.post().uri("/api/chat").body(body).retrieve().body(JsonNode.class);
    }

    public JsonNode publish(Map<String, Object> report) {
        return rest.post().uri("/api/evals").body(report).retrieve().body(JsonNode.class);
    }
}
