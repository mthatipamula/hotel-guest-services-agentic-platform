package com.aurora.guestops.commons.mcp;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Resolves tool names from an agent's catalog entry to callbacks: tools implemented in this service
 * first, then tools served over MCP by the hotel data server.
 */
@Component
public class ToolRegistry {

    private final ObjectProvider<ToolCallbackProvider> localProviders;
    private final McpToolsResolver mcp;

    public ToolRegistry(ObjectProvider<ToolCallbackProvider> localProviders, McpToolsResolver mcp) {
        this.localProviders = localProviders;
        this.mcp = mcp;
    }

    public List<ToolCallback> resolve(Collection<String> names) {
        Map<String, ToolCallback> local = new LinkedHashMap<>();
        localProviders.orderedStream().forEach(p -> {
            for (ToolCallback cb : p.getToolCallbacks()) {
                local.put(cb.getToolDefinition().name(), cb);
            }
        });
        List<ToolCallback> out = new ArrayList<>();
        List<String> remote = new ArrayList<>();
        for (String name : names) {
            ToolCallback cb = local.get(name);
            if (cb != null) {
                out.add(cb);
            } else {
                remote.add(name);
            }
        }
        out.addAll(mcp.resolve(remote));
        return out;
    }
}
