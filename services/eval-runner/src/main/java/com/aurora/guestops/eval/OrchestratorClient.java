package com.aurora.guestops.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.aurora.guestops.commons.security.ServiceAuth;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Black-box client: evaluations go through the same public API the console uses. */
@Component
public class OrchestratorClient {

    private final RestClient rest;

    public OrchestratorClient(EvalProperties props, ServiceAuth auth) {
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout(Duration.ofSeconds(5));
        rf.setReadTimeout(Duration.ofMinutes(4));
        String base = props.orchestratorUrl();
        // On Cloud Run the orchestrator only accepts callers with a Google ID token (roles/run.invoker).
        this.rest = RestClient.builder().baseUrl(base).requestFactory(rf)
                .requestInterceptor((request, body, execution) -> {
                    if (auth.mode() == com.aurora.guestops.commons.config.GuestOpsProperties.AuthMode.GOOGLE_ID_TOKEN) {
                        auth.authorizationHeader(base).ifPresent(h -> request.getHeaders().set(HttpHeaders.AUTHORIZATION, h));
                    }
                    return execution.execute(request, body);
                })
                .build();
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
        String key = System.getenv().getOrDefault("CONSOLE_ADMIN_KEY", "");
        return rest.post().uri("/api/evals").headers(h -> {
            if (!key.isBlank()) {
                h.set("X-Admin-Key", key);
            }
        }).body(report).retrieve().body(JsonNode.class);
    }
}
