CREATE TABLE coverage_report (
    id                   BIGSERIAL PRIMARY KEY,
    project_id           BIGINT NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    line_coverage        DECIMAL(5,2),
    branch_coverage      DECIMAL(5,2),
    requirement_coverage DECIMAL(5,2),
    total_lines          INT,
    covered_lines        INT,
    total_branches       INT,
    covered_branches     INT,
    uploaded_at          TIMESTAMP NOT NULL DEFAULT NOW(),
    xml_file_path        TEXT
);

CREATE INDEX idx_coverage_project ON coverage_report(project_id);

CREATE TABLE coverage_detail (
    id              BIGSERIAL PRIMARY KEY,
    report_id       BIGINT NOT NULL REFERENCES coverage_report(id) ON DELETE CASCADE,
    method_id       BIGINT REFERENCES java_method(id) ON DELETE SET NULL,
    line_coverage   DECIMAL(5,2),
    branch_coverage DECIMAL(5,2),
    missed_lines    JSONB,
    missed_branches JSONB,
    has_gap         BOOLEAN DEFAULT FALSE
);

CREATE INDEX idx_coverage_detail_report ON coverage_detail(report_id);
CREATE INDEX idx_coverage_detail_method ON coverage_detail(method_id);
