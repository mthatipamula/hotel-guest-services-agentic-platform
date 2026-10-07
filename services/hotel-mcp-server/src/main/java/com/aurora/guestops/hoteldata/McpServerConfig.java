package com.aurora.guestops.hoteldata;

import com.aurora.guestops.commons.config.GuestOpsProperties;
import com.aurora.guestops.commons.registry.RegistryClient;
import com.aurora.guestops.commons.registry.RegistryModels.McpServerRegistration;
import com.aurora.guestops.commons.registry.RegistryModels.McpToolInfo;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;

/** Exposes the tools over MCP (streamable HTTP at /mcp) and registers the server in the MCP registry. */
@Configuration
public class McpServerConfig {

    private static final Logger log = LoggerFactory.getLogger(McpServerConfig.class);
    static final String SERVER_ID = "hotel-mcp-server";

    private final RegistryClient registry;
    private final GuestOpsProperties props;
    private final AtomicBoolean registered = new AtomicBoolean();
    private ToolCallbackProvider provider;

    public McpServerConfig(RegistryClient registry, GuestOpsProperties props) {
        this.registry = registry;
        this.props = props;
    }

    @Bean
    ToolCallbackProvider hotelTools(HotelOperationsTools operations, PolicyKnowledge policies) {
        this.provider = MethodToolCallbackProvider.builder().toolObjects(operations, policies).build();
        return provider;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        heartbeat();
    }

    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    public void heartbeat() {
        try {
            if (!registered.get() || !registry.mcpHeartbeat(SERVER_ID)) {
                List<McpToolInfo> tools = Arrays.stream(provider.getToolCallbacks())
                        .map(t -> new McpToolInfo(t.getToolDefinition().name(), t.getToolDefinition().description()))
                        .toList();
                registry.registerMcpServer(new McpServerRegistration(SERVER_ID, "Hotel Data MCP Server", "1.0.0",
                        props.publicBaseUrl(), "/mcp", "streamable-http", props.networkZone(), tools));
                registered.set(true);
                log.info("Registered MCP server {} with {} tools", SERVER_ID, tools.size());
            }
        } catch (RuntimeException e) {
            registered.set(false);
            log.warn("MCP registry not reachable: {}", e.getMessage());
        }
    }
}
