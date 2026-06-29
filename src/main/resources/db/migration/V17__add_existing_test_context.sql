CREATE TABLE existing_test (
    id               BIGSERIAL PRIMARY KEY,
    project_id        BIGINT NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    file_path         TEXT NOT NULL,
    package_name      VARCHAR(500),
    test_class_name   VARCHAR(255),
    related_class_id  BIGINT REFERENCES java_class(id) ON DELETE SET NULL,
    related_method_id BIGINT REFERENCES java_method(id) ON DELETE SET NULL,
    test_methods      JSONB,
    imports           JSONB,
    source_code       TEXT,
    created_at        TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_existing_test_project ON existing_test(project_id);
CREATE INDEX idx_existing_test_related_class ON existing_test(related_class_id);

ALTER TABLE business_rule
    ALTER COLUMN source TYPE VARCHAR(30),
    ADD COLUMN review_note TEXT;

ALTER TABLE unit_test
    ADD COLUMN generation_type VARCHAR(40),
    ADD COLUMN existing_test_file_path TEXT;
