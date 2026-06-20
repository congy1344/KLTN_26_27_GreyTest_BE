CREATE TABLE java_class (
    id           BIGSERIAL PRIMARY KEY,
    project_id   BIGINT NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    package_name VARCHAR(500),
    class_name   VARCHAR(255) NOT NULL,
    file_path    TEXT,
    class_type   VARCHAR(50),
    source_code  TEXT,
    created_at   TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_java_class_project ON java_class(project_id);
CREATE INDEX idx_java_class_type ON java_class(class_type);
