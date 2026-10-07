package com.aurora.guestops.commons.a2a;

import com.aurora.guestops.commons.a2a.A2a.AgentCard;
import com.aurora.guestops.commons.a2a.A2a.Artifact;
import com.aurora.guestops.commons.a2a.A2a.ErrorCodes;
import com.aurora.guestops.commons.a2a.A2a.JsonRpcRequest;
import com.aurora.guestops.commons.a2a.A2a.JsonRpcResponse;
import com.aurora.guestops.commons.a2a.A2a.Message;
import com.aurora.guestops.commons.a2a.A2a.MessageSendParams;
import com.aurora.guestops.commons.a2a.A2a.Part;
import com.aurora.guestops.commons.a2a.A2a.Task;
import com.aurora.guestops.commons.a2a.A2a.TaskQueryParams;
import com.aurora.guestops.commons.a2a.A2a.TaskState;
import com.aurora.guestops.commons.a2a.A2a.TaskStatus;
import com.aurora.guestops.commons.agent.AgentCatalog;
import com.aurora.guestops.commons.agent.AgentDefinition;
import com.aurora.guestops.commons.agent.AgentModels.AgentRequest;
import com.aurora.guestops.commons.agent.AgentModels.AgentResult;
import com.aurora.guestops.commons.agent.ChatClients;
import com.aurora.guestops.commons.agent.LlmAgentExecutor;
import com.aurora.guestops.commons.config.GuestOpsProperties;
import com.aurora.guestops.commons.governance.GovernanceEnforcer;
import com.aurora.guestops.commons.guardrails.PiiRedactor;
import com.aurora.guestops.commons.mcp.ToolRegistry;
import com.aurora.guestops.commons.registry.RegistryClient;
import com.aurora.guestops.commons.registry.RegistryModels.DataClassification;
import com.aurora.guestops.commons.registry.RegistryModels.GovernancePolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exposes every agent in this service's catalog over A2A:
 * <ul>
 *   <li>{@code GET /a2a/{agentId}/.well-known/agent-card.json} - the Agent Card (discovery)</li>
 *   <li>{@code POST /a2a/{agentId}} - JSON-RPC 2.0: {@code message/send}, {@code tasks/get}</li>
 * </ul>
 * Governance is enforced here as well as at the caller (defence in depth): the remote agent checks
 * its own policy for the calling agent, applies its tool allowlist and token cap, and fails closed.
 */
@RestController
@ConditionalOnProperty(name = "guestops.a2a.server-enabled", havingValue = "true")
public class A2aServerController {

    private static final Logger log = LoggerFactory.getLogger(A2aServerController.class);

    private final AgentCatalog catalog;
    private final LlmAgentExecutor executor;
    private final ToolRegistry tools;
    private final RegistryClient registry;
    private final GovernanceEnforcer governance;
    private final PiiRedactor redactor;
    private final GuestOpsProperties props;
    private final ObjectMapper mapper;
    private final ChatClients chatClients;
    private final Cache<String, Task> tasks = Caffeine.newBuilder()
            .maximumSize(1_000).expireAfterWrite(Duration.ofHours(1)).build();

    public A2aServerController(AgentCatalog catalog, LlmAgentExecutor executor, ToolRegistry tools,
                               RegistryClient registry, GovernanceEnforcer governance, PiiRedactor redactor,
                               GuestOpsProperties props, ObjectMapper mapper, ChatClients chatClients) {
        this.catalog = catalog;
        this.executor = executor;
        this.tools = tools;
        this.registry = registry;
        this.governance = governance;
        this.redactor = redactor;
        this.props = props;
        this.mapper = mapper;
        this.chatClients = chatClients;
    }

    @GetMapping("/a2a")
    public List<AgentCard> cards() {
        return catalog.all().stream().map(d -> AgentCards.from(d, props.publicBaseUrl(), organization())).toList();
    }

    @GetMapping("/a2a/{agentId}" + A2a.WELL_KNOWN_CARD)
    public ResponseEntity<AgentCard> card(@PathVariable String agentId) {
        return catalog.find(agentId)
                .map(d -> ResponseEntity.ok(AgentCards.from(d, props.publicBaseUrl(), organization())))
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/a2a/{agentId}")
    public JsonRpcResponse rpc(@PathVariable String agentId, @RequestBody JsonRpcRequest request) {
        if (request == null || !"2.0".equals(request.jsonrpc()) || request.method() == null) {
            return JsonRpcResponse.error(request == null ? null : request.id(), ErrorCodes.INVALID_REQUEST,
                    "not a JSON-RPC 2.0 request");
        }
        AgentDefinition def = catalog.find(agentId).orElse(null);
        if (def == null) {
            return JsonRpcResponse.error(request.id(), ErrorCodes.INVALID_PARAMS, "unknown agent " + agentId);
        }
        try {
            return switch (request.method()) {
                case "message/send" -> JsonRpcResponse.ok(request.id(),
                        send(def, mapper.treeToValue(request.params(), MessageSendParams.class)));
                case "tasks/get" -> {
                    TaskQueryParams q = mapper.treeToValue(request.params(), TaskQueryParams.class);
                    Task t = q == null ? null : tasks.getIfPresent(q.id());
                    yield t == null
                            ? JsonRpcResponse.error(request.id(), ErrorCodes.TASK_NOT_FOUND, "task not found")
                            : JsonRpcResponse.ok(request.id(), t);
                }
                case "message/stream", "tasks/resubscribe", "tasks/pushNotificationConfig/set" ->
                        JsonRpcResponse.error(request.id(), ErrorCodes.UNSUPPORTED_OPERATION,
                                "streaming and push notifications are not supported by this agent");
                default -> JsonRpcResponse.error(request.id(), ErrorCodes.METHOD_NOT_FOUND,
                        "unknown method " + request.method());
            };
        } catch (Exception e) {
            log.error("A2A call to {} failed", agentId, e);
            return JsonRpcResponse.error(request.id(), ErrorCodes.INTERNAL_ERROR, e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private Task send(AgentDefinition def, MessageSendParams params) {
        Message in = params.message();
        String contextId = in.contextId() == null ? UUID.randomUUID().toString() : in.contextId();
        String taskId = UUID.randomUUID().toString();
        Map<String, Object> meta = params.metadata() == null ? Map.of() : params.metadata();
        String caller = String.valueOf(meta.getOrDefault("callerAgent", "unknown"));
        String traceId = String.valueOf(meta.getOrDefault("traceId", taskId));

        GovernancePolicy policy;
        try {
            policy = registry.policy(def.id());
        } catch (RuntimeException e) {
            return rejected(taskId, contextId, "governance policy unavailable; failing closed");
        }
        if (policy == null || !policy.callerAllowed(caller)) {
            registry.audit(traceId, caller, "a2a.invoke", def.id(), "DENIED", Map.of("reason", "caller not allowed"));
            return rejected(taskId, contextId, caller + " is not allowed to call " + def.id());
        }

        Map<String, Object> context = new LinkedHashMap<>(in.firstData());
        Object historyObj = context.remove("history");
        List<String> history = historyObj instanceof List<?> l ? (List<String>) l : List.of();
        String text = in.firstText();
        if (policy.dataClassification() == DataClassification.PARTNER_SAFE) {
            // The caller should already have stripped identity data; enforce it again on our side.
            context = redactor.partnerSafe(context);
            text = redactor.redact(text).text();
        }

        List<ToolCallback> allowed = governance.filterTools(policy, tools.resolve(def.tools()));
        int maxTokens = Math.min(def.maxOutputTokens() == null ? 2048 : def.maxOutputTokens(),
                policy.maxOutputTokens());
        AgentResult result = executor.execute(def,
                new AgentRequest(traceId, contextId, caller, text, context, history),
                allowed, maxTokens, props.networkZone());
        registry.audit(traceId, caller, "a2a.invoke", def.id(), result.ok() ? "COMPLETED" : "FAILED",
                Map.of("tools", result.toolCalls().size(), "tokens", result.usage().totalTokens()));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("agentId", def.id());
        data.put("toolCalls", result.toolCalls());
        data.put("sources", result.sources());
        data.put("contexts", result.contexts());
        data.put("usage", result.usage());
        data.put("model", chatClients.modelName(def.modelTier()));
        data.put("latencyMs", result.latencyMs());
        data.put("networkZone", props.networkZone());
        if (result.error() != null) {
            data.put("error", result.error());
        }
        String state = result.ok() ? TaskState.COMPLETED : TaskState.FAILED;
        Task task = new Task("task", taskId, contextId,
                TaskStatus.of(state, Message.agent(contextId, taskId,
                        List.of(Part.text(result.ok() ? "done" : "agent failed")))),
                List.of(new Artifact(UUID.randomUUID().toString(), def.id() + "-answer",
                        List.of(Part.text(result.text()), Part.data(data)))),
                List.of(in), Map.of("traceId", traceId));
        tasks.put(taskId, task);
        return task;
    }

    private Task rejected(String taskId, String contextId, String reason) {
        return new Task("task", taskId, contextId,
                TaskStatus.of(TaskState.REJECTED, Message.agent(contextId, taskId, List.of(Part.text(reason)))),
                List.of(), null, null);
    }

    private String organization() {
        return "Aurora Hotels & Resorts - " + props.serviceName();
    }
}
