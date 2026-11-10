-- Tạo bảng lưu trữ danh mục gói dịch vụ và hạn mức Quota bền vững trong PostgreSQL
CREATE TABLE tier_package (
    code VARCHAR(50) PRIMARY KEY,
    name VARCHAR(150) NOT NULL,
    price BIGINT NOT NULL DEFAULT 0,
    cycle VARCHAR(20) NOT NULL DEFAULT 'MONTHLY',
    quota_limit INT NOT NULL DEFAULT 100,
    batch_concurrency INT NOT NULL DEFAULT 2,
    features TEXT NOT NULL,
    description VARCHAR(500),
    active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP
);

-- Seed các gói dịch vụ tiêu chuẩn mặc định
INSERT INTO tier_package (code, name, price, cycle, quota_limit, batch_concurrency, features, description, active)
VALUES
('FREE', 'Gói Miễn Phí (FREE)', 0, 'MONTHLY', 100, 2, 'Trích xuất Quy tắc Nghiệp vụ (AST)
Sinh Test Plan & Test Case
Sinh Unit Test JUnit 5 cơ bản', 'Dành cho sinh viên và dự án thử nghiệm cơ bản', true),
('PLUS', 'Gói Tiêu Chuẩn (PLUS)', 10000, 'WEEKLY', 200, 3, 'Toàn bộ tính năng gói FREE
200 lượt gọi AI hồi lại hàng tuần
Sinh tối đa 3 batch song song
Ưu tiên xử lý hàng đợi tác vụ', 'Dành cho nhóm phát triển mở rộng / dự án sprint nước rút', true),
('PRO', 'Gói Chuyên Nghiệp (PRO)', 20000, 'MONTHLY', 300, 4, 'Toàn bộ tính năng gói PLUS
Phân tích cú pháp AST & Call Graph sâu
Sinh Unit Test JUnit 5 song song 4 luồng
Vòng lặp JaCoCo Refinement tự cải thiện độ bao phủ', 'Toàn bộ tính năng phân tích sâu & JaCoCo Refinement', true)
ON CONFLICT (code) DO NOTHING;

