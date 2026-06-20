CREATE TABLE java_method (
    id          BIGSERIAL PRIMARY KEY,
    class_id    BIGINT NOT NULL REFERENCES java_class(id) ON DELETE CASCADE,
    method_name VARCHAR(255) NOT NULL,
    return_type VARCHAR(255),
    parameters  JSONB,
    throws_list JSONB,
    visibility  VARCHAR(20),
    source_code TEXT,
    line_start  INT,
    line_end    INT
);

CREATE INDEX idx_java_method_class ON java_method(class_id);
