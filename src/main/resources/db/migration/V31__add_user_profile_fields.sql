-- Thêm các trường thông tin bổ sung cho hồ sơ người dùng: số điện thoại, giới tính, ngày sinh
ALTER TABLE auth_user ADD COLUMN IF NOT EXISTS phone_number VARCHAR(20);
ALTER TABLE auth_user ADD COLUMN IF NOT EXISTS gender VARCHAR(10);
ALTER TABLE auth_user ADD COLUMN IF NOT EXISTS date_of_birth DATE;

-- Đồng bộ chu kỳ gói PRO là WEEKLY (hàng tuần) theo đúng thiết kế và giá gói 20.000đ/tuần
UPDATE tier_package SET cycle = 'WEEKLY' WHERE code = 'PRO';
