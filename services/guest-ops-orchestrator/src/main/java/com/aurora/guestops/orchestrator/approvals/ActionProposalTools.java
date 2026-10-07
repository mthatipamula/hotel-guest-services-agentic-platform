package com.aurora.guestops.orchestrator.approvals;

import java.util.List;
import java.util.Map;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

/**
 * The only way an agent can ask for a high-impact action. One instance per agent run, so the
 * proposal is tied to the request's trace and the proposing agent.
 */
public class ActionProposalTools {

    private final ApprovalService approvals;
    private final String traceId;
    private final String conversationId;
    private final String agentId;

    public ActionProposalTools(ApprovalService approvals, String traceId, String conversationId, String agentId) {
        this.approvals = approvals;
        this.traceId = traceId;
        this.conversationId = conversationId;
        this.agentId = agentId;
    }

    public List<ToolCallback> callbacks() {
        return List.of(MethodToolCallbackProvider.builder().toolObjects(this).build().getToolCallbacks());
    }

    @Tool(description = """
            Propose a high-impact action for duty manager approval. Nothing is executed until a human approves.
            actionType: ROOM_MOVE (needs newRoomNumber), FOLIO_CREDIT (needs creditAmount in USD),
            LATE_CHECKOUT (needs checkoutTime as HH:mm). Always include a short justification.""")
    public Map<String, Object> proposeAction(
            @ToolParam(description = "ROOM_MOVE, FOLIO_CREDIT or LATE_CHECKOUT") String actionType,
            @ToolParam(description = "Confirmation number, e.g. AUR-10021") String confirmationNumber,
            @ToolParam(required = false, description = "For ROOM_MOVE: the new room number") String newRoomNumber,
            @ToolParam(required = false, description = "For FOLIO_CREDIT: amount in USD") Double creditAmount,
            @ToolParam(required = false, description = "For LATE_CHECKOUT: time as HH:mm") String checkoutTime,
            @ToolParam(description = "Why this action is appropriate, citing policy") String justification) {
        ApprovalService.ProposalResult r = approvals.propose(traceId, conversationId, agentId, actionType,
                confirmationNumber, newRoomNumber, creditAmount, checkoutTime, justification);
        return r.accepted()
                ? Map.of("status", "pending_approval", "approvalId", r.approvalId(), "note", r.message())
                : Map.of("status", "rejected", "reason", r.message());
    }
}
