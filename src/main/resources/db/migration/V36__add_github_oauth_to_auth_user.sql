-- V36: Thêm trường liên kết tài khoản GitHub OAuth vào bảng auth_user
ALTER TABLE auth_user ADD COLUMN IF NOT EXISTS github_user_id VARCHAR(50);
ALTER TABLE auth_user ADD COLUMN IF NOT EXISTS github_username VARCHAR(100);
ALTER TABLE auth_user ADD COLUMN IF NOT EXISTS github_access_token TEXT;

CREATE UNIQUE INDEX IF NOT EXISTS idx_auth_user_github_user_id ON auth_user(github_user_id);

