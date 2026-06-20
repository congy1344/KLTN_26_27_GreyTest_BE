CREATE TABLE test_plan (
    id               BIGSERIAL PRIMARY KEY,
    project_id       BIGINT NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    business_rule_id BIGINT NOT NULL REFERENCES business_rule(id) ON DELETE CASCADE,
    plan_code        VARCHAR(50) NOT NULL,
    title            VARCHAR(500) NOT NULL,
    description      TEXT,
    test_type        VARCHAR(30) NOT NULL,
    status           VARCHAR(30) NOT NULL,
    is_modified      BOOLEAN DEFAULT FALSE,
    created_at       TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_test_plan_project ON test_plan(project_id);
CREATE INDEX idx_test_plan_rule ON test_plan(business_rule_id);
