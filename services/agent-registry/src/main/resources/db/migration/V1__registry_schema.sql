-- Agent registry: every agent in the platform, local or remote.
CREATE TABLE agents (
    agent_id        VARCHAR(100) PRIMARY KEY,
    name            VARCHAR(200) NOT NULL,
    description     TEXT,
    version         VARCHAR(40),
    owner_team      VARCHAR(100),
    host_service    VARCHAR(100),
    network_zone    VARCHAR(60),
    protocol        VARCHAR(20)  NOT NULL,           -- IN_PROCESS | A2A
    endpoint_url    TEXT,
    card_url        TEXT,
    skills          TEXT[]       NOT NULL DEFAULT '{}',
    risk_level      VARCHAR(10)  NOT NULL DEFAULT 'LOW',
    status          VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    last_heartbeat  TIMESTAMPTZ,
    registered_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    card            JSONB
);
CREATE INDEX idx_agents_skills ON agents USING GIN (skills);

CREATE TABLE agent_status_history (
    id          BIGSERIAL PRIMARY KEY,
    agent_id    VARCHAR(100) NOT NULL,
    from_status VARCHAR(20),
    to_status   VARCHAR(20)  NOT NULL,
    reason      TEXT,
    changed_by  VARCHAR(100),
    changed_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- MCP registry: tool servers agents can discover instead of hard-coding URLs.
CREATE TABLE mcp_servers (
    server_id      VARCHAR(100) PRIMARY KEY,
    name           VARCHAR(200) NOT NULL,
    version        VARCHAR(40),
    url            TEXT         NOT NULL,
    endpoint       VARCHAR(100) NOT NULL DEFAULT '/mcp',
    transport      VARCHAR(40)  NOT NULL DEFAULT 'streamable-http',
    network_zone   VARCHAR(60),
    tools          JSONB        NOT NULL DEFAULT '[]',
    status         VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    last_heartbeat TIMESTAMPTZ
);

-- Governance: what each agent may do. Agents without a policy are denied by default.
CREATE TABLE governance_policies (
    agent_id                VARCHAR(100) PRIMARY KEY,
    allowed_tools           TEXT[]      NOT NULL DEFAULT '{}',
    allowed_callers         TEXT[]      NOT NULL DEFAULT '{}',
    max_output_tokens       INT         NOT NULL DEFAULT 2048,
    max_tokens_per_request  INT         NOT NULL DEFAULT 20000,
    daily_token_budget      BIGINT      NOT NULL DEFAULT 2000000,
    requires_human_approval BOOLEAN     NOT NULL DEFAULT FALSE,
    data_classification     VARCHAR(30) NOT NULL DEFAULT 'INTERNAL',
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by              VARCHAR(100) NOT NULL DEFAULT 'seed'
);

-- Append-only audit trail of agent invocations, governance decisions and approvals.
CREATE TABLE audit_events (
    id         BIGSERIAL PRIMARY KEY,
    trace_id   VARCHAR(100),
    actor      VARCHAR(100) NOT NULL,
    action     VARCHAR(100) NOT NULL,
    target     VARCHAR(200),
    decision   VARCHAR(40),
    details    JSONB,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_audit_trace ON audit_events (trace_id);
CREATE INDEX idx_audit_created ON audit_events (created_at DESC);
