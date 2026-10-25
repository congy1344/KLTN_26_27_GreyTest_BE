# Testability-Aware Test Generation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Sinh test theo khả năng điều khiển/quan sát của source, báo sớm giới hạn và giữ kết quả hợp lệ khi một số case bị chặn.

**Architecture:** Thêm `TestFeasibilityAnalyzer` thuần JavaParser và `GenerationValidationGate` điều phối validator hiện có. Báo cáo có method/case ID, trạng thái READY/BLOCKED/UNKNOWN, bằng chứng source và chiến lược fixture; prompt BR/Plan/Case/UT dùng báo cáo này. UT chỉ gửi case chưa bị chứng minh là blocked cho LLM; blocked metadata do backend tạo, không nhận từ LLM. Trạng thái progress PARTIAL thể hiện kết quả chưa đầy đủ.

**Tech Stack:** Java 17, JavaParser hiện có, JUnit/Mockito, Spring Boot, React/TypeScript hiện có. Không thêm dependency.

**Spec:** Thiết kế đã trao đổi trong hội thoại: hai thành phần nội bộ, đánh giá theo scenario, giữ BR/HITL, không giả định kiểm tra tĩnh là chạy test thành công.

## Global Constraints

- Không sửa source mẫu hoặc thay đổi kỳ vọng đã duyệt; không đổi database schema.
- Không gọi LLM bổ sung chỉ để đánh giá feasibility; không thực thi project người dùng trong backend.
- Chỉ BLOCKED khi có bằng chứng ràng buộc scenario với nhánh không điều khiển được; thiếu bằng chứng là UNKNOWN.
- Một method có random không tự động khiến mọi case bị chặn. Mẫu chưa hỗ trợ phải được ghi rõ.
- Tái sử dụng API/getter, boolean assertion, fixture mutation, framework và fingerprint checks.
- Không loại blocked case khỏi số yêu cầu/coverage hoặc báo toàn bộ project đã sinh xong.
- Giữ nguyên các thay đổi đang có trong workspace; không tạo commit hay restart container.

## Task 1: Analyzer và chiến lược dựng fixture

- [x] Viết regression cho Math.random trực tiếp, random có dependency điều khiển được, thiếu source, dependency final tạo nội bộ, collection mutation, scenario không yêu cầu ép nhánh và source/helper không liên quan.
- [x] Chạy red rồi triển khai `TestFeasibilityAnalyzer` với báo cáo có bằng chứng. Đọc đúng selected methods; hỗ trợ helper trong giới hạn có thể phân giải chắc chắn.
- [x] Chạy green và giữ phạm vi pattern được hỗ trợ rõ ràng.

## Task 2: Validation Gate và prompt pipeline

- [x] Viết regression gateway: blocked trước LLM, mixed case chỉ sinh phần khả thi, output cache vẫn qua gate, mock setup/API/assertion cũ vẫn bị kiểm tra.
- [x] Thêm `GenerationValidationGate`, đưa báo cáo vào prompt và progress. Đánh dấu cảnh báo trong artifact Plan/Case mới trước review, giữ nội dung nghiệp vụ.
- [x] Tách blocked metadata backend khỏi response LLM; giữ API constructor cũ.
- [x] Snapshot/source-update không được coi output thiếu vì blocked là đã phủ đầy đủ.

## Task 3: Kết quả một phần và giao diện tiến trình

- [x] Viết regression service cho mixed/all-blocked, không recovery/retry blocked case, giữ valid output, không TEST_GENERATED/100% thành công khi còn thiếu.
- [x] UnitTestService tiếp nhận blocked metadata, tiếp tục batch khác và kết thúc progress PARTIAL với lý do cụ thể. Supplemental generation dùng cùng nguyên tắc.
- [x] Thêm PARTIAL vào contract tiến trình và FE hiển thị tương ứng; không thêm bảng hay trạng thái business artifact.

## Task 4: Verification và review

- [x] Chạy targeted backend và FE checks, sau đó suite phù hợp môi trường. Phân biệt test lỗi môi trường với regression.
- [x] Reviewer chỉ đọc xem correctness/security/regression/coverage; xử lý phát hiện trước khi hoàn tất.
- [x] Ghi báo cáo giới hạn, kết quả thực nghiệm regression và hướng dẫn rebuild/sinh lại. Không tuyên bố mọi source đều sinh được UT hoặc mọi test đã thực thi.
