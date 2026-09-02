ALTER TABLE coverage_report
    ADD COLUMN service_path VARCHAR(1000) NOT NULL DEFAULT '.';

UPDATE coverage_report
SET service_path = '.'
WHERE service_path IS NULL OR BTRIM(service_path) = '';

CREATE INDEX idx_coverage_project_service
    ON coverage_report(project_id, service_path, id DESC);
