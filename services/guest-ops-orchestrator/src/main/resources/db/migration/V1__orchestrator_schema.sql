CREATE TABLE conversation_turns (
    id              BIGSERIAL PRIMARY KEY,
    conversation_id VARCHAR(100) NOT NULL,
    role            VARCHAR(20)  NOT NULL,      -- staff | assistant
    content         TEXT         NOT NULL,
    trace_id        VARCHAR(100),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_turns_conversation ON conversation_turns (conversation_id, id DESC);

-- Human-in-the-loop: high-impact actions proposed by agents wait here for a manager.
CREATE TABLE pending_actions (
    id                  VARCHAR(40)  PRIMARY KEY,
    trace_id            VARCHAR(100) NOT NULL,
    conversation_id     VARCHAR(100),
    proposed_by_agent   VARCHAR(100) NOT NULL,
    action_type         VARCHAR(30)  NOT NULL,   -- ROOM_MOVE | FOLIO_CREDIT | LATE_CHECKOUT
    confirmation_number VARCHAR(20)  NOT NULL,
    arguments           JSONB        NOT NULL,
    justification       TEXT,
    status              VARCHAR(20)  NOT NULL,   -- PENDING | APPROVED | REJECTED | EXECUTED | FAILED
    decided_by          VARCHAR(100),
    decided_at          TIMESTAMPTZ,
    decision_note       TEXT,
    result              JSONB,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_pending_status ON pending_actions (status, created_at DESC);

-- Token ledger: every LLM call (local or remote agent) with model, tokens and estimated cost.
CREATE TABLE token_usage (
    id                BIGSERIAL PRIMARY KEY,
    trace_id          VARCHAR(100),
    agent_id          VARCHAR(100) NOT NULL,
    model             VARCHAR(60),
    prompt_tokens     BIGINT       NOT NULL,
    completion_tokens BIGINT       NOT NULL,
    total_tokens      BIGINT       NOT NULL,
    est_cost_usd      NUMERIC(12, 6) NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_usage_agent_day ON token_usage (agent_id, created_at);

CREATE TABLE eval_runs (
    id          VARCHAR(40)  PRIMARY KEY,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    passed      BOOLEAN      NOT NULL,
    summary     JSONB        NOT NULL,
    report      JSONB        NOT NULL
);
