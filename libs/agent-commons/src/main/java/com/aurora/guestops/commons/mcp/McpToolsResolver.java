package com.aurora.guestops.commons.mcp;

import com.aurora.guestops.commons.config.GuestOpsProperties;
import com.aurora.guestops.commons.registry.RegistryClient;
import com.aurora.guestops.commons.registry.RegistryModels.RegisteredMcpServer;
import com.aurora.guestops.commons.security.ServiceAuth;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.McpToolNamePrefixGenerator;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

/**
 * Connects to the hotel data MCP server found in the MCP registry and exposes its tools as Spring AI
 * ToolCallbacks. The connection is created lazily and rebuilt if the server moves or restarts, so
 * services do not hard-code where tools live.
 */
@Component
public class McpToolsResolver {

    private static final Logger log = LoggerFactory.getLogger(McpToolsResolver.class);

    private final RegistryClient registry;
    private final ServiceAuth auth;
    private final GuestOpsProperties props;

    private McpSyncClient client;
    private Map<String, ToolCallback> tools = Map.of();
    private String connectedUrl;

    public McpToolsResolver(RegistryClient registry, ServiceAuth auth, GuestOpsProperties props) {
        this.registry = registry;
        this.auth = auth;
        this.props = props;
    }

    /** Tools with the given names; unknown names are skipped. Retries once after reconnecting. */
    public List<ToolCallback> resolve(Collection<String> names) {
        if (names.isEmpty()) {
            return List.of();
        }
        try {
            return pick(names, connected());
        } catch (RuntimeException e) {
            log.warn("MCP tools unavailable, reconnecting: {}", e.toString());
            reset();
            try {
                return pick(names, connected());
            } catch (RuntimeException again) {
                log.error("MCP server unreachable; agents will run without hotel data tools", again);
                return List.of();
            }
        }
    }

    /** Direct tool call outside an LLM loop, used after a human approves an action. */
    public String call(String toolName, String jsonArguments) {
        ToolCallback tool = connected().get(toolName);
        if (tool == null) {
            throw new IllegalArgumentException("MCP tool not found: " + toolName);
        }
        return tool.call(jsonArguments);
    }

    public synchronized String connectedUrl() {
        return connectedUrl;
    }

    public synchronized List<String> toolNames() {
        return List.copyOf(connected().keySet());
    }

    private static List<ToolCallback> pick(Collection<String> names, Map<String, ToolCallback> available) {
        return names.stream().map(available::get).filter(Objects::nonNull).toList();
    }

    private synchronized Map<String, ToolCallback> connected() {
        if (client != null) {
            return tools;
        }
        String url = locate();
        String baseUrl = url.substring(0, url.length() - props.mcp().endpoint().length());
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder(baseUrl)
                .endpoint(props.mcp().endpoint())
                .connectTimeout(Duration.ofSeconds(5))
                // Per-request so short-lived Google ID tokens are refreshed when needed.
                .httpRequestCustomizer((builder, method, uri, body, ctx) ->
                        auth.authorizationHeader(uri.toString()).ifPresent(h -> builder.header("Authorization", h)))
                .build();
        McpSyncClient c = McpClient.sync(transport)
                .clientInfo(new McpSchema.Implementation(props.serviceName(), "1.0.0"))
                .requestTimeout(Duration.ofSeconds(30))
                .build();
        c.initialize();
        SyncMcpToolCallbackProvider provider = SyncMcpToolCallbackProvider.builder()
                .mcpClients(c)
                .toolNamePrefixGenerator(McpToolNamePrefixGenerator.noPrefix())
                .build();
        Map<String, ToolCallback> map = new LinkedHashMap<>();
        for (ToolCallback cb : provider.getToolCallbacks()) {
            map.put(cb.getToolDefinition().name(), cb);
        }
        this.client = c;
        this.tools = map;
        this.connectedUrl = url;
        log.info("Connected to MCP server {} with {} tools", url, map.size());
        return map;
    }

    /** MCP registry first; the configured fallback URL only if the registry cannot answer. */
    private String locate() {
        String endpoint = props.mcp().endpoint();
        return registry.mcpServer(props.mcp().serverId())
                .map(RegisteredMcpServer::url)
                .map(u -> trimSlash(u) + endpoint)
                .or(() -> props.mcp().fallbackUrl().isBlank() ? java.util.Optional.empty()
                        : java.util.Optional.of(trimSlash(props.mcp().fallbackUrl()) + endpoint))
                .orElseThrow(() -> new IllegalStateException(
                        "MCP server '" + props.mcp().serverId() + "' is not in the MCP registry and no fallback URL is set"));
    }

    private static String trimSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    public synchronized void reset() {
        if (client != null) {
            try {
                client.closeGracefully();
            } catch (RuntimeException ignored) {
                // Already gone.
            }
        }
        client = null;
        tools = Map.of();
        connectedUrl = null;
    }
}
