package com.aurora.guestops.orchestrator.approvals;

import java.time.Instant;
import java.util.Map;

public record PendingAction(String id, String traceId, String conversationId, String proposedByAgent,
                            ActionType actionType, String confirmationNumber, Map<String, Object> arguments,
                            String justification, String status, String decidedBy, Instant decidedAt,
                            String decisionNote, Map<String, Object> result, Instant createdAt) {
}
