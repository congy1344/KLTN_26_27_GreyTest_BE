CREATE TABLE auth_user (
    id            BIGSERIAL PRIMARY KEY,
    email         VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    full_name     VARCHAR(255),
    role          VARCHAR(30) NOT NULL,
    enabled       BOOLEAN NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_auth_user_role ON auth_user(role);

ALTER TABLE project
    ADD COLUMN owner_user_id BIGINT REFERENCES auth_user(id) ON DELETE SET NULL;

CREATE INDEX idx_project_owner_user ON project(owner_user_id);
