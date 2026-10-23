-- M8 Agent runs: a goal and limits per agent, runs and their steps

ALTER TABLE agent ADD COLUMN goal VARCHAR(1000) NOT NULL DEFAULT '';
ALTER TABLE agent ADD COLUMN input_label VARCHAR(40) NOT NULL DEFAULT 'Task';
ALTER TABLE agent ADD COLUMN max_steps INT NOT NULL DEFAULT 12;
ALTER TABLE agent ADD COLUMN max_seconds INT NOT NULL DEFAULT 300;
ALTER TABLE agent ADD COLUMN max_tokens INT NOT NULL DEFAULT 100000;
ALTER TABLE agent ADD CONSTRAINT ck_agent_steps CHECK (max_steps BETWEEN 1 AND 30);
ALTER TABLE agent ADD CONSTRAINT ck_agent_seconds CHECK (max_seconds BETWEEN 10 AND 900);
ALTER TABLE agent ADD CONSTRAINT ck_agent_tokens CHECK (max_tokens BETWEEN 1000 AND 500000);

CREATE TABLE agent_run (
    id                UUID          NOT NULL PRIMARY KEY,
    agent_id          UUID          NOT NULL REFERENCES agent (id) ON DELETE CASCADE,
    agent_version     INT           NOT NULL,
    started_by        UUID REFERENCES app_user (id),
    api_key_id        UUID,
    actor             VARCHAR(64)   NOT NULL,
    draft             BOOLEAN       NOT NULL,
    input             VARCHAR(1000),
    status            VARCHAR(24)   NOT NULL,
    stop_reason       VARCHAR(24),
    result            VARCHAR(10000),
    detail            VARCHAR(500),
    max_steps         INT           NOT NULL,
    max_seconds       INT           NOT NULL,
    max_tokens        INT           NOT NULL,
    step_count        INT           NOT NULL DEFAULT 0,
    tokens_in         INT           NOT NULL DEFAULT 0,
    tokens_out        INT           NOT NULL DEFAULT 0,
    cancel_requested  BOOLEAN       NOT NULL DEFAULT FALSE,
    pending_action_id UUID,
    started_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    ended_at          TIMESTAMP WITH TIME ZONE,
    last_step_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at        TIMESTAMP WITH TIME ZONE,
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    version           BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT ck_run_status CHECK (status IN ('RUNNING', 'WAITING_FOR_APPROVAL', 'FINISHED')),
    CONSTRAINT ck_run_stop CHECK (stop_reason IS NULL OR stop_reason IN ('DONE', 'CANNOT_COMPLETE', 'STEP_LIMIT', 'TIME_LIMIT', 'TOKEN_LIMIT',
        'STOPPED_BY_USER', 'REFUSED_TOO_OFTEN', 'BLOCKED', 'DENIED', 'FAILED', 'INTERRUPTED'))
);
CREATE INDEX ix_run_agent ON agent_run (agent_id, started_at);
CREATE INDEX ix_run_status ON agent_run (status, last_step_at);
CREATE INDEX ix_run_expires ON agent_run (expires_at);

CREATE TABLE agent_run_step (
    id          UUID          NOT NULL PRIMARY KEY,
    run_id      UUID          NOT NULL REFERENCES agent_run (id) ON DELETE CASCADE,
    ordinal     INT           NOT NULL,
    type        VARCHAR(16)   NOT NULL,
    tool        VARCHAR(40),
    arguments   VARCHAR(1000),
    content     VARCHAR(4000),
    tokens_in   INT           NOT NULL DEFAULT 0,
    tokens_out  INT           NOT NULL DEFAULT 0,
    duration_ms BIGINT        NOT NULL DEFAULT 0,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    version     BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT uq_run_step UNIQUE (run_id, ordinal),
    CONSTRAINT ck_run_step_type CHECK (type IN ('MODEL', 'SKILL', 'TOOL_CALL', 'TOOL_RESULT', 'REFUSED', 'GUARDRAIL', 'PENDING', 'FINISH'))
);
