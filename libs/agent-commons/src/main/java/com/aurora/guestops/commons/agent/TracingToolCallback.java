package com.aurora.guestops.commons.agent;

import com.aurora.guestops.commons.agent.AgentModels.ToolCallRecord;
import java.util.List;
import java.util.function.Consumer;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/**
 * Wraps a tool so every call is recorded for the trace, and large outputs are truncated before they
 * go back to the model (token management: tool results are often the biggest part of the prompt).
 */
public class TracingToolCallback implements ToolCallback {

    private final ToolCallback delegate;
    private final List<ToolCallRecord> calls;
    private final Consumer<String> rawOutputSink;
    private final int maxOutputChars;

    public TracingToolCallback(ToolCallback delegate, List<ToolCallRecord> calls, Consumer<String> rawOutputSink,
                               int maxOutputChars) {
        this.delegate = delegate;
        this.calls = calls;
        this.rawOutputSink = rawOutputSink;
        this.maxOutputChars = maxOutputChars;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        return call(toolInput, null);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        long start = System.nanoTime();
        String name = getToolDefinition().name();
        try {
            String out = toolContext == null ? delegate.call(toolInput) : delegate.call(toolInput, toolContext);
            out = out == null ? "" : out;
            rawOutputSink.accept(name + "::" + out);
            String trimmed = out.length() > maxOutputChars
                    ? out.substring(0, maxOutputChars) + "...[truncated " + (out.length() - maxOutputChars) + " chars]"
                    : out;
            calls.add(new ToolCallRecord(name, toolInput, preview(out), elapsed(start), "ok"));
            return trimmed;
        } catch (RuntimeException e) {
            calls.add(new ToolCallRecord(name, toolInput, e.getMessage(), elapsed(start), "error"));
            // Return the error to the model so it can recover or explain, instead of failing the whole turn.
            return "{\"error\":\"" + String.valueOf(e.getMessage()).replace("\"", "'") + "\"}";
        }
    }

    private static long elapsed(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }

    private static String preview(String s) {
        return s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }
}
