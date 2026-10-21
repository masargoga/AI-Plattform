-- Searchable content of data sources
CREATE TABLE source_document (
    id           UUID         NOT NULL PRIMARY KEY,
    source_id    UUID         NOT NULL REFERENCES data_source (id) ON DELETE CASCADE,
    title        VARCHAR(255) NOT NULL,
    uri          VARCHAR(500),
    content_hash VARCHAR(64)  NOT NULL,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    version      BIGINT       NOT NULL DEFAULT 0
);
CREATE INDEX ix_document_source ON source_document (source_id);

CREATE TABLE chunk (
    id          UUID          NOT NULL PRIMARY KEY,
    document_id UUID          NOT NULL REFERENCES source_document (id) ON DELETE CASCADE,
    ordinal     INT           NOT NULL,
    text        VARCHAR(2000) NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    version     BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT uq_chunk_order UNIQUE (document_id, ordinal)
);

-- Conversations (test chat and API) and their messages. Message text is stored masked.
CREATE TABLE conversation (
    id         UUID        NOT NULL PRIMARY KEY,
    agent_id   UUID        NOT NULL REFERENCES agent (id) ON DELETE CASCADE,
    channel    VARCHAR(8)  NOT NULL,
    started_by UUID REFERENCES app_user (id),
    started_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    version    BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT ck_conversation_channel CHECK (channel IN ('TEST', 'API'))
);
CREATE INDEX ix_conversation_expires ON conversation (expires_at);

CREATE TABLE message (
    id              UUID          NOT NULL PRIMARY KEY,
    conversation_id UUID          NOT NULL REFERENCES conversation (id) ON DELETE CASCADE,
    ordinal         INT           NOT NULL,
    role            VARCHAR(16)   NOT NULL,
    text            VARCHAR(16000) NOT NULL,
    trace           VARCHAR(4000),
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    version         BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT ck_message_role CHECK (role IN ('user', 'assistant')),
    CONSTRAINT uq_message_order UNIQUE (conversation_id, ordinal)
);

-- Needs-approval requests waiting for a person
CREATE TABLE pending_action (
    id              UUID         NOT NULL PRIMARY KEY,
    agent_id        UUID         NOT NULL REFERENCES agent (id) ON DELETE CASCADE,
    conversation_id UUID,
    rule_id         UUID,
    description     VARCHAR(500) NOT NULL,
    status          VARCHAR(16)  NOT NULL,
    requested_by    UUID REFERENCES app_user (id),
    decided_by      UUID REFERENCES app_user (id),
    decided_at      TIMESTAMP WITH TIME ZONE,
    audit_entry_id  UUID,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    version         BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT ck_pending_status CHECK (status IN ('PENDING', 'APPROVED', 'DENIED'))
);
CREATE INDEX ix_pending_audit ON pending_action (audit_entry_id);
