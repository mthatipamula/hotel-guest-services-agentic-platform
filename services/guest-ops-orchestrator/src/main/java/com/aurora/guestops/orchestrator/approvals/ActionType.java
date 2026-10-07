package com.aurora.guestops.orchestrator.approvals;

/** High-impact actions that always need a human decision, and the MCP tool that executes each. */
public enum ActionType {
    ROOM_MOVE("moveGuestToRoom"),
    FOLIO_CREDIT("applyFolioCredit"),
    LATE_CHECKOUT("grantLateCheckout");

    private final String mcpTool;

    ActionType(String mcpTool) {
        this.mcpTool = mcpTool;
    }

    public String mcpTool() {
        return mcpTool;
    }
}
