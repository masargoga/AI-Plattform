-- Guardrail settings per agent (one row per agent, shares the agent id)
CREATE TABLE agent_guardrail (
    agent_id          UUID        NOT NULL PRIMARY KEY REFERENCES agent (id) ON DELETE CASCADE,
    personal_data     VARCHAR(8)  NOT NULL DEFAULT 'MASK',
    special_data      VARCHAR(8)  NOT NULL DEFAULT 'BLOCK',
    region            VARCHAR(8)  NOT NULL DEFAULT 'EU',
    retention         VARCHAR(16) NOT NULL DEFAULT 'DAYS_90',
    hidden_instruction_protection BOOLEAN NOT NULL DEFAULT TRUE,
    human_review      BOOLEAN     NOT NULL DEFAULT TRUE,
    max_records       INT         NOT NULL DEFAULT 50,
    purpose           VARCHAR(1000) NOT NULL DEFAULT '',
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    version           BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT ck_guard_pii CHECK (personal_data IN ('MASK', 'BLOCK', 'ALLOW')),
    CONSTRAINT ck_guard_special CHECK (special_data IN ('BLOCK', 'REVIEW', 'ALLOW')),
    CONSTRAINT ck_guard_region CHECK (region IN ('EU', 'ANY')),
    CONSTRAINT ck_guard_retention CHECK (retention IN ('NONE', 'DAYS_30', 'DAYS_90', 'YEAR_1', 'UNLIMITED')),
    CONSTRAINT ck_guard_max CHECK (max_records IN (10, 50, 200))
);

CREATE TABLE agent_approval_action (
    agent_id UUID        NOT NULL REFERENCES agent (id) ON DELETE CASCADE,
    action   VARCHAR(16) NOT NULL,
    PRIMARY KEY (agent_id, action),
    CONSTRAINT ck_approval_action CHECK (action IN ('EMAIL', 'REFUND', 'DELETE', 'SHARE', 'TICKET'))
);

-- Agents that exist before this migration get the recommended defaults
INSERT INTO agent_guardrail (agent_id, purpose, created_at, updated_at)
SELECT id, job, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM agent;
INSERT INTO agent_approval_action (agent_id, action) SELECT id, 'EMAIL' FROM agent;

-- Workspace policy: one live row plus a history of every change (no approval needed)
CREATE TABLE workspace_policy (
    id                UUID        NOT NULL PRIMARY KEY,
    personal_data_on  BOOLEAN     NOT NULL,
    special_data_on   BOOLEAN     NOT NULL,
    eu_only_on        BOOLEAN     NOT NULL,
    default_retention VARCHAR(16) NOT NULL,
    revision          INT         NOT NULL,
    changed_by        UUID REFERENCES app_user (id),
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    version           BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT ck_policy_retention CHECK (default_retention IN ('NONE', 'DAYS_30', 'DAYS_90', 'YEAR_1', 'UNLIMITED'))
);

CREATE TABLE workspace_policy_version (
    id                UUID        NOT NULL PRIMARY KEY,
    revision          INT         NOT NULL UNIQUE,
    personal_data_on  BOOLEAN     NOT NULL,
    special_data_on   BOOLEAN     NOT NULL,
    eu_only_on        BOOLEAN     NOT NULL,
    default_retention VARCHAR(16) NOT NULL,
    changed_by        UUID REFERENCES app_user (id),
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    version           BIGINT      NOT NULL DEFAULT 0
);

INSERT INTO workspace_policy (id, personal_data_on, special_data_on, eu_only_on, default_retention, revision, created_at, updated_at)
VALUES ('00000000-0000-0000-0000-000000000001', TRUE, TRUE, FALSE, 'DAYS_90', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
INSERT INTO workspace_policy_version (id, revision, personal_data_on, special_data_on, eu_only_on, default_retention, created_at, updated_at)
VALUES ('00000000-0000-0000-0000-0000000000a1', 1, TRUE, TRUE, FALSE, 'DAYS_90', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- Custom rules and their versions
CREATE TABLE custom_rule (
    id           UUID         NOT NULL PRIMARY KEY,
    name         VARCHAR(48)  NOT NULL,
    catches      VARCHAR(500) NOT NULL,
    action       VARCHAR(16)  NOT NULL,
    message      VARCHAR(500) NOT NULL DEFAULT '',
    scope        VARCHAR(16)  NOT NULL,
    enabled      BOOLEAN      NOT NULL DEFAULT TRUE,
    live_version INT          NOT NULL DEFAULT 0,
    sample       VARCHAR(500) NOT NULL DEFAULT '',
    created_by   UUID         NOT NULL REFERENCES app_user (id),
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    version      BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT ck_rule_action CHECK (action IN ('BLOCK', 'MASK', 'APPROVE', 'NOTE')),
    CONSTRAINT ck_rule_scope CHECK (scope IN ('ALL', 'SELECTED'))
);

CREATE TABLE custom_rule_agent (
    rule_id  UUID NOT NULL REFERENCES custom_rule (id) ON DELETE CASCADE,
    agent_id UUID NOT NULL REFERENCES agent (id) ON DELETE CASCADE,
    PRIMARY KEY (rule_id, agent_id)
);

CREATE TABLE rule_version (
    id             UUID         NOT NULL PRIMARY KEY,
    rule_id        UUID         NOT NULL REFERENCES custom_rule (id) ON DELETE CASCADE,
    version_number INT          NOT NULL,
    snapshot       VARCHAR(4000) NOT NULL,
    author_id      UUID         NOT NULL REFERENCES app_user (id),
    note           VARCHAR(500) NOT NULL,
    status         VARCHAR(16)  NOT NULL,
    approver_id    UUID REFERENCES app_user (id),
    decided_at     TIMESTAMP WITH TIME ZONE,
    -- 1 while pending, NULL afterwards: the unique constraint allows only one pending version per rule
    pending_flag   INT,
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    version        BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uq_rule_version UNIQUE (rule_id, version_number),
    CONSTRAINT uq_rule_pending UNIQUE (rule_id, pending_flag),
    CONSTRAINT ck_rule_version_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'WITHDRAWN')),
    -- four-eyes rule, backed by the database
    CONSTRAINT ck_rule_version_four_eyes CHECK (approver_id IS NULL OR approver_id <> author_id),
    CONSTRAINT ck_rule_version_pending CHECK ((status = 'PENDING' AND pending_flag = 1) OR (status <> 'PENDING' AND pending_flag IS NULL))
);

-- Append-only audit log
CREATE TABLE audit_entry (
    id          UUID         NOT NULL PRIMARY KEY,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    agent_id    UUID,
    agent_name  VARCHAR(80),
    actor       VARCHAR(64)  NOT NULL,
    event       VARCHAR(500) NOT NULL,
    outcome     VARCHAR(16)  NOT NULL,
    rule_id     UUID,
    request_id  VARCHAR(64),
    details     VARCHAR(4000),
    CONSTRAINT ck_audit_outcome CHECK (outcome IN ('ALLOWED', 'MASKED', 'BLOCKED', 'NEEDS_APPROVAL'))
);
CREATE INDEX ix_audit_time ON audit_entry (occurred_at DESC);

-- Compliance records and data subject requests
CREATE TABLE compliance_record (
    id         UUID         NOT NULL PRIMARY KEY,
    title      VARCHAR(200) NOT NULL,
    owner_name VARCHAR(80)  NOT NULL,
    note       VARCHAR(200) NOT NULL DEFAULT '',
    due_date   DATE,
    status     VARCHAR(16)  NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    version    BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT ck_record_status CHECK (status IN ('TODO', 'IN_PROGRESS', 'DONE'))
);

CREATE TABLE subject_request (
    id         UUID         NOT NULL PRIMARY KEY,
    kind       VARCHAR(16)  NOT NULL,
    subject    VARCHAR(120) NOT NULL,
    due_date   DATE         NOT NULL,
    done_at    TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    version    BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT ck_request_kind CHECK (kind IN ('ACCESS', 'DELETION'))
);
