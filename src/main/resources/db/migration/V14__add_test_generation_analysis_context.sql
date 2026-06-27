CREATE TABLE relevant_annotation (
    id              BIGSERIAL PRIMARY KEY,
    project_id      BIGINT NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    class_id        BIGINT REFERENCES java_class(id) ON DELETE CASCADE,
    method_id       BIGINT REFERENCES java_method(id) ON DELETE CASCADE,
    target_type     VARCHAR(50) NOT NULL,
    category        VARCHAR(50) NOT NULL,
    annotation_name VARCHAR(255) NOT NULL,
    attributes      VARCHAR(2000),
    CONSTRAINT chk_relevant_annotation_target CHECK (
        (target_type = 'CLASS' AND class_id IS NOT NULL AND method_id IS NULL)
        OR (target_type = 'METHOD' AND class_id IS NOT NULL AND method_id IS NOT NULL)
    )
);

CREATE INDEX idx_relevant_annotation_class ON relevant_annotation(class_id);
CREATE INDEX idx_relevant_annotation_method ON relevant_annotation(method_id);
CREATE UNIQUE INDEX ux_relevant_annotation_identity
    ON relevant_annotation(target_type, class_id, COALESCE(method_id, -1), annotation_name, COALESCE(attributes, ''));

CREATE TABLE controller_service_relation (
    id                   BIGSERIAL PRIMARY KEY,
    controller_class_id  BIGINT NOT NULL REFERENCES java_class(id) ON DELETE CASCADE,
    controller_method_id BIGINT NOT NULL REFERENCES java_method(id) ON DELETE CASCADE,
    service_class_id     BIGINT NOT NULL REFERENCES java_class(id) ON DELETE CASCADE,
    service_method_id    BIGINT REFERENCES java_method(id) ON DELETE SET NULL,
    service_field_name   VARCHAR(255) NOT NULL,
    service_field_type   VARCHAR(1000) NOT NULL,
    called_method_name   VARCHAR(255) NOT NULL
);

CREATE INDEX idx_csr_controller_class ON controller_service_relation(controller_class_id);
CREATE INDEX idx_csr_controller_method ON controller_service_relation(controller_method_id);
CREATE INDEX idx_csr_service_class ON controller_service_relation(service_class_id);
CREATE UNIQUE INDEX ux_csr_identity
    ON controller_service_relation(
        controller_method_id,
        service_class_id,
        COALESCE(service_method_id, -1),
        service_field_name,
        called_method_name
    );
