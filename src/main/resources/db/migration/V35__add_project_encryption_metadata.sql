-- V35: Add encryption metadata for Project and SourceRevision
ALTER TABLE project
    ADD COLUMN IF NOT EXISTS storage_encrypted BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS encrypted_dek VARCHAR(512),
    ADD COLUMN IF NOT EXISTS dek_key_version VARCHAR(64),
    ADD COLUMN IF NOT EXISTS encryption_algorithm VARCHAR(32);

ALTER TABLE source_revision
    ADD COLUMN IF NOT EXISTS storage_encrypted BOOLEAN NOT NULL DEFAULT FALSE;
