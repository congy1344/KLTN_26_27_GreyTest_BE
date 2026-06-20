CREATE TABLE test_case (
    id              BIGSERIAL PRIMARY KEY,
    test_plan_id    BIGINT NOT NULL REFERENCES test_plan(id) ON DELETE CASCADE,
    case_code       VARCHAR(50) NOT NULL,
    test_type       VARCHAR(30) NOT NULL,
    description     TEXT NOT NULL,
    preconditions   TEXT,
    test_data       JSONB,
    expected_result TEXT NOT NULL,
    priority        VARCHAR(10) NOT NULL,
    trace_source    VARCHAR(500),
    status          VARCHAR(30) NOT NULL,
    is_modified     BOOLEAN DEFAULT FALSE,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_test_case_plan ON test_case(test_plan_id);
