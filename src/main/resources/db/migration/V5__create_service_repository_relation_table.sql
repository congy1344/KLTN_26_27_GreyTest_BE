CREATE TABLE service_repository_relation (
    id                  BIGSERIAL PRIMARY KEY,
    service_class_id    BIGINT NOT NULL REFERENCES java_class(id) ON DELETE CASCADE,
    repository_class_id BIGINT NOT NULL REFERENCES java_class(id) ON DELETE CASCADE
);

CREATE INDEX idx_srr_service ON service_repository_relation(service_class_id);
CREATE INDEX idx_srr_repository ON service_repository_relation(repository_class_id);
