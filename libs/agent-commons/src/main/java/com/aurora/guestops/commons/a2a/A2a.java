package com.aurora.guestops.commons.a2a;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Data model for the subset of the A2A (Agent2Agent) protocol, v0.3, that this platform uses:
 * Agent Cards for discovery and the JSON-RPC {@code message/send} and {@code tasks/get} methods.
 *
 * <p>Field names follow the A2A specification so any A2A-compliant client or server can talk to
 * these agents. Streaming and push notifications are not implemented; the cards advertise that.
 */
public final class A2a {

    public static final String PROTOCOL_VERSION = "0.3.0";
    public static final String WELL_KNOWN_CARD = "/.well-known/agent-card.json";

    private A2a() {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AgentCard(
            String protocolVersion,
            String name,
            String description,
            String url,
            String preferredTransport,
            String version,
            AgentProvider provider,
            AgentCapabilities capabilities,
            List<String> defaultInputModes,
            List<String> defaultOutputModes,
            List<AgentSkill> skills,
            Map<String, Object> securitySchemes,
            List<Map<String, List<String>>> security) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AgentProvider(String organization, String url) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AgentCapabilities(boolean streaming, boolean pushNotifications, boolean stateTransitionHistory) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AgentSkill(String id, String name, String description, List<String> tags, List<String> examples) {
    }

    /** A message part. {@code kind} is "text" (uses {@code text}) or "data" (uses {@code data}). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Part(String kind, String text, Map<String, Object> data) {

        public static Part text(String text) {
            return new Part("text", text, null);
        }

        public static Part data(Map<String, Object> data) {
            return new Part("data", null, data);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Message(String kind, String role, List<Part> parts, String messageId, String contextId,
                          String taskId, Map<String, Object> metadata) {

        public static Message user(String contextId, List<Part> parts) {
            return new Message("message", "user", parts, UUID.randomUUID().toString(), contextId, null, null);
        }

        public static Message agent(String contextId, String taskId, List<Part> parts) {
            return new Message("message", "agent", parts, UUID.randomUUID().toString(), contextId, taskId, null);
        }

        public String firstText() {
            return parts == null ? "" : parts.stream()
                    .filter(p -> "text".equals(p.kind()) && p.text() != null)
                    .map(Part::text).findFirst().orElse("");
        }

        public Map<String, Object> firstData() {
            return parts == null ? Map.of() : parts.stream()
                    .filter(p -> "data".equals(p.kind()) && p.data() != null)
                    .map(Part::data).findFirst().orElse(Map.of());
        }
    }

    /** Task states from the spec that this implementation can emit. */
    public static final class TaskState {
        public static final String SUBMITTED = "submitted";
        public static final String WORKING = "working";
        public static final String COMPLETED = "completed";
        public static final String FAILED = "failed";
        public static final String REJECTED = "rejected";

        private TaskState() {
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TaskStatus(String state, Message message, String timestamp) {

        public static TaskStatus of(String state, Message message) {
            return new TaskStatus(state, message, Instant.now().toString());
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Artifact(String artifactId, String name, List<Part> parts) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Task(String kind, String id, String contextId, TaskStatus status, List<Artifact> artifacts,
                       List<Message> history, Map<String, Object> metadata) {

        public String resultText() {
            if (artifacts != null) {
                for (Artifact a : artifacts) {
                    for (Part p : a.parts()) {
                        if ("text".equals(p.kind()) && p.text() != null) {
                            return p.text();
                        }
                    }
                }
            }
            return status != null && status.message() != null ? status.message().firstText() : "";
        }

        public Map<String, Object> resultData() {
            if (artifacts != null) {
                for (Artifact a : artifacts) {
                    for (Part p : a.parts()) {
                        if ("data".equals(p.kind()) && p.data() != null) {
                            return p.data();
                        }
                    }
                }
            }
            return Map.of();
        }
    }

    public record MessageSendParams(Message message, Map<String, Object> metadata) {
    }

    public record TaskQueryParams(String id) {
    }

    public record JsonRpcRequest(String jsonrpc, Object id, String method, JsonNode params) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record JsonRpcResponse(String jsonrpc, Object id, Object result, JsonRpcError error) {

        public static JsonRpcResponse ok(Object id, Object result) {
            return new JsonRpcResponse("2.0", id, result, null);
        }

        public static JsonRpcResponse error(Object id, int code, String message) {
            return new JsonRpcResponse("2.0", id, null, new JsonRpcError(code, message, null));
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record JsonRpcError(int code, String message, Object data) {
    }

    /** Standard JSON-RPC and A2A error codes. */
    public static final class ErrorCodes {
        public static final int PARSE_ERROR = -32700;
        public static final int INVALID_REQUEST = -32600;
        public static final int METHOD_NOT_FOUND = -32601;
        public static final int INVALID_PARAMS = -32602;
        public static final int INTERNAL_ERROR = -32603;
        public static final int TASK_NOT_FOUND = -32001;
        public static final int UNSUPPORTED_OPERATION = -32004;

        private ErrorCodes() {
        }
    }
}
