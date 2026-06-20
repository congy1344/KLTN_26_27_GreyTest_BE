CREATE TABLE experiment_metric (
    id                      BIGSERIAL PRIMARY KEY,
    project_id              BIGINT NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    method_used             VARCHAR(50),
    requirement_coverage    DECIMAL(5,2),
    line_coverage           DECIMAL(5,2),
    branch_coverage         DECIMAL(5,2),
    generation_time_seconds INT,
    user_modification_rate  DECIMAL(5,2),
    input_tokens            INT,
    output_tokens           INT,
    stability_score         DECIMAL(5,2),
    traceability_score      DECIMAL(5,2),
    run_at                  TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_experiment_metric_project ON experiment_metric(project_id);
