-- Thêm cột reset_cycle (chu kỳ làm mới Quota) tách biệt với cycle (chu kỳ gia hạn thanh toán)
ALTER TABLE tier_package ADD COLUMN IF NOT EXISTS reset_cycle VARCHAR(20) NOT NULL DEFAULT 'WEEKLY';

-- Cập nhật dữ liệu mặc định chuẩn cho các gói tiêu chuẩn hiện có
UPDATE tier_package SET reset_cycle = 'MONTHLY' WHERE code = 'FREE' AND (reset_cycle IS NULL OR reset_cycle = 'WEEKLY');
UPDATE tier_package SET reset_cycle = 'WEEKLY' WHERE code = 'PLUS' AND (reset_cycle IS NULL);
UPDATE tier_package SET reset_cycle = 'WEEKLY' WHERE code = 'PRO' AND (reset_cycle IS NULL);
UPDATE tier_package SET reset_cycle = 'WEEKLY' WHERE reset_cycle IS NULL;
