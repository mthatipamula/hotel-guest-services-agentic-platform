package com.aurora.guestops.commons.tokens;

import org.springframework.ai.chat.metadata.Usage;

public record TokenUsage(long promptTokens, long completionTokens, long totalTokens) {

    public static final TokenUsage ZERO = new TokenUsage(0, 0, 0);

    public static TokenUsage of(Usage usage) {
        if (usage == null) {
            return ZERO;
        }
        long p = usage.getPromptTokens() == null ? 0 : usage.getPromptTokens();
        long c = usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens();
        long t = usage.getTotalTokens() == null ? p + c : usage.getTotalTokens();
        return new TokenUsage(p, c, Math.max(t, p + c));
    }

    public TokenUsage plus(TokenUsage other) {
        return other == null ? this : new TokenUsage(promptTokens + other.promptTokens,
                completionTokens + other.completionTokens, totalTokens + other.totalTokens);
    }
}
