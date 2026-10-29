# Tăng tốc sinh AI — 22/10/2026

Thời gian chờ LLM là phần chậm chính trong các batch được ghi log. Hệ thống đã có executor chạy song song, cache context theo job và lưu kết quả từng batch; lần này chỉ điều chỉnh mức song song và cách biểu diễn JSON gửi AI.

## Thay đổi

- Cấu hình `.env` cục bộ: `GREYTEST_LLM_BATCH_CONCURRENCY=4`, trước đó là `2`. Giá trị mặc định trong cấu hình dùng chung vẫn là `2`; `.env.example` giải thích cách chọn mức phù hợp với quota provider.
- `PromptManager` gửi JSON context không có indentation, bằng một `ObjectWriter` riêng. Giữ nguyên nội dung source, Unicode, xuống dòng trong chuỗi, trường null, dữ liệu đầu vào và các ràng buộc prompt. Không thay đổi `ObjectMapper` dùng chung.
- Bổ sung kiểm tra context sau khi serialize và chạy executor ở cả mức 2/4, gồm giới hạn song song, thứ tự kết quả, callback lưu từng batch và context người dùng/ngôn ngữ.

## Kết quả kiểm tra

- **171 test liên quan: 0 failure, 0 error, 0 skipped**, Maven `BUILD SUCCESS`.
- Đọc lại 12 context Unit Test đã ghi log, dựng prompt bằng template thật và đối chiếu toàn bộ cây JSON: tổng dung lượng prompt giảm từ **1.400.568 xuống 1.288.765 byte, khoảng 8%**. Đây là số byte, không phải phép đo token hoặc thời gian gọi API.
- Mô phỏng 8 batch, mỗi batch chờ 100 ms: mức 1 mất **889 ms**, mức 2 mất **458 ms**, mức 4 mất **211 ms**. Số đo này chỉ xác nhận khả năng chạy đồng thời; không đại diện cho tốc độ provider thật.
- `git diff --check` thành công. Reviewer chỉ đọc không phát hiện vấn đề cần sửa trong phạm vi thay đổi.

Lệnh kiểm tra:

```powershell
.\mvnw.cmd -o '-Dtest=PromptManagerTest,AgentGatewayTest,GenerationContextBuilderTest,GenerationPerformanceTest,LlmBatchExecutorTest,IncrementalBusinessRuleBatchTest,TestabilityGenerationGatewayTest,MultiSourceGenerationRegressionTest,UnitTestServiceTest,GeneratedNestedEnumRegressionTest,GeneratedUnusedStubRegressionTest' test
```

Bằng chứng nằm trong `.greytest-generation-verification/generation-speed/` ở workspace: `targeted-final.log`, `prompt-payload.log` và `MeasurePromptPayload.java`.

## Áp dụng

Chưa rebuild hoặc khởi động lại backend đang chạy. Sau khi job hiện tại kết thúc, chạy trong thư mục backend:

```powershell
docker compose up -d --build backend
```

Chưa gọi API tính phí để benchmark. Tốc độ thực tế phụ thuộc độ trễ và giới hạn request/token của provider; nếu mức 4 gặp giới hạn request thì giảm cấu hình về 2.
