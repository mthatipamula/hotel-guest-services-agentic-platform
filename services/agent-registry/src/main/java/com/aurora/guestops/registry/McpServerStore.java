package com.aurora.guestops.registry;

import com.aurora.guestops.commons.registry.RegistryModels.McpServerRegistration;
import com.aurora.guestops.commons.registry.RegistryModels.McpToolInfo;
import com.aurora.guestops.commons.registry.RegistryModels.RegisteredMcpServer;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The MCP registry: which tool servers exist, where they are, and which tools they expose. */
@Repository
public class McpServerStore {

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public McpServerStore(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public RegisteredMcpServer upsert(McpServerRegistration r) {
        try {
            jdbc.sql("""
                    INSERT INTO mcp_servers (server_id, name, version, url, endpoint, transport, network_zone, tools,
                                             status, last_heartbeat)
                    VALUES (:id, :name, :version, :url, :endpoint, :transport, :zone, CAST(:tools AS jsonb),
                            'ACTIVE', now())
                    ON CONFLICT (server_id) DO UPDATE SET name = EXCLUDED.name, version = EXCLUDED.version,
                        url = EXCLUDED.url, endpoint = EXCLUDED.endpoint, transport = EXCLUDED.transport,
                        network_zone = EXCLUDED.network_zone, tools = EXCLUDED.tools, status = 'ACTIVE',
                        last_heartbeat = now()""")
                    .param("id", r.serverId()).param("name", r.name()).param("version", r.version())
                    .param("url", r.url()).param("endpoint", r.endpoint() == null ? "/mcp" : r.endpoint())
                    .param("transport", r.transport() == null ? "streamable-http" : r.transport())
                    .param("zone", r.networkZone())
                    .param("tools", mapper.writeValueAsString(r.tools() == null ? List.of() : r.tools()))
                    .update();
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
        return find(r.serverId()).orElseThrow();
    }

    public boolean heartbeat(String serverId) {
        return jdbc.sql("UPDATE mcp_servers SET last_heartbeat = now(), status = 'ACTIVE' WHERE server_id = :id")
                .param("id", serverId).update() == 1;
    }

    public List<RegisteredMcpServer> all() {
        return jdbc.sql("SELECT *, tools::text AS tools_json FROM mcp_servers ORDER BY server_id")
                .query(this::map).list();
    }

    public Optional<RegisteredMcpServer> find(String serverId) {
        return jdbc.sql("SELECT *, tools::text AS tools_json FROM mcp_servers WHERE server_id = :id")
                .param("id", serverId).query(this::map).optional();
    }

    public int markStale(int staleAfterSeconds) {
        return jdbc.sql("""
                UPDATE mcp_servers SET status = 'UNREACHABLE'
                WHERE status = 'ACTIVE' AND last_heartbeat < now() - make_interval(secs => :secs)""")
                .param("secs", staleAfterSeconds).update();
    }

    private RegisteredMcpServer map(ResultSet rs, int row) throws SQLException {
        List<McpToolInfo> tools;
        try {
            tools = mapper.readValue(rs.getString("tools_json"), new TypeReference<>() {
            });
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            tools = List.of();
        }
        return new RegisteredMcpServer(rs.getString("server_id"), rs.getString("name"), rs.getString("version"),
                rs.getString("url"), rs.getString("endpoint"), rs.getString("transport"),
                rs.getString("network_zone"), tools, rs.getString("status"),
                AgentStore.instant(rs.getTimestamp("last_heartbeat")));
    }
}
