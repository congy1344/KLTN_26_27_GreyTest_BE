-- Đưa mọi gói về chu kỳ tháng để dễ so sánh; gói cao hơn rẻ hơn trên mỗi lượt.
-- Chi phí API ước tính khoảng 40đ/lượt (gpt-6-luna, ~14 nghìn token/lượt): PLUS ~67đ/lượt, PRO ~53đ/lượt.
UPDATE tier_package SET quota_limit = 150, cycle = 'MONTHLY', reset_cycle = 'MONTHLY',
    features = 'Trích xuất Quy tắc Nghiệp vụ (AST)
Sinh Test Plan & Test Case
Sinh Unit Test JUnit 5 cơ bản
150 lượt gọi AI mỗi tháng', updated_at = CURRENT_TIMESTAMP
WHERE code = 'FREE';
UPDATE tier_package SET price = 40000, quota_limit = 600, cycle = 'MONTHLY', reset_cycle = 'MONTHLY',
    features = 'Toàn bộ tính năng gói FREE
600 lượt gọi AI mỗi tháng
Sinh tối đa 4 batch song song
Ưu tiên xử lý hàng đợi tác vụ', updated_at = CURRENT_TIMESTAMP
WHERE code = 'PLUS';
UPDATE tier_package SET price = 80000, quota_limit = 1500, cycle = 'MONTHLY', reset_cycle = 'MONTHLY',
    features = 'Toàn bộ tính năng gói PLUS
1.500 lượt gọi AI mỗi tháng
Phân tích cú pháp AST & Call Graph sâu
Sinh song song tối đa 8 batch
Vòng lặp JaCoCo Refinement tự cải thiện độ bao phủ', updated_at = CURRENT_TIMESTAMP
WHERE code = 'PRO';

-- Người dùng đang ở hạn mức mặc định cũ thì nhận hạn mức mới; hạn mức admin đã chỉnh riêng giữ nguyên.
UPDATE usage_quota q SET quota_limit = 150 FROM auth_user u
WHERE u.id = q.user_id AND u.tier = 'FREE' AND q.quota_limit = 100;
UPDATE usage_quota q SET quota_limit = 600 FROM auth_user u
WHERE u.id = q.user_id AND u.tier = 'PLUS' AND q.quota_limit = 200;
UPDATE usage_quota q SET quota_limit = 1500 FROM auth_user u
WHERE u.id = q.user_id AND u.tier = 'PRO' AND q.quota_limit = 300;
