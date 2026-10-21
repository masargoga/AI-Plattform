CREATE TABLE skill (
    id          UUID         NOT NULL PRIMARY KEY,
    name        VARCHAR(48)  NOT NULL,
    type        VARCHAR(16)  NOT NULL,
    description VARCHAR(500) NOT NULL DEFAULT '',
    trigger_text VARCHAR(1000) NOT NULL DEFAULT '',
    instructions VARCHAR(8000) NOT NULL DEFAULT '',
    output_format VARCHAR(16) NOT NULL DEFAULT 'PLAIN_TEXT',
    revision    INT          NOT NULL DEFAULT 1,
    created_by  UUID         NOT NULL REFERENCES app_user (id),
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    version     BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT ck_skill_type CHECK (type IN ('INSTRUCTION', 'WORKFLOW', 'TOOL')),
    CONSTRAINT ck_skill_output CHECK (output_format IN ('PLAIN_TEXT', 'BULLETS', 'TABLE', 'JSON'))
);

CREATE TABLE skill_tool (
    skill_id UUID        NOT NULL REFERENCES skill (id) ON DELETE CASCADE,
    tool     VARCHAR(32) NOT NULL,
    PRIMARY KEY (skill_id, tool),
    CONSTRAINT ck_skill_tool CHECK (tool IN ('SEARCH_DOCUMENTS', 'BROWSE_WEB', 'SEND_EMAIL', 'CREATE_TICKET', 'READ_SHEETS'))
);

CREATE TABLE data_source (
    id          UUID         NOT NULL PRIMARY KEY,
    name        VARCHAR(80)  NOT NULL,
    kind        VARCHAR(16)  NOT NULL,
    location    VARCHAR(500) NOT NULL,
    config      VARCHAR(4000),
    status      VARCHAR(16)  NOT NULL DEFAULT 'SYNCED',
    item_count  INT          NOT NULL DEFAULT 0,
    last_synced TIMESTAMP WITH TIME ZONE,
    last_error  VARCHAR(500),
    created_by  UUID         NOT NULL REFERENCES app_user (id),
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    version     BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT ck_source_kind CHECK (kind IN ('UPLOAD', 'WEBSITE', 'FOLDER', 'WIKI', 'DATABASE', 'API')),
    CONSTRAINT ck_source_status CHECK (status IN ('SYNCED', 'SYNCING', 'ERROR'))
);

CREATE TABLE agent (
    id           UUID         NOT NULL PRIMARY KEY,
    name         VARCHAR(40)  NOT NULL,
    job          VARCHAR(200) NOT NULL DEFAULT '',
    instructions VARCHAR(8000) NOT NULL DEFAULT '',
    kind         VARCHAR(16)  NOT NULL DEFAULT 'MANAGED',
    status       VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    autonomy     VARCHAR(8)   NOT NULL DEFAULT 'ASK',
    model_tier   VARCHAR(16)  NOT NULL DEFAULT 'BALANCED',
    last_run     TIMESTAMP WITH TIME ZONE,
    run_count    INT          NOT NULL DEFAULT 0,
    owner_id     UUID         NOT NULL REFERENCES app_user (id),
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    version      BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT ck_agent_kind CHECK (kind IN ('MANAGED', 'EXTERNAL')),
    CONSTRAINT ck_agent_status CHECK (status IN ('ACTIVE', 'PAUSED')),
    CONSTRAINT ck_agent_autonomy CHECK (autonomy IN ('ASK', 'ACT', 'AUTO')),
    CONSTRAINT ck_agent_model CHECK (model_tier IN ('FAST', 'BALANCED', 'DEEP'))
);

CREATE TABLE agent_skill (
    agent_id UUID NOT NULL REFERENCES agent (id) ON DELETE CASCADE,
    skill_id UUID NOT NULL REFERENCES skill (id),
    PRIMARY KEY (agent_id, skill_id)
);

CREATE TABLE agent_source (
    agent_id  UUID NOT NULL REFERENCES agent (id) ON DELETE CASCADE,
    source_id UUID NOT NULL REFERENCES data_source (id),
    PRIMARY KEY (agent_id, source_id)
);
