-- Xóa toàn bộ dữ liệu mock ban đầu trong bảng user_feedback, chỉ giữ lại các phản hồi thật do người dùng gửi
DELETE FROM user_feedback WHERE id IN (1, 2, 3, 4);

