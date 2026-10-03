ALTER TABLE usage_quota ALTER COLUMN quota_limit DROP NOT NULL;
UPDATE usage_quota SET quota_limit = NULL WHERE quota_limit = 999999;
