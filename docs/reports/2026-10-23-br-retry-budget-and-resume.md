# Giới hạn retry và tiếp tục sinh BR

Lượt BR project 825 trước sửa có 4 batch và 11 lần thử provider, gồm 7 lần lỗi 504/EOF; phản hồi cuối sau khoảng 9 phút 44 giây. Hai tầng retry có thể nhân tối đa 2 × 3 lượt gọi khi lỗi transport. Ngoài ra, nhánh backfill ở trạng thái chờ duyệt có thể chọn lại method đã hoàn tất khi người dùng muốn tiếp tục.

## Thay đổi

`GenerationJobContext.withLlmCallBudget(3, ...)` giữ ngân sách riêng theo worker/batch qua retry và correction. `LlmBatchExecutor` mở scope trước vòng retry, kể cả fallback tuần tự; BR tăng dần mở scope cho mỗi batch trực tiếp. Agent chỉ tiêu ngân sách cho prompt `business-rule` ngay trước `llmClient.complete`, sau kiểm tra cache. Hết ngân sách thì không retry hoặc chờ thêm. Mỗi batch BR tối đa 3 lượt complete ở tầng agent; việc xoay key bên trong provider client là cơ chế riêng. TP/TC/UT giữ cơ chế retry/recovery hiện có.

API sinh BR thêm query `resume`, mặc định true để caller cũ tiếp tục phần thiếu an toàn. `BusinessRuleService` dùng `needsGeneration` cho chế độ này ngay cả khi project đang BR_PENDING_REVIEW. Chỉ `resume=false` mới chọn lại method trong scope và cập nhật BR AI chưa sửa; các BR thêm/sửa thủ công được giữ nguyên. Kiểm tra method/decision và loại trùng vẫn chạy trước khi lưu.

Frontend truyền cờ từ hook đến API, phân biệt AI sinh BR/ Tiếp tục sinh với Sinh lại BR. Sinh lại mở hộp xác nhận và cảnh báo quota; bỏ xác nhận không gửi request sinh lại. Đã cập nhật phần retry, luồng BR và mốc breakpoint trong `KLTN_Docs/DEBUG_FLOW_GUIDE.md`.

## Kiểm chứng

- Test trước sửa tái hiện 6 lượt complete thay vì 3 khi 504/EOF, các lần correction vượt ngân sách và tiếp tục chờ duyệt ghi lại method đã có BR.
- **144 test backend pass** trong 9 suite liên quan, gồm controller/auth/mode, gateway, budget độc lập giữa batch song song, BR resume/regenerate, giữ sửa thủ công và sinh tăng dần.
- **35 test frontend pass**: panel, request mode và hồi quy dấu cập nhật source.
- TypeScript/Vite build thành công; còn cảnh báo bundle trên 500 kB. `git diff --check` pass cho hai repository.
- Reviewer chỉ đọc kiểm tra bản cuối, không còn phát hiện cần sửa.
- Vite localhost:3000 phục vụ giao diện mới; chưa rebuild/restart backend đang chạy. Không gọi LLM thật hoặc đổi dữ liệu tài khoản/project trong các test này, chưa đo tốc độ thực tế sau sửa.

Giới hạn retry giúp tránh chờ và gọi lặp quá nhiều khi API lỗi. Nếu provider vẫn lỗi sau 3 lượt, batch dừng và báo lỗi; các batch đã lưu được giữ để người dùng tiếp tục. Đây không phải cam kết model sẽ trả lời nhanh hơn hoặc mọi lượt sinh đều hoàn tất trong một thời gian cố định.

```powershell
# Backend
.\mvnw.cmd '-Dtest=BusinessRuleServiceTest,BusinessRuleGenerationControllerTest,AgentGatewayTest,LlmBatchExecutorTest,LlmBatchTierConcurrencyTest,GenerationJobContextTest,GenerationJobServiceTest,IncrementalBusinessRuleBatchTest,IncrementalGenerationServiceTest' test
# Frontend
npm.cmd test -- src/features/business-rules/components/BusinessRulesPanel.test.tsx src/features/business-rules/api/business-rule-api.test.ts src/features/projects/components/SourceUpdateHighlights.test.tsx
npm.cmd run build
```
