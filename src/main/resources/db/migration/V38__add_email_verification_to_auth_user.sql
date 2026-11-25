-- Đăng ký bằng mật khẩu chưa chứng minh sở hữu email. Khi Google/GitHub xác nhận email lần đầu,
-- mật khẩu và phiên cũ của tài khoản bị hủy để người đăng ký trước không giữ được quyền truy cập.
ALTER TABLE auth_user ADD COLUMN IF NOT EXISTS email_verified BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE auth_user ADD COLUMN IF NOT EXISTS sessions_valid_from TIMESTAMP;
