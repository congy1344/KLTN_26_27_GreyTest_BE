# Thống nhất tiếng Việt có dấu trong nội dung AI và thông báo

## Nguyên nhân

Ảnh người dùng cung cấp có BR viết không dấu trong khi các nhãn giao diện có dấu. Có hai nguồn không thống nhất:

- `AIAgentService.languageInstruction` trước đây hướng dẫn tiếng Việt bằng chính câu không dấu và không yêu cầu rõ chính tả có dấu. Nội dung do model trả về vì thế có thể viết không dấu.
- Nhiều thông báo validation, review, tiến độ, retry và lỗi trong backend được viết sẵn không dấu. FE hiển thị trực tiếp các câu này nên lẫn với nhãn có dấu.

Không phát hiện bước bỏ dấu trong luồng render nội dung. Prompt/context/response log và ZIP đã được đọc/ghi UTF-8; runner Windows đã có `chcp 65001` trước các lệnh echo. Không cần thêm bước tự đoán dấu ở FE hoặc đổi dữ liệu nghiệp vụ.

## Thay đổi

- Hướng dẫn ngôn ngữ chung yêu cầu tiếng Việt có dấu, đúng chính tả cho nội dung BR, TP, TC, UT, review và sinh tăng dần; dùng ví dụ có dấu và nhắc không bắt chước mô tả context không dấu.
- Giữ nguyên nhánh English, technical identifiers, JSON keys, enum values, test data, chuỗi literal và exception message được trích từ source. Chỉ chuẩn hóa phần diễn giải tự nhiên.
- Chuẩn hóa các thông báo validation/authorization, phân tích source, sinh artifact, review, retry, provider, export, echo coverage và README xuất trong ZIP.
- Giữ nhận diện lỗi JSON cả dạng có dấu và dạng không dấu cũ để không làm mất cơ chế correction/retry. Mã lỗi và các token kỹ thuật dùng để phân loại không thay đổi.
- Cập nhật kỳ vọng thông báo trong test; không nới điều kiện pass hoặc thay đổi scenario.

## Kiểm chứng

Test hợp đồng ngôn ngữ đã fail trước khi sửa prompt, sau đó yêu cầu rõ tiếng Việt có dấu và bảo toàn source literal/test data. Các test hiện có kiểm tra retry JSON, lỗi provider, review/validation và chạy script coverage với đường dẫn Unicode.

Đối chiếu snapshot trước lượt sửa bằng JavaParser: **35 production Java files giữ nguyên cấu trúc AST sau khi chuẩn hóa text literals**, ngoại trừ hai method chủ đích đã được review là `languageInstruction` và `isMalformedJson`. Quét lại các cụm tiếng Việt không dấu trong chuỗi thông báo chỉ còn legacy matcher có chủ đích ở `isMalformedJson`.

```powershell
.\mvnw.cmd -q '-Dtest=*Test,!*IntegrationTest' test
```

Kết quả cuối của tập hồi quy: **88 suites, 973 test cases, 0 failures, 0 errors, 1 skipped** (972 pass). Lượt chạy toàn tập còn hai kỳ vọng chuỗi cũ ở UnitTestFileServiceTest và JavaParserHelperTest; sau khi đổi đúng thông báo có dấu, hai suite này được chạy lại thành công bằng lệnh dưới. Số liệu tổng hợp từ báo cáo Surefire mới nhất của đúng 88 suite; không khẳng định lần chạy toàn tập trước đó đã pass khi còn hai kỳ vọng cũ.

```powershell
.\mvnw.cmd -q '-Dtest=UnitTestFileServiceTest,JavaParserHelperTest' test
```

Reviewer chỉ đọc không còn phát hiện cần sửa ở phiên bản cuối. `git diff --check` pass. Không chạy lại toàn tập sau thay đổi chỉ ở hai kỳ vọng test; production code giữ nguyên so với lượt chạy toàn tập cuối.

## Giới hạn và dùng bản sửa

- Cần rebuild/restart backend để nạp prompt/thông báo mới; tải ZIP mới nếu cần runner/README mới.
- BR/TP/TC đã lưu và log lịch sử giữ nguyên nội dung cũ. Không có bulk update DB hoặc tự khôi phục dấu cho nội dung đã approve.
- Prompt guidance không phải chứng minh model luôn tuân thủ. Chưa gọi LLM thật để sinh lại BR-155/BR-156; hai câu trong ảnh cần chỉnh nội dung qua UI hoặc sinh lại đúng phạm vi và review.
- Bộ integration dùng DB local được loại khỏi lần xác nhận cuối. Lượt thử toàn suite ban đầu đã dừng; DataLayerIntegrationTest đã chạy là transactional/rollback, Flyway báo schema hiện tại không cần migration.
