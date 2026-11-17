CREATE TABLE user_feedback (
    id           BIGSERIAL PRIMARY KEY,
    user_id      BIGINT REFERENCES auth_user(id) ON DELETE SET NULL,
    user_email   VARCHAR(255),
    user_name    VARCHAR(255),
    category     VARCHAR(64) NOT NULL,
    title        VARCHAR(255) NOT NULL,
    content      TEXT NOT NULL,
    project_name VARCHAR(255),
    status       VARCHAR(32) NOT NULL DEFAULT 'NEW',
    admin_note   TEXT,
    created_at   TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMP
);

CREATE INDEX idx_user_feedback_status ON user_feedback(status);
CREATE INDEX idx_user_feedback_category ON user_feedback(category);
CREATE INDEX idx_user_feedback_created ON user_feedback(created_at DESC);

-- Seed dữ liệu phản hồi ban đầu của người dùng từ hệ thống
INSERT INTO user_feedback (id, user_id, user_email, user_name, category, title, content, status, admin_note, created_at, updated_at, project_name)
VALUES
(1, (SELECT id FROM auth_user WHERE email = 'sizngu@gmail.com'), 'sizngu@gmail.com', 'Nguyen ChuTi', 'FEATURE_REQUEST',
 'Sinh Unit Test JUnit 5 cho Spring Service rất chuẩn',
 'Mô hình sinh test case bao phủ tốt các nhánh if-else và throw business exception. Đề xuất bổ sung mock cho Redis cache.',
 'RESOLVED', 'Đã ghi nhận, đã tích hợp vào catalog prompt Spring Boot.', NOW() - INTERVAL '2 days', NOW() - INTERVAL '1 day', 'Project #1133'),

(2, (SELECT id FROM auth_user WHERE email = 'test_dev@greytest.com'), 'test_dev@greytest.com', 'Test Dev', 'COVERAGE_TESTPLAN',
 'Test Case đôi khi bị thiếu trường hợp boundary value cho BigDecimal',
 'Khi sinh test plan cho hàm tính lãi suất, các giá trị 0 hoặc âm chưa được generate đầy đủ test case biên.',
 'IN_PROGRESS', 'Đang tinh chỉnh prompt sinh test case với kỹ thuật Boundary Value Analysis.', NOW() - INTERVAL '1 day', NULL, 'PaymentService'),

(3, (SELECT id FROM auth_user WHERE email = 'abcxyz@gmail.com'), 'abcxyz@gmail.com', 'Anh Quynh', 'ACCOUNT_QUOTA',
 'Hạn mức Quota 100 lượt hơi nhanh hết khi test dự án lớn',
 'Dự án có hơn 40 class service nên chạy 2-3 lần là chạm mốc quota tháng. Mong sớm có gói mở rộng sinh viên.',
 'NEW', '', NOW() - INTERVAL '8 hours', NULL, 'EcommerceStore'),

(4, (SELECT id FROM auth_user WHERE email = 'testuser@gmail.com'), 'testuser@gmail.com', 'Test User', 'BUG_GENERATION',
 'Lỗi biên dịch khi test service có generic type lồng nhau',
 'Khi mock Repository có Interface extends JpaRepository<Entity<T>, ID>, import javax bị xung đột với jakarta.',
 'RESOLVED', 'Đã cập nhật AST Parser v1.2 sửa lỗi nhận diện Spring Boot 3 Jakarta package.', NOW() - INTERVAL '3 days', NOW() - INTERVAL '2 days', 'OrderRepository')
ON CONFLICT (id) DO NOTHING;

SELECT setval('user_feedback_id_seq', (SELECT COALESCE(MAX(id), 1) FROM user_feedback));

