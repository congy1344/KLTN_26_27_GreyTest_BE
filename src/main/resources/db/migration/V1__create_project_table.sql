CREATE TABLE project (
    id           BIGSERIAL PRIMARY KEY,
    name         VARCHAR(255) NOT NULL,
    source_type  VARCHAR(20)  NOT NULL,
    source_url   TEXT,
    storage_path TEXT,
    status       VARCHAR(50)  NOT NULL,
    created_at   TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMP    NOT NULL DEFAULT NOW()
);
