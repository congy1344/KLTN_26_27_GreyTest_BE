ALTER TABLE java_class
    ADD COLUMN qualified_name VARCHAR(1000);

UPDATE java_class
SET qualified_name = CASE
    WHEN package_name IS NULL OR package_name = '' THEN class_name
    ELSE package_name || '.' || class_name
END
WHERE qualified_name IS NULL;

ALTER TABLE java_class
    ALTER COLUMN qualified_name SET NOT NULL;

CREATE INDEX idx_java_class_qualified_name ON java_class(qualified_name);

DELETE FROM service_repository_relation duplicate
USING service_repository_relation original
WHERE duplicate.id > original.id
  AND duplicate.service_class_id = original.service_class_id
  AND duplicate.repository_class_id = original.repository_class_id;

CREATE UNIQUE INDEX uq_service_repository_relation
    ON service_repository_relation(service_class_id, repository_class_id);
