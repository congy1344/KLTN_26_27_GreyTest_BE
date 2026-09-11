-- Bảng lưu trữ phiên bản source (snapshot)
CREATE TABLE source_revision (
    id BIGSERIAL PRIMARY KEY,
    project_id BIGINT NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    source_type VARCHAR(50) NOT NULL,
    source_url VARCHAR(500),
    branch VARCHAR(255),
    commit_sha VARCHAR(100),
    storage_path VARCHAR(1000) NOT NULL,
    content_hash VARCHAR(128),
    logical_root VARCHAR(500),
    analysis_snapshot JSONB,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_source_revision_project_id ON source_revision(project_id);

-- Bảng lưu trữ phiên cập nhật source dạng bản nháp (Draft Update)
CREATE TABLE source_update (
    id BIGSERIAL PRIMARY KEY,
    project_id BIGINT NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    base_revision_id BIGINT REFERENCES source_revision(id) ON DELETE SET NULL,
    candidate_revision_id BIGINT NOT NULL REFERENCES source_revision(id) ON DELETE CASCADE,
    status VARCHAR(50) NOT NULL DEFAULT 'DRAFT',
    total_changed_methods INT DEFAULT 0,
    impact_summary JSONB,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_source_update_project_id ON source_update(project_id);
CREATE INDEX idx_source_update_status ON source_update(status);

-- Bảng lưu trữ từng đề xuất thay đổi cụ thể (BR, TP, TC, UT) trong bản nháp
CREATE TABLE source_update_item (
    id BIGSERIAL PRIMARY KEY,
    source_update_id BIGINT NOT NULL REFERENCES source_update(id) ON DELETE CASCADE,
    target_type VARCHAR(50) NOT NULL,
    target_id BIGINT,
    target_key VARCHAR(500),
    action VARCHAR(50) NOT NULL,
    reason TEXT,
    before_data JSONB,
    after_data JSONB,
    review_status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_source_update_item_update_id ON source_update_item(source_update_id);
CREATE INDEX idx_source_update_item_target ON source_update_item(target_type, target_id);

-- Bổ sung trường tham chiếu source revision và bản nháp đang xử lý vào project
ALTER TABLE project ADD COLUMN active_source_revision_id BIGINT REFERENCES source_revision(id) ON DELETE SET NULL;
ALTER TABLE project ADD COLUMN active_source_update_id BIGINT REFERENCES source_update(id) ON DELETE SET NULL;
