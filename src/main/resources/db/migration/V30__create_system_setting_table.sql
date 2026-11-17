-- Tạo bảng lưu trữ cấu hình hệ thống quản trị bền vững trong PostgreSQL
CREATE TABLE system_setting (
    setting_key VARCHAR(100) PRIMARY KEY,
    setting_value TEXT NOT NULL,
    description VARCHAR(500),
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT REFERENCES auth_user(id)
);

-- Seed các cấu hình quản trị hệ thống ban đầu
INSERT INTO system_setting (setting_key, setting_value, description)
VALUES
('ai.provider', 'openai', 'Nhà cung cấp mô hình AI mặc định (openai / google)'),
('ai.model', 'gpt-4o-mini', 'Mô hình suy luận AI mặc định'),
('ai.batch_concurrency', '4', 'Số luồng xử lý đồng thời khi sinh Unit Test theo batch'),
('vnpay.tmn_code', 'CGXZLS0Z', 'Mã TMN Code tích hợp VNPay Sandbox'),
('vnpay.url', 'https://sandbox.vnpayment.vn/paymentv2/vpcpay.html', 'Đường dẫn cổng thanh toán VNPay Sandbox'),
('log.output_dir', '../log/llm-usage', 'Thư mục lưu trữ nhật ký ngữ cảnh gọi LLM'),
('alert.quota_threshold_percent', '80', 'Ngưỡng phần trăm cảnh báo khi người dùng sắp cạn kiệt Quota')
ON CONFLICT (setting_key) DO NOTHING;

