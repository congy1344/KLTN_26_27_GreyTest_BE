-- Chỉ bổ sung metadata; giữ nguyên các bản nháp và quyết định review hiện có.
ALTER TABLE source_update ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE source_update_item ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE source_update_item ADD COLUMN user_revision BIGINT NOT NULL DEFAULT 0;

CREATE TABLE generation_checkpoint (
    id BIGSERIAL PRIMARY KEY,
    project_id BIGINT NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    source_update_id BIGINT NOT NULL REFERENCES source_update(id) ON DELETE CASCADE,
    stage VARCHAR(50) NOT NULL,
    target_key VARCHAR(1000) NOT NULL,
    fingerprint VARCHAR(128),
    provenance JSONB,
    state VARCHAR(30) NOT NULL CHECK (state IN ('RUNNING', 'SUCCEEDED', 'FAILED', 'BLOCKED', 'CONFLICT', 'CANCELLED')),
    output_json TEXT,
    output_digest VARCHAR(128),
    owner VARCHAR(36),
    fence BIGINT NOT NULL DEFAULT 0,
    lease_until TIMESTAMPTZ,
    error TEXT,
    metrics JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (source_update_id, stage, target_key)
);
CREATE INDEX idx_generation_checkpoint_project ON generation_checkpoint(project_id);

CREATE TABLE llm_response_cache (
    id BIGSERIAL PRIMARY KEY,
    project_id BIGINT NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    stage VARCHAR(50) NOT NULL,
    fingerprint VARCHAR(128) NOT NULL,
    provenance JSONB NOT NULL DEFAULT '{"formatVersion":1}',
    response TEXT NOT NULL,
    response_digest VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ NOT NULL DEFAULT (CURRENT_TIMESTAMP + INTERVAL '7 days'),
    UNIQUE (project_id, stage, fingerprint)
);
CREATE INDEX idx_llm_response_cache_expiry ON llm_response_cache(expires_at);
