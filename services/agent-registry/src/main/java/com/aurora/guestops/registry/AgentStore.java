package com.aurora.guestops.registry;

import com.aurora.guestops.commons.a2a.A2a.AgentCard;
import com.aurora.guestops.commons.registry.RegistryModels.AgentRegistration;
import com.aurora.guestops.commons.registry.RegistryModels.AgentStatus;
import com.aurora.guestops.commons.registry.RegistryModels.Protocol;
import com.aurora.guestops.commons.registry.RegistryModels.RegisteredAgent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class AgentStore {

    private static final String COLUMNS = """
            agent_id, name, description, version, owner_team, host_service, network_zone, protocol,
            endpoint_url, card_url, skills, risk_level, status, last_heartbeat, registered_at, card::text AS card""";

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public AgentStore(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /**
     * Upsert on registration. A re-registering agent never clears a governance decision: SUSPENDED and
     * DEPRECATED stay as they are; only UNREACHABLE returns to ACTIVE.
     */
    @Transactional
    public RegisteredAgent upsert(AgentRegistration r, AgentCard card) {
        jdbc.sql("""
                INSERT INTO agents (agent_id, name, description, version, owner_team, host_service, network_zone,
                                    protocol, endpoint_url, card_url, skills, risk_level, status, last_heartbeat, card)
                VALUES (:id, :name, :description, :version, :owner, :host, :zone, :protocol, :endpoint, :cardUrl,
                        :skills, :risk, 'ACTIVE', now(), CAST(:card AS jsonb))
                ON CONFLICT (agent_id) DO UPDATE SET
                    name = EXCLUDED.name, description = EXCLUDED.description, version = EXCLUDED.version,
                    owner_team = EXCLUDED.owner_team, host_service = EXCLUDED.host_service,
                    network_zone = EXCLUDED.network_zone, protocol = EXCLUDED.protocol,
                    endpoint_url = EXCLUDED.endpoint_url, card_url = EXCLUDED.card_url, skills = EXCLUDED.skills,
                    risk_level = EXCLUDED.risk_level, card = EXCLUDED.card, last_heartbeat = now(),
                    status = CASE WHEN agents.status = 'UNREACHABLE' THEN 'ACTIVE' ELSE agents.status END
                """)
                .param("id", r.agentId())
                .param("name", r.name())
                .param("description", r.description())
                .param("version", r.version())
                .param("owner", r.ownerTeam())
                .param("host", r.hostService())
                .param("zone", r.networkZone())
                .param("protocol", r.protocol().name())
                .param("endpoint", r.endpointUrl())
                .param("cardUrl", r.cardUrl())
                .param("skills", r.skills() == null ? new String[0] : r.skills().toArray(String[]::new))
                .param("risk", r.riskLevel() == null ? "LOW" : r.riskLevel())
                .param("card", toJson(card))
                .update();
        return find(r.agentId()).orElseThrow();
    }

    public boolean heartbeat(String agentId) {
        return jdbc.sql("""
                UPDATE agents SET last_heartbeat = now(),
                    status = CASE WHEN status = 'UNREACHABLE' THEN 'ACTIVE' ELSE status END
                WHERE agent_id = :id""").param("id", agentId).update() == 1;
    }

    public List<RegisteredAgent> all() {
        return jdbc.sql("SELECT " + COLUMNS + " FROM agents ORDER BY network_zone, agent_id")
                .query(this::map).list();
    }

    public Optional<RegisteredAgent> find(String agentId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM agents WHERE agent_id = :id")
                .param("id", agentId).query(this::map).optional();
    }

    /** Discovery: ACTIVE agents whose skills (or id) match. Freshness is enforced by the sweeper. */
    public List<RegisteredAgent> discover(String skill) {
        return jdbc.sql("SELECT " + COLUMNS + """
                 FROM agents WHERE status = 'ACTIVE' AND (:skill = ANY(skills) OR agent_id = :skill)
                 ORDER BY last_heartbeat DESC""")
                .param("skill", skill).query(this::map).list();
    }

    @Transactional
    public Optional<RegisteredAgent> changeStatus(String agentId, AgentStatus to, String reason, String by) {
        Optional<RegisteredAgent> current = find(agentId);
        current.ifPresent(a -> {
            jdbc.sql("UPDATE agents SET status = :s WHERE agent_id = :id")
                    .param("s", to.name()).param("id", agentId).update();
            jdbc.sql("""
                    INSERT INTO agent_status_history (agent_id, from_status, to_status, reason, changed_by)
                    VALUES (:id, :from, :to, :reason, :by)""")
                    .param("id", agentId).param("from", a.status().name()).param("to", to.name())
                    .param("reason", reason).param("by", by).update();
        });
        return find(agentId);
    }

    public int markStale(int staleAfterSeconds) {
        return jdbc.sql("""
                UPDATE agents SET status = 'UNREACHABLE'
                WHERE status = 'ACTIVE' AND last_heartbeat < now() - make_interval(secs => :secs)""")
                .param("secs", staleAfterSeconds).update();
    }

    private RegisteredAgent map(ResultSet rs, int row) throws SQLException {
        return new RegisteredAgent(
                rs.getString("agent_id"), rs.getString("name"), rs.getString("description"),
                rs.getString("version"), rs.getString("owner_team"), rs.getString("host_service"),
                rs.getString("network_zone"), Protocol.valueOf(rs.getString("protocol")),
                rs.getString("endpoint_url"), rs.getString("card_url"), strings(rs.getArray("skills")),
                rs.getString("risk_level"), AgentStatus.valueOf(rs.getString("status")),
                instant(rs.getTimestamp("last_heartbeat")), instant(rs.getTimestamp("registered_at")),
                fromJson(rs.getString("card")));
    }

    static List<String> strings(Array array) throws SQLException {
        return array == null ? List.of() : List.of((String[]) array.getArray());
    }

    static java.time.Instant instant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    private String toJson(Object o) {
        if (o == null) {
            return null;
        }
        try {
            return mapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private AgentCard fromJson(String json) {
        if (json == null) {
            return null;
        }
        try {
            return mapper.readValue(json, AgentCard.class);
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
