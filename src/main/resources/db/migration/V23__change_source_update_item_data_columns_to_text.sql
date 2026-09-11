-- Chuyển before_data và after_data từ JSONB sang TEXT để hỗ trợ lưu mã nguồn Java (không phải JSON) của MethodDiff
ALTER TABLE source_update_item ALTER COLUMN before_data TYPE TEXT USING before_data::text;
ALTER TABLE source_update_item ALTER COLUMN after_data TYPE TEXT USING after_data::text;
