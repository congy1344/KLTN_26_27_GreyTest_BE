# GreyTest — Việc cần nhớ trên AWS

Ghi ngày 24/11/2026, đối chiếu từ file trong repo; **chưa kiểm tra trực tiếp trên máy EC2**.
Luận văn (mục 4.2, 4.5) và `greytest.drawio` đã trình bày sandbox và VNPay như đã có, nên cần làm xong trước khi demo.

## 1. Sandbox tự chạy sau khi sinh Unit Test

- [ ] Thực hiện [aws-server-upgrade.md](aws-server-upgrade.md): đổi sang `m7i-flex.large`, ổ 30 GiB, copy `docker-compose.aws.yml` mới lên EC2, đặt `DOCKER_GID`. File này ghi trạng thái "chưa thực hiện" (16/11/2026).
- [ ] `GREYTEST_LLM_BATCH_CONCURRENCY`: compose mặc định 1; tài liệu trên ghi 4 (theo PLUS=3, PRO=4). Gói hiện tại là FREE=2, PLUS=4, PRO=8 (migration V39), nên cần 8 nếu muốn PRO chạy đủ mức.
- [ ] Bước kiểm tra "bấm chạy test" trong tài liệu trên đã cũ: giờ sinh xong Unit Test thì hệ thống tự chạy.
- [ ] Deploy bản frontend đã ẩn nút "Chạy test" (thay đổi ở `CoveragePanel.tsx`, chưa commit).
- [ ] Quyết định có bỏ API `POST /api/projects/{id}/coverage/run` không; giao diện không còn gọi nhưng API vẫn mở.
- [ ] `docker.sock` cho backend quyền điều khiển Docker của máy chủ: chỉ cấp tài khoản cho người tin cậy.

## 2. VNPay

- [ ] Bỏ khối chặn `@payment` trong `frontend/Caddyfile` (đang trả 503 `PAYMENT_DISABLED`).
- [ ] Sửa `smoke-aws.py` và mục 1 của `aws-runbook.md`: đang coi "payment bị chặn" là đúng.
- [ ] Truyền vào service `backend` của compose: `VNPAY_TMN_CODE`, `VNPAY_HASH_SECRET`, `VNPAY_PAY_URL`, `VNPAY_RETURN_URL` (`https://<tên miền>/payment/vnpay-return`).
- [ ] Không dùng khóa bí mật mặc định đang ghi cứng trong `VnpayService.java` / `application.yml`; đặt khóa riêng.
- [ ] Chưa có IPN: người dùng đóng trình duyệt trước khi quay về thì giao dịch giữ trạng thái PENDING.

## 3. Email đặt lại mật khẩu (SMTP)

- [ ] Truyền vào compose: `SPRING_MAIL_HOST`, `SPRING_MAIL_PORT`, `SPRING_MAIL_USERNAME`, `SPRING_MAIL_PASSWORD`.
- [ ] Truyền `GREYTEST_FRONTEND_URL`; thiếu biến này thì đường dẫn trong thư trỏ về `http://localhost:3000`.
- [ ] Sau khi bật, thêm SMTP vào sơ đồ triển khai.

## 4. LLM

- [ ] Kiểm tra `LLM_OPENAI_URL` trên EC2. Mặc định trong compose là dịch vụ trung gian `api.vietapi.tech`, tài liệu nâng cấp ghi `api.openai.com`.
- [ ] `LLM_PROVIDER` mặc định là `mock`; phải đặt `openai` khi demo.

Không ghi giá trị khóa hay mật khẩu vào file này.
