# Tối ưu thời gian sinh AI — 21/10/2026

Log thực tế cho thấy thời gian chủ yếu nằm ở chờ LLM: có request khoảng 30–210 giây. Parse 93 file Java chỉ mất khoảng 30–60 ms. Cấu hình chạy thực tế trước thay đổi là một batch tại một thời điểm; BR còn dùng vòng lặp tuần tự riêng.

## Thay đổi

- Mặc định chạy hai batch đồng thời cho bộ executor hiện có. BR sử dụng cùng executor với các bước sinh khác.
- BR vẫn lưu từng batch bằng transaction riêng. Callback lưu được đồng bộ để không trùng mã BR; tiến trình dùng chỉ số batch thực tế, kể cả khi batch sau hoàn thành trước batch trước. Khi tiếp tục sinh, bỏ qua đúng các batch đã lưu.
- Dữ liệu analysis, source snapshot và file build được tái sử dụng trong phạm vi một job, chia sẻ giữa các worker. Cache phân biệt project, đường dẫn snapshot và chế độ kiểm tra parse; tự bỏ khi job kết thúc. BR/Plan/Case đang thay đổi trong CSDL vẫn được đọc mới.
- Tắt in toàn bộ context lên console trong `.env` cục bộ, giữ file log context.

Prompt, lượng source liên quan, giới hạn token và các bước validation được giữ nguyên.

## Kiểm chứng

- Test trước sửa xác nhận analysis bị đọc hai lần trong một job; test sau sửa xác nhận chỉ đọc một lần và đọc mới ở job sau.
- Test BR xác nhận hai request cùng chạy, mã BR không trùng, giữ batch thành công khi batch khác lỗi, tiếp tục đúng phần thiếu và tạm dừng không gọi batch còn trong hàng đợi.
- Test cache xác nhận không trộn snapshot, không dùng kết quả parse active để bỏ qua kiểm tra snapshot nghiêm ngặt, và không cache artifact nghiệp vụ đang thay đổi.
- Benchmark giả lập 8 request, mỗi request chờ 100 ms: chạy tuần tự **873 ms**, hai batch đồng thời **431 ms**, giảm khoảng **51%**. Đây là số đo giả lập, chưa đo mức cải thiện với nhà cung cấp thật.
- Test liên quan: **129 test, không lỗi**.
- Toàn bộ backend: **816 test, 0 failure, 0 error, 1 skipped** (`mvnw.cmd test`).
- `docker compose config --quiet` và `git diff --check` thành công. Reviewer chỉ đọc không phát hiện vấn đề cần sửa.

## Áp dụng

Chưa rebuild hoặc khởi động lại container đang chạy. Sau khi tác vụ AI hiện tại kết thúc, chạy trong thư mục backend:

```powershell
docker compose up -d --build backend
```

Tốc độ thực tế phụ thuộc độ trễ và giới hạn request của nhà cung cấp. Có thể đặt `GREYTEST_LLM_BATCH_CONCURRENCY=1` nếu nhà cung cấp chỉ cho phép một request đồng thời.
