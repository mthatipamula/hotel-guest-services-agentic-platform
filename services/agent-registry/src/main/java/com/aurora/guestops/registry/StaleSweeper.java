package com.aurora.guestops.registry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Marks agents and MCP servers UNREACHABLE when heartbeats stop, so discovery skips them. */
@Component
public class StaleSweeper {

    private static final Logger log = LoggerFactory.getLogger(StaleSweeper.class);

    private final AgentStore agents;
    private final McpServerStore mcpServers;
    private final int staleAfterSeconds;

    public StaleSweeper(AgentStore agents, McpServerStore mcpServers,
                        @Value("${guestops.registry.stale-after-seconds:90}") int staleAfterSeconds) {
        this.agents = agents;
        this.mcpServers = mcpServers;
        this.staleAfterSeconds = staleAfterSeconds;
    }

    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    public void sweep() {
        int a = agents.markStale(staleAfterSeconds);
        int m = mcpServers.markStale(staleAfterSeconds);
        if (a + m > 0) {
            log.warn("Marked {} agents and {} MCP servers UNREACHABLE (no heartbeat for {}s)", a, m, staleAfterSeconds);
        }
    }
}
