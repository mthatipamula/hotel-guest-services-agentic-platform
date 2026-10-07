package com.aurora.guestops.orchestrator.tokens;

import com.aurora.guestops.commons.tokens.TokenUsage;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Records every LLM call's tokens and estimated cost, and answers "how much has this agent used
 * today?" for daily budget enforcement.
 */
@Component
@EnableConfigurationProperties(TokenLedger.Pricing.class)
public class TokenLedger {

    @ConfigurationProperties(prefix = "guestops")
    public record Pricing(Map<String, Price> pricing) {
    }

    public record Price(double input, double output) {
    }

    private final JdbcClient jdbc;
    private final Map<String, Price> prices;

    public TokenLedger(JdbcClient jdbc, Pricing pricing) {
        this.jdbc = jdbc;
        this.prices = pricing.pricing() == null ? Map.of() : pricing.pricing();
    }

    public BigDecimal estimateCost(String model, TokenUsage usage) {
        Price p = model == null ? null : prices.get(model);
        if (p == null || usage == null) {
            return BigDecimal.ZERO;
        }
        double usd = usage.promptTokens() * p.input() / 1_000_000d + usage.completionTokens() * p.output() / 1_000_000d;
        return BigDecimal.valueOf(usd).setScale(6, RoundingMode.HALF_UP);
    }

    public BigDecimal record(String traceId, String agentId, String model, TokenUsage usage) {
        if (usage == null || usage.totalTokens() == 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal cost = estimateCost(model, usage);
        jdbc.sql("""
                INSERT INTO token_usage (trace_id, agent_id, model, prompt_tokens, completion_tokens, total_tokens, est_cost_usd)
                VALUES (:t, :a, :m, :p, :c, :tot, :cost)""")
                .param("t", traceId).param("a", agentId).param("m", model)
                .param("p", usage.promptTokens()).param("c", usage.completionTokens())
                .param("tot", usage.totalTokens()).param("cost", cost).update();
        return cost;
    }

    public long usedToday(String agentId) {
        Long v = jdbc.sql("""
                SELECT COALESCE(SUM(total_tokens), 0) FROM token_usage
                WHERE agent_id = :a AND created_at >= date_trunc('day', now())""")
                .param("a", agentId).query(Long.class).single();
        return v == null ? 0 : v;
    }

    public Map<String, Object> summary() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("today", jdbc.sql("""
                SELECT COALESCE(SUM(total_tokens),0) AS tokens, COALESCE(SUM(est_cost_usd),0) AS cost_usd,
                       COUNT(DISTINCT trace_id) AS requests
                FROM token_usage WHERE created_at >= date_trunc('day', now())""").query().singleRow());
        out.put("byAgentToday", jdbc.sql("""
                SELECT agent_id, model, SUM(prompt_tokens) AS prompt_tokens, SUM(completion_tokens) AS completion_tokens,
                       SUM(total_tokens) AS total_tokens, SUM(est_cost_usd) AS cost_usd, COUNT(*) AS calls
                FROM token_usage WHERE created_at >= date_trunc('day', now())
                GROUP BY agent_id, model ORDER BY total_tokens DESC""").query().listOfRows());
        out.put("byModelToday", jdbc.sql("""
                SELECT model, SUM(total_tokens) AS total_tokens, SUM(est_cost_usd) AS cost_usd
                FROM token_usage WHERE created_at >= date_trunc('day', now())
                GROUP BY model ORDER BY total_tokens DESC""").query().listOfRows());
        out.put("last7Days", jdbc.sql("""
                SELECT to_char(date_trunc('day', created_at), 'YYYY-MM-DD') AS day, SUM(total_tokens) AS total_tokens,
                       SUM(est_cost_usd) AS cost_usd
                FROM token_usage WHERE created_at >= now() - interval '7 days'
                GROUP BY 1 ORDER BY 1""").query().listOfRows());
        return out;
    }

    public List<Map<String, Object>> forTrace(String traceId) {
        return jdbc.sql("SELECT agent_id, model, total_tokens, est_cost_usd FROM token_usage WHERE trace_id = :t")
                .param("t", traceId).query().listOfRows();
    }
}
