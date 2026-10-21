-- API keys: one agent per key, only a hash is stored, shown once at creation
CREATE TABLE api_key (
    id           UUID         NOT NULL PRIMARY KEY,
    agent_id     UUID         NOT NULL REFERENCES agent (id) ON DELETE CASCADE,
    name         VARCHAR(80)  NOT NULL,
    prefix       VARCHAR(16)  NOT NULL UNIQUE,
    secret_hash  VARCHAR(64)  NOT NULL,
    created_by   UUID         NOT NULL REFERENCES app_user (id),
    last_used_at TIMESTAMP WITH TIME ZONE,
    revoked_at   TIMESTAMP WITH TIME ZONE,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    version      BIGINT       NOT NULL DEFAULT 0
);
CREATE INDEX ix_api_key_agent ON api_key (agent_id);

-- API conversations belong to the key that started them
ALTER TABLE conversation ADD COLUMN api_key_id UUID REFERENCES api_key (id) ON DELETE CASCADE;

-- Domains the website connector may crawl (managed by Admins)
CREATE TABLE allowed_domain (
    id         UUID         NOT NULL PRIMARY KEY,
    domain     VARCHAR(255) NOT NULL UNIQUE,
    added_by   UUID         NOT NULL REFERENCES app_user (id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    version    BIGINT       NOT NULL DEFAULT 0
);
