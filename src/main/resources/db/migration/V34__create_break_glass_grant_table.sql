CREATE TABLE IF NOT EXISTS admin_break_glass_grant (
    id                 BIGSERIAL PRIMARY KEY,
    project_id         BIGINT NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    admin_user_id      BIGINT NOT NULL REFERENCES auth_user(id) ON DELETE CASCADE,
    approver_user_id   BIGINT NOT NULL REFERENCES auth_user(id) ON DELETE CASCADE,
    reason             VARCHAR(1000) NOT NULL,
    duration_minutes   INTEGER NOT NULL DEFAULT 30,
    status             VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    granted_at         TIMESTAMP,
    expires_at         TIMESTAMP,
    revoked            BOOLEAN NOT NULL DEFAULT FALSE,
    created_at         TIMESTAMP NOT NULL DEFAULT NOW()
);

ALTER TABLE admin_break_glass_grant 
    ADD COLUMN IF NOT EXISTS duration_minutes INTEGER NOT NULL DEFAULT 30,
    ADD COLUMN IF NOT EXISTS status VARCHAR(20) NOT NULL DEFAULT 'PENDING';

ALTER TABLE admin_break_glass_grant 
    ALTER COLUMN granted_at DROP NOT NULL,
    ALTER COLUMN expires_at DROP NOT NULL;

CREATE INDEX IF NOT EXISTS idx_break_glass_lookup ON admin_break_glass_grant (project_id, admin_user_id, status, revoked, expires_at);
