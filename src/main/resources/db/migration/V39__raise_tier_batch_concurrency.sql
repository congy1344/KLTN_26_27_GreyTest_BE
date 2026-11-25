-- Thực nghiệm gpt-6-luna: 8 batch song song giảm thời gian chờ AI rõ rệt mà không đổi chất lượng.
-- FREE giữ 2, PLUS 4, PRO 8; trần toàn hệ thống nâng lên 8. Chỉ đổi giá trị mặc định cũ, giữ giá trị admin đã chỉnh.
UPDATE tier_package SET batch_concurrency = 4,
    features = REPLACE(features, 'Sinh tối đa 3 batch song song', 'Sinh tối đa 4 batch song song')
WHERE code = 'PLUS' AND batch_concurrency = 3;
UPDATE tier_package SET batch_concurrency = 8,
    features = REPLACE(features, 'Sinh Unit Test JUnit 5 song song 4 luồng', 'Sinh song song tối đa 8 batch')
WHERE code = 'PRO' AND batch_concurrency = 4;
UPDATE system_setting SET setting_value = '8' WHERE setting_key = 'ai.batch_concurrency' AND setting_value = '4';
