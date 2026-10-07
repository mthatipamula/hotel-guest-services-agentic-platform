package com.aurora.guestops.registry;

import com.aurora.guestops.commons.registry.RegistryModels.AuditEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AuditStore {

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public AuditStore(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public void append(List<AuditEvent> events) {
        for (AuditEvent e : events) {
            String details;
            try {
                details = e.details() == null ? null : mapper.writeValueAsString(e.details());
            } catch (Exception ex) {
                details = null;
            }
            jdbc.sql("""
                    INSERT INTO audit_events (trace_id, actor, action, target, decision, details, created_at)
                    VALUES (:trace, :actor, :action, :target, :decision, CAST(:details AS jsonb), :at)""")
                    .param("trace", e.traceId()).param("actor", e.actor()).param("action", e.action())
                    .param("target", e.target()).param("decision", e.decision()).param("details", details)
                    .param("at", Timestamp.from(e.timestamp() == null ? Instant.now() : e.timestamp()))
                    .update();
        }
    }

    public List<Map<String, Object>> recent(String traceId, int limit) {
        String sql = """
                SELECT id, trace_id, actor, action, target, decision, details::text AS details, created_at
                FROM audit_events""" + (traceId == null ? "" : " WHERE trace_id = :trace")
                + " ORDER BY id DESC LIMIT :limit";
        var spec = jdbc.sql(sql).param("limit", Math.min(limit, 500));
        if (traceId != null) {
            spec = spec.param("trace", traceId);
        }
        return spec.query().listOfRows();
    }
}
