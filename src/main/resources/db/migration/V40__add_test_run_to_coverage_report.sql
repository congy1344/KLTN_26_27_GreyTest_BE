-- Lưu số test của lượt chạy sandbox cùng báo cáo coverage để "Test đạt" không mất khi backend khởi động lại.
-- NULL nghĩa là báo cáo do người dùng tự upload, không có lượt chạy test đi kèm.
ALTER TABLE coverage_report ADD COLUMN tests_run INTEGER;
ALTER TABLE coverage_report ADD COLUMN tests_failed INTEGER;
