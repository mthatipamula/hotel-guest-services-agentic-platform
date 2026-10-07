package com.aurora.guestops.orchestrator.memory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Short conversation memory. Only the last few turns are sent to agents, each truncated: memory is
 * one of the biggest sources of token growth in multi-turn agent systems.
 */
@Component
public class ConversationStore {

    private static final int MAX_CHARS_PER_TURN = 400;

    private final JdbcClient jdbc;

    public ConversationStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void append(String conversationId, String role, String content, String traceId) {
        jdbc.sql("INSERT INTO conversation_turns (conversation_id, role, content, trace_id) VALUES (:c, :r, :t, :tr)")
                .param("c", conversationId).param("r", role).param("t", content).param("tr", traceId).update();
    }

    public List<String> recent(String conversationId, int turns) {
        List<String> rows = new ArrayList<>(jdbc.sql("""
                SELECT role || ': ' || content FROM conversation_turns
                WHERE conversation_id = :c ORDER BY id DESC LIMIT :n""")
                .param("c", conversationId).param("n", turns).query(String.class).list());
        Collections.reverse(rows);
        return rows.stream().map(s -> s.length() > MAX_CHARS_PER_TURN ? s.substring(0, MAX_CHARS_PER_TURN) + "..." : s)
                .toList();
    }

    public void clear(String conversationId) {
        jdbc.sql("DELETE FROM conversation_turns WHERE conversation_id = :c").param("c", conversationId).update();
    }
}
