package com.aurora.guestops.commons.registry;

import com.aurora.guestops.commons.config.GuestOpsProperties;
import com.aurora.guestops.commons.registry.RegistryModels.AgentRegistration;
import com.aurora.guestops.commons.registry.RegistryModels.AuditEvent;
import com.aurora.guestops.commons.registry.RegistryModels.GovernancePolicy;
import com.aurora.guestops.commons.registry.RegistryModels.McpServerRegistration;
import com.aurora.guestops.commons.registry.RegistryModels.RegisteredAgent;
import com.aurora.guestops.commons.registry.RegistryModels.RegisteredMcpServer;
import com.aurora.guestops.commons.security.ServiceAuth;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Client for the agent-registry service: registration, heartbeats, discovery, governance policies
 * and audit events. Policies and discovery results are cached briefly so the registry is not on the
 * hot path of every agent call, and so a short registry outage does not stop the platform.
 */
@Component
public class RegistryClient {

    private static final Logger log = LoggerFactory.getLogger(RegistryClient.class);

    private final RestClient rest;
    private final String baseUrl;
    private final ServiceAuth auth;
    private final ExecutorService executor;
    private final Cache<String, GovernancePolicy> policies;
    private final Cache<String, List<RegisteredAgent>> agentLists;

    public RegistryClient(GuestOpsProperties props, ServiceAuth auth, ExecutorService agentExecutor) {
        this.baseUrl = props.registry().url();
        this.auth = auth;
        this.executor = agentExecutor;
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout(Duration.ofSeconds(3));
        rf.setReadTimeout(Duration.ofSeconds(15));
        this.rest = RestClient.builder().baseUrl(baseUrl).requestFactory(rf).build();
        Duration ttl = Duration.ofSeconds(props.registry().policyCacheSeconds());
        this.policies = Caffeine.newBuilder().expireAfterWrite(ttl).maximumSize(500).build();
        this.agentLists = Caffeine.newBuilder().expireAfterWrite(Duration.ofSeconds(10)).maximumSize(50).build();
    }

    public String baseUrl() {
        return baseUrl;
    }

    public RegisteredAgent register(AgentRegistration registration) {
        return rest.post().uri("/api/registry/agents").headers(this::authHeaders).body(registration)
                .retrieve().body(RegisteredAgent.class);
    }

    /** Returns false if the registry no longer knows the agent (e.g. after a registry restart). */
    public boolean heartbeat(String agentId) {
        try {
            rest.put().uri("/api/registry/agents/{id}/heartbeat", agentId).headers(this::authHeaders)
                    .retrieve().toBodilessEntity();
            return true;
        } catch (org.springframework.web.client.HttpClientErrorException.NotFound e) {
            return false;
        }
    }

    public List<RegisteredAgent> listAgents() {
        return agentLists.get("all", k -> rest.get().uri("/api/registry/agents").retrieve()
                .body(new ParameterizedTypeReference<List<RegisteredAgent>>() {
                }));
    }

    public Optional<RegisteredAgent> getAgent(String agentId) {
        return listAgents().stream().filter(a -> a.agentId().equals(agentId)).findFirst();
    }

    /** A2A-style discovery by skill tag: the registry returns ACTIVE agents with a fresh heartbeat. */
    public List<RegisteredAgent> discover(String skill) {
        return agentLists.get("skill:" + skill, k -> rest.get()
                .uri(uri -> uri.path("/api/registry/discover").queryParam("skill", skill).build())
                .retrieve().body(new ParameterizedTypeReference<List<RegisteredAgent>>() {
                }));
    }

    public GovernancePolicy policy(String agentId) {
        return policies.get(agentId, id -> rest.get().uri("/api/governance/policies/{id}", id)
                .retrieve().body(GovernancePolicy.class));
    }

    public RegisteredMcpServer registerMcpServer(McpServerRegistration registration) {
        return rest.post().uri("/api/registry/mcp-servers").headers(this::authHeaders).body(registration)
                .retrieve().body(RegisteredMcpServer.class);
    }

    public boolean mcpHeartbeat(String serverId) {
        try {
            rest.put().uri("/api/registry/mcp-servers/{id}/heartbeat", serverId).headers(this::authHeaders)
                    .retrieve().toBodilessEntity();
            return true;
        } catch (org.springframework.web.client.HttpClientErrorException.NotFound e) {
            return false;
        }
    }

    public Optional<RegisteredMcpServer> mcpServer(String serverId) {
        try {
            return Optional.ofNullable(rest.get().uri("/api/registry/mcp-servers/{id}", serverId)
                    .retrieve().body(RegisteredMcpServer.class));
        } catch (RestClientException e) {
            log.warn("MCP registry lookup for {} failed: {}", serverId, e.toString());
            return Optional.empty();
        }
    }

    /** Fire-and-forget: auditing must never slow down or break a guest-facing request. */
    public void audit(String traceId, String actor, String action, String target, String decision,
                      Map<String, Object> details) {
        AuditEvent event = new AuditEvent(traceId, actor, action, target, decision, details, Instant.now());
        executor.submit(() -> {
            try {
                rest.post().uri("/api/audit").headers(this::authHeaders).body(List.of(event))
                        .retrieve().toBodilessEntity();
            } catch (RuntimeException e) {
                log.warn("Audit event dropped ({} {} {}): {}", actor, action, target, e.toString());
            }
        });
    }

    /** Pass-through for the operations UI. */
    public <T> T get(String path, ParameterizedTypeReference<T> type) {
        return rest.get().uri(path).retrieve().body(type);
    }

    public <T> T post(String path, Object body, Class<T> type) {
        return rest.post().uri(path).headers(this::authHeaders).body(body).retrieve().body(type);
    }

    public void invalidateCaches() {
        policies.invalidateAll();
        agentLists.invalidateAll();
    }

    private void authHeaders(HttpHeaders headers) {
        auth.authorizationHeader(baseUrl).ifPresent(h -> headers.set(HttpHeaders.AUTHORIZATION, h));
    }
}
