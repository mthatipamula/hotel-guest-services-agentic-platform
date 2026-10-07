package com.aurora.guestops.registry;

import com.aurora.guestops.commons.a2a.A2a.AgentCard;
import com.aurora.guestops.commons.a2a.A2aClient;
import com.aurora.guestops.commons.registry.RegistryModels.AgentRegistration;
import com.aurora.guestops.commons.registry.RegistryModels.AuditEvent;
import com.aurora.guestops.commons.registry.RegistryModels.GovernancePolicy;
import com.aurora.guestops.commons.registry.RegistryModels.McpServerRegistration;
import com.aurora.guestops.commons.registry.RegistryModels.Protocol;
import com.aurora.guestops.commons.registry.RegistryModels.RegisteredAgent;
import com.aurora.guestops.commons.registry.RegistryModels.RegisteredMcpServer;
import com.aurora.guestops.commons.registry.RegistryModels.StatusChange;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Agent registry, MCP registry, governance policies and audit trail.
 *
 * <p>For A2A agents, registration is verified by fetching the Agent Card from the agent's
 * well-known URL, the same way any A2A client would discover it. An agent whose card cannot be
 * fetched, or whose card points elsewhere, is not registered.
 */
@RestController
public class RegistryController {

    private static final Logger log = LoggerFactory.getLogger(RegistryController.class);

    private final AgentStore agents;
    private final McpServerStore mcpServers;
    private final GovernanceStore governance;
    private final AuditStore audit;
    private final A2aClient a2a;

    public RegistryController(AgentStore agents, McpServerStore mcpServers, GovernanceStore governance,
                              AuditStore audit, A2aClient a2a) {
        this.agents = agents;
        this.mcpServers = mcpServers;
        this.governance = governance;
        this.audit = audit;
        this.a2a = a2a;
    }

    // ---------- Agent registry ----------

    @PostMapping("/api/registry/agents")
    public RegisteredAgent register(@RequestBody AgentRegistration r) {
        if (r.agentId() == null || r.protocol() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "agentId and protocol are required");
        }
        AgentCard card = r.card();
        if (r.protocol() == Protocol.A2A) {
            if (r.cardUrl() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A2A agents must provide cardUrl");
            }
            try {
                card = a2a.fetchCard(r.cardUrl());
            } catch (RuntimeException e) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "could not fetch Agent Card from " + r.cardUrl() + ": " + e.getMessage());
            }
            if (card == null || card.url() == null || !card.url().equals(r.endpointUrl())) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Agent Card url does not match the registered endpoint");
            }
        }
        boolean hasPolicy = governance.find(r.agentId()).isPresent();
        RegisteredAgent saved = agents.upsert(r, card);
        audit.append(List.of(new AuditEvent(null, r.hostService(), "registry.register", r.agentId(),
                hasPolicy ? "REGISTERED" : "REGISTERED_WITHOUT_POLICY",
                Map.of("protocol", r.protocol().name(), "zone", String.valueOf(r.networkZone())), Instant.now())));
        if (!hasPolicy) {
            log.warn("Agent {} registered without a governance policy; calls to it will be denied", r.agentId());
        }
        return saved;
    }

    @PutMapping("/api/registry/agents/{id}/heartbeat")
    public ResponseEntity<Void> heartbeat(@PathVariable String id) {
        return agents.heartbeat(id) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    @GetMapping("/api/registry/agents")
    public List<RegisteredAgent> list() {
        return agents.all();
    }

    @GetMapping("/api/registry/agents/{id}")
    public RegisteredAgent get(@PathVariable String id) {
        return agents.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @GetMapping("/api/registry/discover")
    public List<RegisteredAgent> discover(@RequestParam String skill) {
        return agents.discover(skill);
    }

    /** Governance kill switch: SUSPENDED agents are skipped by every caller within the cache TTL. */
    @PostMapping("/api/registry/agents/{id}/status")
    public RegisteredAgent changeStatus(@PathVariable String id, @RequestBody StatusChange change) {
        RegisteredAgent updated = agents.changeStatus(id, change.status(), change.reason(), change.changedBy())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        audit.append(List.of(new AuditEvent(null, String.valueOf(change.changedBy()), "governance.status",
                id, change.status().name(), Map.of("reason", String.valueOf(change.reason())), Instant.now())));
        return updated;
    }

    @GetMapping("/api/registry/summary")
    public Map<String, Object> summary() {
        List<RegisteredAgent> all = agents.all();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("totalAgents", all.size());
        out.put("byZone", new TreeMap<>(all.stream().collect(Collectors.groupingBy(
                a -> String.valueOf(a.networkZone()), Collectors.counting()))));
        out.put("byProtocol", new TreeMap<>(all.stream().collect(Collectors.groupingBy(
                a -> a.protocol().name(), Collectors.counting()))));
        out.put("byStatus", new TreeMap<>(all.stream().collect(Collectors.groupingBy(
                a -> a.status().name(), Collectors.counting()))));
        out.put("mcpServers", mcpServers.all().size());
        return out;
    }

    // ---------- MCP registry ----------

    @PostMapping("/api/registry/mcp-servers")
    public RegisteredMcpServer registerMcp(@RequestBody McpServerRegistration r) {
        return mcpServers.upsert(r);
    }

    @PutMapping("/api/registry/mcp-servers/{id}/heartbeat")
    public ResponseEntity<Void> mcpHeartbeat(@PathVariable String id) {
        return mcpServers.heartbeat(id) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    @GetMapping("/api/registry/mcp-servers")
    public List<RegisteredMcpServer> listMcp() {
        return mcpServers.all();
    }

    @GetMapping("/api/registry/mcp-servers/{id}")
    public RegisteredMcpServer getMcp(@PathVariable String id) {
        return mcpServers.find(id).filter(s -> "ACTIVE".equals(s.status()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    // ---------- Governance ----------

    @GetMapping("/api/governance/policies")
    public List<GovernancePolicy> policies() {
        return governance.all();
    }

    @GetMapping("/api/governance/policies/{id}")
    public GovernancePolicy policy(@PathVariable String id) {
        return governance.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                "no governance policy for " + id));
    }

    @PutMapping("/api/governance/policies/{id}")
    public GovernancePolicy updatePolicy(@PathVariable String id, @RequestBody GovernancePolicy p,
                                         @RequestParam(defaultValue = "api") String updatedBy) {
        if (!id.equals(p.agentId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "agentId mismatch");
        }
        GovernancePolicy saved = governance.upsert(p, updatedBy);
        audit.append(List.of(new AuditEvent(null, updatedBy, "governance.policy.update", id, "UPDATED",
                Map.of("allowedTools", p.allowedTools()), Instant.now())));
        return saved;
    }

    @GetMapping("/api/governance/agents/{id}/history")
    public List<Map<String, Object>> history(@PathVariable String id) {
        return governance.statusHistory(id);
    }

    // ---------- Audit ----------

    @PostMapping("/api/audit")
    public ResponseEntity<Void> appendAudit(@RequestBody List<AuditEvent> events) {
        audit.append(events);
        return ResponseEntity.accepted().build();
    }

    @GetMapping("/api/audit")
    public List<Map<String, Object>> recentAudit(@RequestParam(required = false) String traceId,
                                                 @RequestParam(defaultValue = "100") int limit) {
        return audit.recent(traceId, limit);
    }
}
