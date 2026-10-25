# Sửa Apply kết quả sinh tăng dần

## Nguyên nhân đã xác minh

- Bản cập nhật #79 sinh nhiều BR cho một method nhưng chọn bản ghi theo method_id, làm các nhánh ghi đè cùng BR và mất SOURCE_BRANCH trong review_note.
- Apply tạo lại BR/Test Plan/Test Case đã được tạo để làm context giữa các bước, gây trùng ID logic và các liên kết thiếu ở các bảng bên dưới.
- Method mới giữ line_start=1, line_end=10 thay vì vị trí trong snapshot; thao tác sinh tiếp báo source anchor nằm ngoài method.
- Test Plan chưa lưu đầy đủ covered_rule_ids trong luồng tăng dần/Apply.

## Thay đổi

- Phân biệt BR theo method và nhánh source, giữ marker nhánh qua Apply.
- Dùng lại ID artifact đã sinh; lưu đầy đủ liên kết phủ BR của Test Plan.
- Đồng bộ source, signature và phạm vi dòng từ snapshot, phân biệt overload, từ chối Apply nếu thiếu target đã sinh.
- Flush trước khi tạo lại liên kết coverage để tránh unique constraint.
- Từ chối quảng bá snapshot có METHOD bị từ chối: không thể giữ source của method đó bằng cách chỉ đổi con trỏ sang cả snapshot mới.

## Kiểm chứng

- Toàn bộ backend: 601 test, 600 pass, 1 skip (PetClinic cần fixture ngoài); không có failure/error.
- Test PostgreSQL thật xác nhận coverage không bị trùng, Apply trực tiếp giữ overload, thiếu target phải lỗi, source bị từ chối không bị ghi đè; dữ liệu test rollback.
- Reviewer read-only kiểm tra lại code và script repair, không còn phát hiện cần sửa.
- Repair #79 đã sao lưu, chạy thử rollback, rồi mới commit: sửa metadata 2 class/5 method và marker 4 BR hiện có.
- Số quyết định source chưa được gắn BR trong hai class liên quan: 1 → 0; mọi ID BR/Plan/Case/Unit Test cũ giữ nguyên.

## Giới hạn

Không gọi AI để sinh lại artifact trong lần sửa này. Các bản ghi trùng đã tồn tại từ lần Apply cũ được giữ nguyên; code mới ngăn tạo thêm bản ghi trùng ở các lần Apply tiếp theo. Repair không tự suy diễn lại các BR statement đã bị ghi đè trong lịch sử.

Backup, script, dry run và kết quả đối chiếu ở `.greytest-generation-verification/apply-repair/` tại thư mục workspace gốc.
