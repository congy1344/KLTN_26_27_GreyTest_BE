# GreyTest — Nâng cấp server AWS (m7i-flex.large + Docker sandbox)

Ngày soạn: 16/11/2026. Region: **Sydney `ap-southeast-2`**. Installation đang dùng CI/CD (GitHub Actions → ECR → SSM).

**Trạng thái:** đây là kế hoạch, **chưa thực hiện**. Phần IV của bài báo (`outputs/paper/greytest-overleaf/sections/04-implementation-evaluation.tex`) mô tả cấu hình *sau khi* nâng cấp. Phải làm xong file này và chạy smoke test trước khi nộp hoặc demo, nếu không bài báo sẽ không khớp với server thật.

## 0. Vì sao cần nâng cấp

| Hiện tại | Vấn đề |
|---|---|
| `t3.small`, 2 vCPU / 2 GiB | Mỗi container sandbox được tạo với `--memory 2g --cpus 2` (`SandboxTestRunService.createContainer`). Riêng backend đã giới hạn 1152 MiB, chưa tính PostgreSQL và Caddy. 2 GiB không đủ để chạy test. |
| `docker-compose.aws.yml` không mount `/var/run/docker.sock` | Backend không tạo được container sandbox, nên không chạy test hay sửa lỗi kỹ thuật trên AWS. |
| `Dockerfile.aws` không có Docker CLI | Sandbox gọi lệnh `docker`; bản dev (`Dockerfile`) có, bản AWS thì không. |
| `GREYTEST_LLM_BATCH_CONCURRENCY=1` | Mức song song thực tế bằng `min(giá trị này, mức của gói)`, nên mọi gói chỉ chạy được 1 batch. |

**AWS Free plan có nâng được không?** Có. Tài khoản tạo từ 15/07/2025 được đánh dấu *Free tier eligible* cho `t3.micro`, `t3.small`, `t4g.micro`, `t4g.small`, `c7i-flex.large` và `m7i-flex.large` ([AWS docs](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/ec2-free-tier-usage.html)). Chọn **`m7i-flex.large` (2 vCPU, 8 GiB)**. Lưu ý: "eligible" chỉ có nghĩa là được dùng trong Free plan, giờ chạy vẫn **trừ vào credit**. Instance lớn hơn trừ credit nhanh hơn, nên hãy kiểm tra Billing → Credits và Budget trước.

## 1. Backup trước khi làm

Làm theo [aws-ci-recovery.md](aws-ci-recovery.md), phần backup. Đưa một bản backup ra ngoài EC2. Tắt `AWS_DEPLOY_ENABLED` trong GitHub (Settings → Variables) trong lúc nâng cấp, để CI không deploy chen vào.

## 2. Đổi instance type → m7i-flex.large

1. AWS Console → EC2 (Sydney) → Instances → chọn instance GreyTest.
2. **Instance state → Stop instance**, chờ trạng thái `stopped`.
3. **Actions → Instance settings → Change instance type** → `m7i-flex.large`. Nếu Console không hiện nhãn *Free tier eligible* thì dừng lại và kiểm tra plan.
4. **Instance state → Start instance.** Elastic IP vẫn gắn nên domain không đổi.
5. Mục "T3 Unlimited/Standard" trong hướng dẫn cũ không áp dụng cho m7i-flex.

## 3. Tăng ổ đĩa 20 → 30 GiB

Image Maven/JDK cho sandbox (~0,5 GB mỗi phiên bản Java) và Maven cache (`greytest_sandbox_m2`) sẽ chiếm thêm đĩa.

1. EC2 → Volumes → chọn volume gốc → **Modify volume** → 30 GiB, gp3.
2. Session Manager:

```bash
lsblk                                    # xem tên ổ gốc, thường là nvme0n1 / nvme0n1p1
sudo growpart /dev/nvme0n1 1
sudo resize2fs /dev/nvme0n1p1
df -h /
```

## 4. Bật Docker sandbox

### 4.1 Image backend (đi qua CI/CD)

Thêm vào stage chạy của `Dockerfile.aws`, trước dòng `USER 10001:10001`:

```dockerfile
# Docker CLI để backend tạo container sandbox chạy Unit Test (cần mount /var/run/docker.sock).
COPY --from=docker:27-cli /usr/local/bin/docker /usr/local/bin/docker
```

Commit và push lên main; CI sẽ build image mới lên ECR. Chưa bật deploy lại (bước 1) cho tới khi xong mục 4.2.

### 4.2 Compose (phải sửa tay trên EC2; CI chỉ thay image)

Trong `docker-compose.aws.yml`, service `backend`:

```yaml
    group_add:
      - "${DOCKER_GID:?Set DOCKER_GID to the host docker group id}"
    volumes:
      - backend_storage:/app/storage
      - backend_logs:/app/log
      # Backend tạo container sandbox chạy Unit Test. Socket này cho backend quyền điều khiển Docker của máy chủ.
      - /var/run/docker.sock:/var/run/docker.sock
    mem_limit: 2048m
```

Sửa trong repo trước, sau đó copy lên `/opt/greytest/backend/docker-compose.aws.yml`.

`group_add` là bắt buộc. Backend chạy bằng UID 10001, không phải root, nên phải thuộc group sở hữu docker.sock thì mới gọi được Docker.

### 4.3 Biến môi trường trên EC2

`sudo nano /opt/greytest/config/.env` (giữ quyền 600):

```bash
DOCKER_GID=          # điền kết quả của: getent group docker | cut -d: -f3
GREYTEST_LLM_BATCH_CONCURRENCY=4   # cho phép FREE=2, PLUS=3, PRO=4 theo bảng tier_package
JAVA_TOOL_OPTIONS=-Xms256m -Xmx1536m -XX:+ExitOnOutOfMemoryError
LLM_PROVIDER=openai
LLM_MODEL=gpt-6-luna
LLM_OPENAI_URL=https://api.openai.com/v1/chat/completions
```

Không in file `.env` ra màn hình, vì trong đó có API key.

### 4.4 Tải sẵn image sandbox

```bash
sudo docker pull maven:3.9-eclipse-temurin-17
sudo docker pull maven:3.9-eclipse-temurin-21   # chỉ khi có project Java 21
```

Sandbox chọn image theo phiên bản Java của project (8/11/17/21; mặc định 17).

## 5. Áp dụng

```bash
sudo -i
cd /opt/greytest/backend
docker compose -p greytest-aws --env-file /opt/greytest/config/.env -f docker-compose.aws.yml config --quiet
docker compose -p greytest-aws --env-file /opt/greytest/config/.env -f docker-compose.aws.yml up -d --no-build --pull never --wait --wait-timeout 300
docker inspect greytest-aws-backend-1 --format '{{json .Mounts}}'   # phải thấy docker.sock
docker exec greytest-aws-backend-1 docker version                   # phải in được cả Client và Server
```

Nếu image backend mới nằm trên ECR mà chưa có trên máy, hãy pull đúng tag `sha-<commit>` theo [aws-auto-deploy.md](aws-auto-deploy.md) trước khi chạy `up`.

## 6. Kiểm tra sau nâng cấp

1. Chạy `smoke-aws.py` như trong runbook.
2. Trên web: import một project nhỏ (ví dụ `source-test/course-registration`), sinh đến Unit Test, rồi bấm chạy test. Phải ra được số test đạt/không đạt và coverage.
3. Trong lúc test chạy, gõ `docker stats --no-stream`. Tổng RAM phải dưới khoảng 6 GiB.
4. Bật lại `AWS_DEPLOY_ENABLED` sau khi mọi thứ ổn.

## 7. Rollback

- Image: deploy lại tag `sha-` cũ (xem aws-ci-recovery.md).
- Compose/env: khôi phục bản sao lưu ở bước 1, rồi chạy lại `up`.
- Instance type: Stop → Change instance type về `t3.small` → Start. Khi đó phải bỏ sandbox (gỡ dòng docker.sock), vì 2 GiB không đủ.

## Rủi ro cần ghi nhận

- Mount `docker.sock` cho backend quyền tương đương root trên EC2. Chỉ chấp nhận cho bản demo đồ án và chỉ cấp tài khoản cho người tin cậy. Mỗi container sandbox đã bị giới hạn bằng `--memory 2g --cpus 2 --pids-limit 512 --security-opt no-new-privileges`.
- Code người dùng upload sẽ được biên dịch và chạy trong sandbox, nên không mở đăng ký công khai khi đang bật sandbox.
