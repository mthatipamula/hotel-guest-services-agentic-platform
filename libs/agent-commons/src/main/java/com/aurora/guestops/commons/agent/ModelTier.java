package com.aurora.guestops.commons.agent;

/** Model tiers are a token-management lever: cheap models where quality allows it. */
public enum ModelTier {
    /** Routing, safety classification, short structured outputs. */
    FAST,
    /** Specialist reasoning with tools. */
    STANDARD,
    /** LLM-as-a-judge: strongest model, temperature 0. */
    JUDGE
}
