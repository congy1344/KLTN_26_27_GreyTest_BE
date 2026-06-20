CREATE TABLE unit_test (
    id               BIGSERIAL PRIMARY KEY,
    test_case_id     BIGINT NOT NULL REFERENCES test_case(id) ON DELETE CASCADE,
    test_class_name  VARCHAR(255) NOT NULL,
    test_method_name VARCHAR(255) NOT NULL,
    package_name     VARCHAR(500),
    source_code      TEXT NOT NULL,
    file_path        TEXT,
    created_at       TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_unit_test_case ON unit_test(test_case_id);
