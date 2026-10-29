# Testability-Aware Test Generation — 2026-10-22

## Hành vi đã tích hợp

- `TestFeasibilityAnalyzer` phân tích selected method và scenario bằng JavaParser, trả về READY/BLOCKED/UNKNOWN cùng bằng chứng source và chiến lược fixture. Không gọi LLM hoặc chạy source người dùng.
- `PromptManager` đưa báo cáo vào các prompt BR/review BR/Plan/Case/UT. Rủi ro của method không tự động làm mọi case bị chặn.
- `GenerationValidationGate` kiểm tra tham chiếu method/rule/plan, tái sử dụng kiểm tra mock/API/assertion/framework/fixture hiện có; cả response live và cache đều đi qua gate. Gate cũng kiểm tra class/package/path khi xuất ZIP sau khi gộp file.
- Plan/Case mới nhận ghi chú trước review; expected result và BR không bị sửa để ép test chạy đạt. Ghi chú được loại khỏi đầu vào đánh giá scenario để không tự gây chặn nhầm.
- UT có scenario được chứng minh BLOCKED được tách trước gọi AI. Metadata blocked do backend tạo, JSON LLM không được tự khai báo bỏ case. Batch độc lập tiếp tục; blocked case không đi vào recovery/retry gọi AI.
- Backend/FE có trạng thái tiến trình PARTIAL, không báo hoàn tất 100% hoặc giữ TEST_GENERATED khi còn thiếu UT. Frontend dừng polling và mở khóa thao tác sau kết quả một phần.
- Cập nhật source giữ draft UT hợp lệ nhưng chuyển FAILED nếu còn blocked case, kể cả lỗi bị bọc hoặc batch sau thất bại. Apply từ chối trạng thái này trước khi thay đổi source chính.

## Phạm vi bằng chứng

MVP nhận diện nhánh `Math.random()` trực tiếp và chỉ BLOCKED khi scenario yêu cầu điều khiển giá trị/nhánh rõ ràng. Ghi nhận hướng dựng fixture cho Random/DoubleSupplier/Clock được truyền vào, collaborator final được tạo nội bộ và collection mutation. Câu chỉ mô tả source, câu phủ định, ghi chú backend hoặc method/helper không liên quan không được dùng để tự kết luận BLOCKED.

Fixture từ log project 789 kiểm tra năm scenario TC-1154 đến TC-1158 có yêu cầu ép nhánh random. Regression còn kiểm tra metadata round trip từ Case qua review đến UT: ghi chú UNKNOWN không tự biến thành BLOCKED; điều kiện chỉ nằm trong testData không bị mất khi loại ghi chú.

## Kiểm chứng

- Regression tích hợp analyzer/gate/cache/partial/snapshot: **126 tests, 0 failures, 0 errors**.
- Toàn bộ backend: **905 tests, 0 failures, 0 errors, 1 skipped; BUILD SUCCESS**. PostgreSQL sẵn sàng và các integration test đã chạy trong lượt này.
- Toàn bộ frontend: **169 tests đạt, 45 test files đạt**. Frontend production build cũng đã được kiểm tra trong phần triển khai progress.
- Reviewer chỉ đọc đã hoàn tất, không còn phát hiện cần sửa trong phạm vi thay đổi cuối.
- Log backend: `../.greytest-generation-verification/testability-full-suite.log`; log frontend: `../.greytest-generation-verification/testability-frontend-full-suite.log`.

## Giới hạn và áp dụng

READY là chưa nhận diện blocker trong phạm vi hỗ trợ, không chứng minh UT đã biên dịch/chạy hoặc mọi nhánh đều điều khiển được. Helper/path chưa phân giải, thiếu source và yếu tố thời gian chưa điều khiển được có thể là UNKNOWN; analyzer không phải bộ symbolic execution toàn bộ Java.

Không thêm dependency hay đổi database schema. Báo cáo nằm trong prompt/progress và ghi chú artifact mới; không thêm bảng lưu toàn bộ lịch sử feasibility. Giới hạn log tiến trình và kiểm tra quota trước xếp hàng hiện có được giữ nguyên.

Candidate cập nhật source ở FAILED vì blocked case chưa được tự resume theo từng case trong bản này. Cần xử lý điều kiện bị chặn và chuẩn bị candidate hợp lệ trước Apply; draft hợp lệ được giữ để đối chiếu.

Chưa rebuild/restart container hoặc gọi LLM thật để sinh lại dữ liệu dự án trong lượt triển khai này. Sau khi rebuild backend và cập nhật frontend, sinh lại trong phạm vi cần thiết để áp dụng analyzer/gate cho artifact mới. UT cũ không được tự sửa hoặc tự xác nhận đã chạy đạt.
