-- Hai khoá cấu hình chưa từng được hệ thống đọc tới; bỏ để trang Cài đặt chỉ còn những gì thực sự có tác dụng.
DELETE FROM system_setting WHERE setting_key IN ('log.output_dir', 'alert.quota_threshold_percent');
