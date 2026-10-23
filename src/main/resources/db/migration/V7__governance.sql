-- M7 Governance: approval lifecycle with versions for skills and agents, data source settings, skill test cases

-- Skills: the old revision counter becomes the number of the live (approved) version
ALTER TABLE skill RENAME COLUMN revision TO live_version;
UPDATE skill SET live_version = 1;
ALTER TABLE skill ADD COLUMN lifecycle_status VARCHAR(16) NOT NULL DEFAULT 'APPROVED';
ALTER TABLE skill ADD COLUMN compliance_relevant BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE skill ADD CONSTRAINT ck_skill_lifecycle CHECK (lifecycle_status IN ('DRAFT', 'IN_REVIEW', 'APPROVED', 'RETIRED'));

ALTER TABLE agent ADD COLUMN lifecycle_status VARCHAR(16) NOT NULL DEFAULT 'APPROVED';
ALTER TABLE agent ADD COLUMN live_version INT NOT NULL DEFAULT 1;
ALTER TABLE agent ADD CONSTRAINT ck_agent_lifecycle CHECK (lifecycle_status IN ('DRAFT', 'IN_REVIEW', 'APPROVED', 'RETIRED'));

-- Versions. open_flag is 1 while a version is a draft or pending, NULL afterwards: one open version per item.
CREATE TABLE skill_version (
    id               UUID          NOT NULL PRIMARY KEY,
    item_id          UUID          NOT NULL REFERENCES skill (id) ON DELETE CASCADE,
    version_number   INT           NOT NULL,
    snapshot         VARCHAR(30000) NOT NULL,
    author_id        UUID          NOT NULL REFERENCES app_user (id),
    note             VARCHAR(500)  NOT NULL DEFAULT '',
    status           VARCHAR(16)   NOT NULL,
    approver_id      UUID REFERENCES app_user (id),
    decided_at       TIMESTAMP WITH TIME ZONE,
    decision_comment VARCHAR(500),
    open_flag        INT,
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    version          BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT uq_skill_version UNIQUE (item_id, version_number),
    CONSTRAINT uq_skill_open UNIQUE (item_id, open_flag),
    CONSTRAINT ck_skill_version_status CHECK (status IN ('DRAFT', 'PENDING', 'APPROVED', 'REJECTED', 'WITHDRAWN')),
    CONSTRAINT ck_skill_version_four_eyes CHECK (approver_id IS NULL OR approver_id <> author_id),
    CONSTRAINT ck_skill_version_open CHECK ((status IN ('DRAFT', 'PENDING') AND open_flag = 1) OR (status NOT IN ('DRAFT', 'PENDING') AND open_flag IS NULL))
);

CREATE TABLE agent_version (
    id               UUID          NOT NULL PRIMARY KEY,
    item_id          UUID          NOT NULL REFERENCES agent (id) ON DELETE CASCADE,
    version_number   INT           NOT NULL,
    snapshot         VARCHAR(30000) NOT NULL,
    author_id        UUID          NOT NULL REFERENCES app_user (id),
    note             VARCHAR(500)  NOT NULL DEFAULT '',
    status           VARCHAR(16)   NOT NULL,
    approver_id      UUID REFERENCES app_user (id),
    decided_at       TIMESTAMP WITH TIME ZONE,
    decision_comment VARCHAR(500),
    open_flag        INT,
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    version          BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT uq_agent_version UNIQUE (item_id, version_number),
    CONSTRAINT uq_agent_open UNIQUE (item_id, open_flag),
    CONSTRAINT ck_agent_version_status CHECK (status IN ('DRAFT', 'PENDING', 'APPROVED', 'REJECTED', 'WITHDRAWN')),
    CONSTRAINT ck_agent_version_four_eyes CHECK (approver_id IS NULL OR approver_id <> author_id),
    CONSTRAINT ck_agent_version_open CHECK ((status IN ('DRAFT', 'PENDING') AND open_flag = 1) OR (status NOT IN ('DRAFT', 'PENDING') AND open_flag IS NULL))
);

-- Data source settings
ALTER TABLE data_source ADD COLUMN owner_name VARCHAR(80) NOT NULL DEFAULT '';
ALTER TABLE data_source ADD COLUMN trust_level VARCHAR(16) NOT NULL DEFAULT 'INFORMATIONAL';
ALTER TABLE data_source ADD COLUMN on_failure VARCHAR(16) NOT NULL DEFAULT 'USE_LAST_COPY';
ALTER TABLE data_source ADD COLUMN max_age_days INT NOT NULL DEFAULT 5;
ALTER TABLE data_source ADD COLUMN review_by DATE;
ALTER TABLE data_source ADD COLUMN version_info VARCHAR(200) NOT NULL DEFAULT '';
ALTER TABLE data_source ADD CONSTRAINT ck_source_trust CHECK (trust_level IN ('AUTHORITATIVE', 'INFORMATIONAL'));
ALTER TABLE data_source ADD CONSTRAINT ck_source_failure CHECK (on_failure IN ('USE_LAST_COPY', 'STOP_AND_REPORT'));
ALTER TABLE data_source ADD CONSTRAINT ck_source_max_age CHECK (max_age_days BETWEEN 1 AND 3650);

-- Skill test cases and their latest results
CREATE TABLE skill_test_case (
    id         UUID          NOT NULL PRIMARY KEY,
    skill_id   UUID          NOT NULL REFERENCES skill (id) ON DELETE CASCADE,
    name       VARCHAR(80)   NOT NULL,
    input      VARCHAR(4000) NOT NULL,
    checks     VARCHAR(4000) NOT NULL,
    created_by UUID          NOT NULL REFERENCES app_user (id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    version    BIGINT        NOT NULL DEFAULT 0
);

CREATE TABLE skill_test_result (
    id             UUID          NOT NULL PRIMARY KEY,
    case_id        UUID          NOT NULL REFERENCES skill_test_case (id) ON DELETE CASCADE,
    skill_id       UUID          NOT NULL REFERENCES skill (id) ON DELETE CASCADE,
    version_number INT           NOT NULL,
    draft          BOOLEAN       NOT NULL,
    outcome        VARCHAR(16)   NOT NULL,
    detail         VARCHAR(2000) NOT NULL DEFAULT '',
    run_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    version        BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT ck_skill_result_outcome CHECK (outcome IN ('PASSED', 'FAILED', 'NOT_RUN'))
);
CREATE INDEX ix_skill_result_skill ON skill_test_result (skill_id, version_number);
