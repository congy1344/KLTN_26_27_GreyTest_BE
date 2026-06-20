CREATE TABLE business_rule (
    id          BIGSERIAL PRIMARY KEY,
    project_id  BIGINT NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    method_id   BIGINT REFERENCES java_method(id) ON DELETE SET NULL,
    rule_code   VARCHAR(50) NOT NULL,
    description TEXT NOT NULL,
    source      VARCHAR(20) NOT NULL,
    status      VARCHAR(30) NOT NULL,
    is_modified BOOLEAN DEFAULT FALSE,
    created_at  TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_business_rule_project ON business_rule(project_id);
CREATE INDEX idx_business_rule_method ON business_rule(method_id);
