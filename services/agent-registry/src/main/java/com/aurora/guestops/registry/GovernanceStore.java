package com.aurora.guestops.registry;

import com.aurora.guestops.commons.registry.RegistryModels.DataClassification;
import com.aurora.guestops.commons.registry.RegistryModels.GovernancePolicy;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class GovernanceStore {

    private final JdbcClient jdbc;

    public GovernanceStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<GovernancePolicy> find(String agentId) {
        return jdbc.sql("SELECT * FROM governance_policies WHERE agent_id = :id")
                .param("id", agentId).query(this::map).optional();
    }

    public List<GovernancePolicy> all() {
        return jdbc.sql("SELECT * FROM governance_policies ORDER BY agent_id").query(this::map).list();
    }

    public GovernancePolicy upsert(GovernancePolicy p, String updatedBy) {
        jdbc.sql("""
                INSERT INTO governance_policies (agent_id, allowed_tools, allowed_callers, max_output_tokens,
                    max_tokens_per_request, daily_token_budget, requires_human_approval, data_classification,
                    updated_at, updated_by)
                VALUES (:id, :tools, :callers, :maxOut, :maxReq, :daily, :approval, :cls, now(), :by)
                ON CONFLICT (agent_id) DO UPDATE SET allowed_tools = EXCLUDED.allowed_tools,
                    allowed_callers = EXCLUDED.allowed_callers, max_output_tokens = EXCLUDED.max_output_tokens,
                    max_tokens_per_request = EXCLUDED.max_tokens_per_request,
                    daily_token_budget = EXCLUDED.daily_token_budget,
                    requires_human_approval = EXCLUDED.requires_human_approval,
                    data_classification = EXCLUDED.data_classification, updated_at = now(), updated_by = :by""")
                .param("id", p.agentId())
                .param("tools", p.allowedTools().toArray(String[]::new))
                .param("callers", p.allowedCallers().toArray(String[]::new))
                .param("maxOut", p.maxOutputTokens())
                .param("maxReq", p.maxTokensPerRequest())
                .param("daily", p.dailyTokenBudget())
                .param("approval", p.requiresHumanApproval())
                .param("cls", p.dataClassification().name())
                .param("by", updatedBy)
                .update();
        return find(p.agentId()).orElseThrow();
    }

    public List<Map<String, Object>> statusHistory(String agentId) {
        return jdbc.sql("SELECT * FROM agent_status_history WHERE agent_id = :id ORDER BY changed_at DESC LIMIT 50")
                .param("id", agentId).query().listOfRows();
    }

    private GovernancePolicy map(ResultSet rs, int row) throws SQLException {
        return new GovernancePolicy(rs.getString("agent_id"), AgentStore.strings(rs.getArray("allowed_tools")),
                AgentStore.strings(rs.getArray("allowed_callers")), rs.getInt("max_output_tokens"),
                rs.getInt("max_tokens_per_request"), rs.getLong("daily_token_budget"),
                rs.getBoolean("requires_human_approval"),
                DataClassification.valueOf(rs.getString("data_classification")));
    }
}
