# Multi-source generation regression implementation plan

**Goal:** Kiểm chứng GreyTest với 6 source / 12 module có test trên máy, sửa lỗi chung và lưu regression có thể chạy lại.

**Architecture:** Giữ pipeline hiện tại. Dùng UnitTestFileService để xuất script đúng selector của từng source, chạy test/JaCoCo tại module, lưu kết quả có bằng chứng. Các lỗi generator được tái hiện qua fixture source tối thiểu trong test repository trước khi sửa.

**Tech Stack:** Java 17, JUnit 5, Mockito, JavaParser; source mẫu Maven/Gradle và Windows Unicode junction.

**Spec:** Yêu cầu kiểm thử nhiều source trong hội thoại; phạm vi EVALUATION.md và CODING_RULES.md.

## Constraints

- Không tạo production class giả, không thay business behavior để ép test xanh.
- Giữ Mockito strict stubbing, package, import và các artifact DB hiện có.
- Mỗi kết quả phải có exit code, log, số tests và JaCoCo XML mới của chính lần chạy.
- Tách lỗi generator/test khỏi môi trường hoặc dependency thiếu; không ghi PASS cho source chưa chạy được.
- Không commit hoặc thêm dependency ngoài yêu cầu.

## Task 1: Inventory and runtime matrix

- [x] Kiểm kê script, build file, source/test của 12 module.
- [x] Xuất script từ UnitTestFileService hiện tại, giữ nguyên selector và sao lưu script cũ.
- [x] Chạy coverage từng module với giới hạn thời gian và lưu stdout/stderr riêng.
- [x] Đọc test result + JaCoCo XML mới; tạo matrix JSON/Markdown từ số liệu thực tế.

## Task 2: General regression coverage

**Files:** `src/test/java/com/greytest/service/agent/MultiSourceGenerationRegressionTest.java`, fixture dưới `src/test/resources/fixtures/multi-source/`; chỉ sửa validator/context/export khi có lỗi tái hiện.

- [x] Chọn khai báo source thực tế đại diện cho các kiểu DTO, constructor, generic, import và phiên bản test khác nhau.
- [x] Tái hiện lỗi thực tế của HMS; xác nhận validator hiện tại đã từ chối API sai, không cần đổi production.
- [x] Kiểm tra rằng source hợp lệ không bị từ chối nhầm và source sai nhận được thông báo chính xác.

## Task 3: Final verification

- [x] Chạy toàn bộ backend tests sau sửa.
- [x] Chạy lại các module bị ảnh hưởng và xác nhận exit/report mới.
- [x] Reviewer chỉ đọc theo `.codex/agents/reviewer.toml`, xử lý finding correctness.
- [x] Lưu báo cáo có source path, build version, trạng thái, test/coverage và giới hạn kiểm chứng.
