package com.aurora.guestops.commons.tokens;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Token budget for one user request across every agent it touches. Agents run in parallel, so the
 * counter is atomic. When the budget runs low, optional agents are skipped rather than failing the
 * whole request.
 */
public class TokenBudget {

    private final long limit;
    private final AtomicLong used = new AtomicLong();

    public TokenBudget(long limit) {
        this.limit = limit;
    }

    public void consume(TokenUsage usage) {
        if (usage != null) {
            used.addAndGet(usage.totalTokens());
        }
    }

    public long used() {
        return used.get();
    }

    public long remaining() {
        return Math.max(0, limit - used.get());
    }

    public long limit() {
        return limit;
    }

    /** Whether there is room for a call expected to cost about {@code estimate} tokens. */
    public boolean canAfford(long estimate) {
        return remaining() >= estimate;
    }
}
