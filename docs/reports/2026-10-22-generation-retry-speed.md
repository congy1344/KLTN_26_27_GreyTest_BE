# Giảm gọi AI lặp khi output lỗi — 22/10/2026

Người dùng chọn giữ nguyên model, chất lượng đầu ra và toàn bộ kiểm tra hiện tại khi tối ưu tốc độ.

## Nguyên nhân

`AIAgentService` đã có vòng sửa output: tối đa 2 lần gọi đối với lỗi semantic thông thường, 3 lần đối với JSON hỏng. Sau khi hết lượt, exception vẫn có `retryable=true`. `LlmBatchExecutor` có thể gọi lại worker thêm hai lần, khiến vòng sửa output bắt đầu lại từ prompt gốc.

Test tích hợp với agent, parser và validator thật, client giả lập, đã tái hiện trước khi sửa:

- Unit Test sai exception liên tục: **6 lần gọi** client thay vì giới hạn 2 của agent.
- JSON không hợp lệ liên tục: **9 lần gọi** client thay vì giới hạn 3 của agent.

## Thay đổi

Agent phân biệt giai đoạn gọi provider với giai đoạn parse/validate phản hồi. Chỉ khi lỗi xảy ra trong giai đoạn kiểm tra output và đã hết lượt sửa, agent trả lỗi không cho tầng batch thử lại. Thông báo và exception gốc được giữ lại qua cause.

Không thay đổi model, prompt, source context, token limit, số lượt tự sửa bên trong agent, các bước validation hoặc điều kiện lưu kết quả. Lỗi provider tạm thời vẫn giữ cơ chế retry ở tầng agent và tầng batch.

## Kiểm chứng

- RED: 2 test hồi quy đều thất bại đúng vì client bị gọi 6/9 lần.
- GREEN: hai trường hợp trên chỉ gọi **2/3 lần**; phản hồi sai vẫn bị từ chối. Lượt thứ hai vẫn có hướng dẫn sửa semantic; các lượt sửa JSON vẫn có hướng dẫn sửa JSON.
- Test provider HTTP 503 liên tục hai lần, thành công lần thứ ba: tầng batch vẫn phục hồi được.
- **215 test liên quan: 0 failure, 0 error, 0 skipped**, Maven `BUILD SUCCESS`. Bao gồm gateway, retry, batch executor, Test Plan, Test Case, Unit Test, validation, context, lưu batch và hồi quy nhiều source.
- `git diff --check` thành công. Reviewer chỉ đọc không phát hiện vấn đề cần sửa.
- Docker Compose kiểm tra cấu hình với exit code 0; CLI có cảnh báo không đọc được cấu hình Docker cá nhân. Chưa rebuild hoặc restart container.

Bằng chứng trong workspace: `.greytest-generation-verification/retry-speed/red.log`, `targeted.log`, `final.log` và bản trước sửa của hai file thay đổi.

## Giới hạn và áp dụng

Đây là giảm request bị nhân lên khi output lỗi liên tục, không phải số đo tăng tốc cho các batch thành công ngay lần đầu. Khi hết lượt sửa, batch báo lỗi sớm hơn; người dùng vẫn có thể tiếp tục sinh phần thiếu. Chưa gọi API tính phí để benchmark tốc độ thực tế.

Sau khi lượt sinh hiện tại kết thúc, chạy trong thư mục backend:

```powershell
docker compose up -d --build backend
```
