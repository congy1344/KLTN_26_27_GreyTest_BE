CREATE TABLE user_activity_log (
    id                 BIGSERIAL PRIMARY KEY,
    user_id            BIGINT NOT NULL REFERENCES auth_user(id) ON DELETE CASCADE,
    action_type        VARCHAR(50) NOT NULL,
    related_project_id BIGINT REFERENCES project(id) ON DELETE SET NULL,
    created_at         TIMESTAMP NOT NULL DEFAULT NOW(),
    metadata           JSONB NOT NULL DEFAULT '{}'::jsonb
);

CREATE INDEX idx_activity_user_created ON user_activity_log(user_id, created_at DESC);
CREATE INDEX idx_activity_action_created ON user_activity_log(action_type, created_at DESC);
CREATE INDEX idx_activity_project ON user_activity_log(related_project_id);

CREATE TABLE usage_quota (
    id           BIGSERIAL PRIMARY KEY,
    user_id      BIGINT NOT NULL UNIQUE REFERENCES auth_user(id) ON DELETE CASCADE,
    quota_limit  INTEGER NOT NULL CHECK (quota_limit >= 0),
    quota_used   INTEGER NOT NULL DEFAULT 0 CHECK (quota_used >= 0),
    period_start DATE NOT NULL,
    updated_at   TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_usage_quota_period ON usage_quota(period_start);

