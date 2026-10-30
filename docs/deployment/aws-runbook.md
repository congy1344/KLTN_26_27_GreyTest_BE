> Tự động deploy khi push main: dùng [hướng dẫn CI/CD](aws-auto-deploy.md). Runbook này dành cho bundle thủ công. Installation CI/CD dùng [CI recovery](aws-ci-recovery.md) cho backup/restore; không dùng phần backup/restore bundle bên dưới.

# GreyTest — Runbook deploy EC2 bằng Docker Compose

Ngày 22/10/2026. Đọc [các bước AWS Console](aws-console-steps.md) để xác minh Free plan, credit, Sydney và tạo EC2 trước. Hướng dẫn này không chứng minh cloud đã triển khai; kết quả thực chạy được ghi trong release evidence.

Đã kiểm chứng image Linux, pipeline mock, persistence và restore riêng tại local; xem [báo cáo ngày 22/10/2026](local-verification-20261022.md) để biết kết quả và giới hạn trước khi triển khai cloud.

## 1. Những giá trị phải giữ nhất quán

| Thành phần | Giá trị |
| --- | --- |
| Hai repo local | `D:\.KLTN\backend` và `D:\.KLTN\frontend` |
| Backend Dockerfile | `backend/Dockerfile.aws`, Java 17, UID/GID `10001:10001` |
| Frontend Dockerfile | `frontend/Dockerfile`, build React rồi phục vụ bằng Caddy |
| Compose production | `backend/docker-compose.aws.yml`, file độc lập, không merge Compose dev |
| Project production | **`greytest-aws`**; luôn truyền `-p greytest-aws` |
| Services | `postgres`, `backend`, `web` |
| Images | `greytest-backend:RELEASE_TAG`, `greytest-frontend:RELEASE_TAG`, `postgres:15-alpine` |
| DB/user | `greytest` / `greytest`, không publish cổng DB hoặc backend |
| Volume production | `greytest-aws_postgres_data`, `greytest-aws_backend_storage`, `greytest-aws_backend_logs`, `greytest-aws_caddy_data`, `greytest-aws_caddy_config` |
| Dữ liệu ứng dụng | `/app/storage`: source, coverage, revisions; log tại `/app/log` |
| File môi trường thật | `/opt/greytest/config/.env`, quyền 600, ngoài Git/image |
| File chạy trên EC2 | `/opt/greytest/backend/docker-compose.aws.yml` và `smoke-aws.py` |
| Lịch sử release | `/opt/greytest/releases/RELEASE_TAG/` chứa bundle, manifest và checksum |
| Backup | `/opt/greytest/backups/UTC_TIMESTAMP/`, lấy thêm một bản ra ngoài EC2 |

Volume được đặt theo project; `-p greytest-local` và `-p greytest-restore` có dữ liệu riêng. Không đổi project production khi update. Không dùng `down -v`, `volume prune` hoặc lệnh xóa volume trong cập nhật thông thường.

Bản demo này chặn prefix `/api/payment*` tại Caddy bằng **503 / PAYMENT_DISABLED**, gồm cả biến thể có path parameter. Code VNPay vẫn được giữ; payment không được coi là đã triển khai. API `/api` giữ nguyên prefix, còn URL sâu React dùng SPA fallback.

## 2. Chuẩn bị và kiểm tra local

Hai repo phải ở cạnh nhau; đây không phải monorepo. Nếu chưa clone, dùng:

```powershell
git clone https://github.com/congy1344/KLTN_26_27_GreyTest_BE.git D:\.KLTN\backend
git clone https://github.com/congy1344/KLTN_26_27_GreyTest_FE.git D:\.KLTN\frontend
```

Không clone đè thư mục đã có. Checkout cặp commit đã kiểm tra/review trước khi build. Cần Docker Desktop đang chạy Linux containers, Git, OpenSSH client và đủ disk. Chạy từ PowerShell Windows; không cần nhập các lệnh qua WSL terminal. [Docker Desktop Windows](https://docs.docker.com/desktop/setup/install/windows-install/).

Kiểm tra:

```powershell
docker info --format '{{.OSType}}'
docker buildx version
docker compose version
Get-Command ssh, scp
```

`OSType` phải là `linux`. Nếu daemon chưa chạy, mở Docker Desktop và chờ engine ready. Không đổi sang Windows containers để chữa lỗi image Linux.

Chạy test/build release theo hướng dẫn dự án; Dockerfile package với `-DskipTests`, nên Docker build không thay thế test suite. Đợi reviewer theo AGENTS.md. Local smoke dùng file env riêng, project `greytest-local`, `SITE_ADDRESS=:80`, `WEB_BIND_ADDRESS=127.0.0.1`, ports `18080`/`18443`; không dùng DB dev hoặc env/secrets cloud cho test.

## 3. Build và đóng gói release tại local

Block dưới yêu cầu working tree hai repo sạch. Kết quả build dùng tag gồm ngày và hai commit, không dùng `latest`. Chạy mỗi command thành công rồi mới chuyển bước; block có kiểm tra exit code cho build/export.

```powershell
$ErrorActionPreference = 'Stop'
$backendRepo = 'D:\.KLTN\backend'
$frontendRepo = 'D:\.KLTN\frontend'
$backendCommit = (git -C $backendRepo rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0) { throw 'Cannot read backend commit' }
$frontendCommit = (git -C $frontendRepo rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0) { throw 'Cannot read frontend commit' }
foreach ($repo in @($backendRepo, $frontendRepo)) {
    $changes = git -C $repo status --porcelain
    if ($LASTEXITCODE -ne 0 -or $changes) { throw "Commit/review local changes first: $repo" }
}
$releaseTag = '{0}-{1}-{2}' -f (Get-Date -Format 'yyyyMMdd'), $backendCommit.Substring(0, 7), $frontendCommit.Substring(0, 7)
$releaseDir = Join-Path 'D:\.KLTN\outputs\aws-releases' $releaseTag
if (Test-Path -LiteralPath $releaseDir) { throw 'Release directory already exists; keep release tags immutable' }
New-Item -ItemType Directory -Path $releaseDir | Out-Null

docker buildx build --platform linux/amd64 --load -f "$backendRepo\Dockerfile.aws" -t "greytest-backend:$releaseTag" $backendRepo
if ($LASTEXITCODE -ne 0) { throw 'Backend image build failed' }
docker buildx build --platform linux/amd64 --load -t "greytest-frontend:$releaseTag" $frontendRepo
if ($LASTEXITCODE -ne 0) { throw 'Frontend image build failed' }
docker pull --platform linux/amd64 postgres:15-alpine
if ($LASTEXITCODE -ne 0) { throw 'Postgres image pull failed' }
docker save --output (Join-Path $releaseDir 'images.tar') "greytest-backend:$releaseTag" "greytest-frontend:$releaseTag" postgres:15-alpine
if ($LASTEXITCODE -ne 0) { throw 'Image export failed' }

Copy-Item -LiteralPath "$backendRepo\docker-compose.aws.yml" -Destination $releaseDir
Copy-Item -LiteralPath "$backendRepo\.env.aws.example" -Destination $releaseDir
Copy-Item -LiteralPath "$backendRepo\scripts\deployment\smoke-aws.py" -Destination $releaseDir
$manifest = [ordered]@{
    release_tag = $releaseTag
    created_utc = (Get-Date).ToUniversalTime().ToString('o')
    platform = 'linux/amd64'
    active_project = 'greytest-aws'
    backend_commit = $backendCommit
    frontend_commit = $frontendCommit
    backend_commit_url = "https://github.com/congy1344/KLTN_26_27_GreyTest_BE/commit/$backendCommit"
    frontend_commit_url = "https://github.com/congy1344/KLTN_26_27_GreyTest_FE/commit/$frontendCommit"
    backend_image = "greytest-backend:$releaseTag"
    frontend_image = "greytest-frontend:$releaseTag"
    postgres_image = 'postgres:15-alpine'
    provider_for_initial_smoke = 'mock'
    cloud_verified = $false
}
$utf8NoBom = New-Object System.Text.UTF8Encoding $false
[IO.File]::WriteAllText((Join-Path $releaseDir 'release-manifest.json'), ($manifest | ConvertTo-Json), $utf8NoBom)
$checks = foreach ($name in @('images.tar', 'docker-compose.aws.yml', '.env.aws.example', 'smoke-aws.py', 'release-manifest.json')) {
    $hash = Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $releaseDir $name)
    '{0}  {1}' -f $hash.Hash.ToLowerInvariant(), $name
}
[IO.File]::WriteAllText((Join-Path $releaseDir 'SHA256SUMS'), (($checks -join "`n") + "`n"), $utf8NoBom)
Write-Output $releaseDir
```

`--platform linux/amd64` nằm ở **buildx build/pull**, không phải tùy chọn của `docker compose build`. `--load` đưa image vào local image store; `save --output` tránh chuyển binary qua redirect/pipeline PowerShell cũ. [Buildx](https://docs.docker.com/reference/cli/docker/buildx/build/), [image save](https://docs.docker.com/reference/cli/docker/image/save/).

Bundle có đủ ba images, Compose, env mẫu, smoke và manifest; EC2 không cần Maven, npm, source checkout, registry riêng hoặc GitHub token. File env thật không có trong bundle. Giữ bundle cũ để rollback và ghi kết quả test/reviewer bên cạnh manifest.

## 4. Cài Docker trên EC2 mới

Hoàn thành IAM role, SG, key pair và SSM theo [Console guide](aws-console-steps.md). Mở **EC2 → Connect → Session Manager**. Các lệnh dưới chạy trong Ubuntu trên EC2:

```bash
sudo apt-get update
sudo apt-get install -y ca-certificates curl python3 nano
sudo install -m 0755 -d /etc/apt/keyrings
sudo curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
sudo chmod a+r /etc/apt/keyrings/docker.asc
sudo tee /etc/apt/sources.list.d/docker.sources >/dev/null <<EOF
Types: deb
URIs: https://download.docker.com/linux/ubuntu
Suites: $(. /etc/os-release && echo "${UBUNTU_CODENAME:-$VERSION_CODENAME}")
Components: stable
Architectures: $(dpkg --print-architecture)
Signed-By: /etc/apt/keyrings/docker.asc
EOF
sudo apt-get update
sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
sudo systemctl enable --now docker
sudo docker version
sudo docker compose version
sudo docker run --rm hello-world
```

Đây là cài từ Docker apt repository; nếu official guide thay đổi, đối chiếu [Docker Ubuntu install — apt repository](https://docs.docker.com/engine/install/ubuntu/#install-using-the-repository). Không chạy script convenience tải từ mạng hoặc cài cả Docker Desktop trên EC2.

## 5. Chuyển bundle và cài lần đầu

Tạm bật SG inbound SSH TCP 22 **My IP**, dùng `.pem` đã chọn lúc launch. Trong PowerShell local, tiếp nối các biến ở mục 3 hoặc gán lại `$releaseDir`, `$releaseTag`:

```powershell
$instanceAddress = 'REPLACE_WITH_ELASTIC_IP'
$keyPath = 'C:\keys\greytest-demo.pem'
ssh -i $keyPath "ubuntu@$instanceAddress" 'mkdir -p /home/ubuntu/greytest-releases'
if ($LASTEXITCODE -ne 0) { throw 'Cannot prepare upload directory' }
scp -r -i $keyPath $releaseDir "ubuntu@${instanceAddress}:/home/ubuntu/greytest-releases/"
if ($LASTEXITCODE -ne 0) { throw 'Release upload failed' }
```

Đối chiếu host fingerprint, không dùng `StrictHostKeyChecking=no`. [SCP tới EC2](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/linux-file-transfer-scp.html).

Về terminal SSM, dùng root shell cho các thao tác file; thay `REPLACE_RELEASE_TAG` đúng tên thư mục bundle vừa chuyển:

```bash
sudo -i
set -euo pipefail
release_tag=REPLACE_RELEASE_TAG
release_dir="/home/ubuntu/greytest-releases/$release_tag"
test -d "$release_dir"
(cd "$release_dir" && sha256sum --check SHA256SUMS)
install -d -m 755 /opt/greytest/backend /opt/greytest/releases
install -d -m 700 /opt/greytest/config /opt/greytest/backups
test ! -e "/opt/greytest/releases/$release_tag"
cp -a "$release_dir" "/opt/greytest/releases/$release_tag"
docker load --input "/opt/greytest/releases/$release_tag/images.tar"
test ! -e /opt/greytest/config/.env
install -m 644 "$release_dir/docker-compose.aws.yml" /opt/greytest/backend/docker-compose.aws.yml
install -m 644 "$release_dir/smoke-aws.py" /opt/greytest/backend/smoke-aws.py
install -m 644 "$release_dir/release-manifest.json" /opt/greytest/backend/release-manifest.json
install -m 600 "$release_dir/.env.aws.example" /opt/greytest/config/.env
python3 - <<'PY'
from pathlib import Path
import secrets
path = Path('/opt/greytest/config/.env')
text = path.read_text()
for name in ('POSTGRES_PASSWORD', 'GREYTEST_AUTH_TOKEN_SECRET'):
    text = text.replace(name + '=\n', name + '=' + secrets.token_hex(32) + '\n', 1)
path.write_text(text)
PY
nano /opt/greytest/config/.env
```

Trong editor, đặt **`RELEASE_TAG`** đúng tag vừa chuyển, **`SITE_ADDRESS`** là domain không có path, ví dụ `greytest-demo.duckdns.org`; giữ `LLM_PROVIDER=mock`. Hai secrets đã được sinh mà không in ra màn hình. Không thay thành password mẫu. Không đặt `WEB_BIND_ADDRESS=127.0.0.1` hoặc port local trong production.

DB/password chỉ khởi tạo khi volume PostgreSQL còn trống. Đổi `POSTGRES_PASSWORD` trong env sau đó không tự đổi password đã lưu trong DB. [Docker Postgres official](https://hub.docker.com/_/postgres).

Bắt đầu, vẫn trong root shell:

```bash
docker compose -p greytest-aws --env-file /opt/greytest/config/.env -f /opt/greytest/backend/docker-compose.aws.yml config --quiet
docker compose -p greytest-aws --env-file /opt/greytest/config/.env -f /opt/greytest/backend/docker-compose.aws.yml up -d --no-build --pull never --wait --wait-timeout 300
docker compose -p greytest-aws --env-file /opt/greytest/config/.env -f /opt/greytest/backend/docker-compose.aws.yml ps
python3 /opt/greytest/backend/smoke-aws.py https://REPLACE_DOMAIN
docker stats --no-stream
df -h
exit
```

Chỉ có bundle đã load được sử dụng: `--no-build --pull never` tránh build hoặc tải nhầm image trên VM. Build context `../frontend` trong Compose chỉ phục vụ build local; deploy không cần folder source frontend. [Compose up](https://docs.docker.com/reference/cli/docker/compose/up/).

Smoke kiểm tra `/api/ping`, refresh deep link React, API lỗi vẫn là JSON và payment bị chặn. Nó chưa kiểm tra toàn bộ pipeline, DB backup hoặc provider thật. Nếu HTTPS chưa sẵn sàng, kiểm tra DNS/80/443 trước; không dùng `curl -k` để đánh dấu nghiệm thu.

Đăng ký user demo mới, login, upload source fixture và kiểm tra pipeline bằng mock. Cloud DB mới tránh đường dẫn source Windows cũ. Cấp ADMIN theo mục 5.1 dưới đây; không tạo mật khẩu ADMIN mặc định. Chuyển provider thật bằng editor khi có key/quota và chạy một job nhỏ, rồi đo RAM/CPU/disk. AWS credit không thanh toán API AI bên ngoài AWS.

Xác nhận SSM còn hoạt động rồi xóa rule SSH 22 theo Console guide. Giữ 80/443. Lưu inventory domain/IDs, active project `greytest-aws`, tag và thời hạn credit.

### 5.1. Cấp ADMIN đầu tiên sau khi user tự đăng ký

[AuthService.register](../../src/main/java/com/greytest/service/AuthService.java) chuẩn hóa email về chữ thường, hash mật khẩu bằng BCrypt và tạo `USER`, `enabled=true`. [Migration V16](../../src/main/resources/db/migration/V16__add_auth_user_and_project_owner.sql) tạo bảng `auth_user`; role `ADMIN` có trong [UserRole](../../src/main/java/com/greytest/entity/enums/UserRole.java). Thao tác dưới chỉ cấp quyền cho một tài khoản đã xác minh; không đọc/sửa password hash.

1. Người giữ quyền quản trị tự đăng ký trên UI bằng email và mật khẩu riêng. Giữ một tài khoản USER khác để thử phân quyền.
2. Trong SSM, mở psql nội bộ container, không mở cổng DB public:

```bash
sudo docker compose -p greytest-aws --env-file /opt/greytest/config/.env -f /opt/greytest/backend/docker-compose.aws.yml exec postgres psql --username=greytest --dbname=greytest -v ON_ERROR_STOP=1
```

3. Trong psql, thay email bằng tài khoản người quản trị vừa đăng ký:

```sql
\set admin_email 'REPLACE_WITH_YOUR_REGISTERED_EMAIL'
SELECT id, email, full_name, role, enabled
FROM auth_user
WHERE email = lower(:'admin_email');
```

4. Chỉ tiếp tục khi đúng **một** dòng, đúng chủ tài khoản, `role=USER`, `enabled=true`. Ghi đúng ID đó vào biến; placeholder không phải một ID hợp lệ:

```sql
\set admin_id 'REPLACE_WITH_VERIFIED_NUMERIC_ID'
BEGIN;
SELECT id, email, role, enabled
FROM auth_user
WHERE id = :'admin_id'::bigint AND email = lower(:'admin_email')
FOR UPDATE;
UPDATE auth_user
SET role = 'ADMIN', updated_at = NOW()
WHERE id = :'admin_id'::bigint
  AND email = lower(:'admin_email')
  AND role = 'USER'
  AND enabled = true
RETURNING id, email, role, enabled;
```

5. Đối chiếu `RETURNING`: đúng một dòng, đúng ID/email, `ADMIN`, `enabled=true`. Chỉ lúc đó chạy:

```sql
COMMIT;
\q
```

Nếu không đúng, chạy `ROLLBACK;` rồi `\q`, kiểm tra lại tài khoản; không nới điều kiện UPDATE hoặc cấp quyền hàng loạt.

6. Logout phiên cũ, mở **`https://DOMAIN/admin/login`** và đăng nhập bằng mật khẩu đã đăng ký. `AuthService.login` cố ý từ chối ADMIN ở cổng USER; `adminLogin` yêu cầu role ADMIN. Kiểm tra `/admin` có quyền quản trị, còn USER không vào được. Ghi người được cấp quyền/thời điểm vào inventory riêng, không ghi mật khẩu.

## 6. Theo dõi và khởi động lại

Trong SSM, lệnh read-only:

```bash
sudo docker compose -p greytest-aws --env-file /opt/greytest/config/.env -f /opt/greytest/backend/docker-compose.aws.yml ps
sudo docker compose -p greytest-aws --env-file /opt/greytest/config/.env -f /opt/greytest/backend/docker-compose.aws.yml logs --tail 100 backend postgres web
sudo docker stats --no-stream
df -h
free -h
```

Không gửi log nguyên bản chứa token, source hoặc thông tin người dùng vào public issue. Kiểm tra OOM và health theo bảng troubleshooting bên dưới.

`restart: unless-stopped` giúp container đang chạy trở lại khi Docker/EC2 khởi động, trừ container bị operator stop thủ công. `unhealthy` không tự làm container được restart. [Restart policies](https://docs.docker.com/engine/containers/start-containers-automatically/).

Thử reboot sau khi không còn job; chờ SSM/status checks, chạy lại `ps` và smoke. Nếu trước đó đã stop container thủ công, dùng `up -d --no-build --pull never --wait --wait-timeout 300` để bật lại.

## 7. Backup DB + storage cùng thời điểm

Thực hiện trước update và trước hết credit. Chờ tất cả job kết thúc; báo cửa sổ downtime. Block dưới dừng web/backend nên không có mutation giữa dump DB và chụp storage; PostgreSQL vẫn chạy. Không dùng bản DB và storage lấy từ hai thời điểm khác nhau.

Đối chiếu inventory: các lệnh này dành cho active project **`greytest-aws`**. Nếu sau sự cố operator đã cutover sang project khác, cập nhật inventory và mọi project/volume bên dưới trước khi chạy; không backup nhầm stack cũ.

Chạy trong SSM:

```bash
sudo -i
set -euo pipefail
umask 077
backup_dir="/opt/greytest/backups/$(date -u +%Y%m%dT%H%M%SZ)"
test -f /opt/greytest/backend/release-manifest.json
mkdir -m 700 "$backup_dir"
docker volume inspect greytest-aws_backend_storage greytest-aws_caddy_data greytest-aws_caddy_config >/dev/null
resume_after_backup() {
    result=$?
    trap - EXIT
    if ! docker compose -p greytest-aws --env-file /opt/greytest/config/.env -f /opt/greytest/backend/docker-compose.aws.yml start --wait --wait-timeout 300; then
        echo 'ERROR: could not resume live stack; reconnect SSM and inspect logs' >&2
        result=1
    fi
    exit "$result"
}
trap resume_after_backup EXIT
docker compose -p greytest-aws --env-file /opt/greytest/config/.env -f /opt/greytest/backend/docker-compose.aws.yml stop web backend
docker compose -p greytest-aws --env-file /opt/greytest/config/.env -f /opt/greytest/backend/docker-compose.aws.yml exec -T postgres pg_dump --format=custom --username=greytest --dbname=greytest > "$backup_dir/database.dump"
test -s "$backup_dir/database.dump"
docker compose -p greytest-aws --env-file /opt/greytest/config/.env -f /opt/greytest/backend/docker-compose.aws.yml exec -T postgres pg_restore --list < "$backup_dir/database.dump" > "$backup_dir/database.toc"
for suffix in backend_storage caddy_data caddy_config; do
    docker run --rm --user 0 --entrypoint tar --mount "type=volume,src=greytest-aws_$suffix,dst=/data,readonly" --mount "type=bind,src=$backup_dir,dst=/backup" postgres:15-alpine -czf "/backup/$suffix.tar.gz" -C /data .
done
cp /opt/greytest/backend/docker-compose.aws.yml "$backup_dir/docker-compose.aws.yml"
cp /opt/greytest/backend/release-manifest.json "$backup_dir/release-manifest.json"
sed -n '/^RELEASE_TAG=/p' /opt/greytest/config/.env > "$backup_dir/release-tag.env"
test -s "$backup_dir/release-tag.env"
(cd "$backup_dir" && sha256sum database.dump database.toc backend_storage.tar.gz caddy_data.tar.gz caddy_config.tar.gz docker-compose.aws.yml release-manifest.json release-tag.env > SHA256SUMS)
(cd "$backup_dir" && sha256sum --check SHA256SUMS)
printf 'Backup folder: %s\n' "$backup_dir"
exit
```

EXIT trap cố khởi động lại live stack khi dump/archive lỗi hoặc kết thúc block. Nếu session mất đột ngột, kiểm tra `ps` sau khi kết nối lại; không giả định trap luôn chạy khi máy/daemon bị tắt. `pg_dump -Fc` cần `pg_restore` để khôi phục. [PostgreSQL dump](https://www.postgresql.org/docs/15/app-pgdump.html), [Docker volume backup](https://docs.docker.com/engine/storage/volumes/#back-up-restore-or-migrate-data-volumes).

Backup chứa dữ liệu người dùng và private key TLS; giữ riêng, không commit. File env thật không nằm trong gói trên; giữ secrets trong nơi quản trị riêng để dựng lại hệ thống. Log không bắt buộc cho khôi phục dữ liệu nghiệp vụ.

Tải backup ra ngoài EC2: thay timestamp bằng folder vừa in. Trong SSM:

```bash
backup_id=REPLACE_BACKUP_TIMESTAMP
sudo install -m 600 -o ubuntu -g ubuntu /dev/null "/home/ubuntu/$backup_id.tar.gz"
sudo tar -czf "/home/ubuntu/$backup_id.tar.gz" -C /opt/greytest/backups "$backup_id"
```

Mở SSH My IP tạm thời. PowerShell local:

```powershell
$backupArchive = 'REPLACE_BACKUP_TIMESTAMP.tar.gz'
$localBackupDir = 'D:\GreyTest-private-backups'
New-Item -ItemType Directory -Force -Path $localBackupDir | Out-Null
scp -i 'C:\keys\greytest-demo.pem' "ubuntu@REPLACE_PUBLIC_IP:/home/ubuntu/$backupArchive" $localBackupDir
if ($LASTEXITCODE -ne 0) { throw 'Backup download failed' }
```

Đóng SSH sau chuyển file. Giữ ít nhất một backup ngoài VM đã thử restore; trước khi xóa bản cũ phải kiểm tra bản mới khôi phục được. Không để các bản backup và image tar tăng vô hạn trên EBS 20 GiB.

## 8. Restore rehearsal vào project tách biệt

Không chạy restore đè vào production. Mặc định dùng **`greytest-restore`** với volume riêng và web chỉ bind loopback **28080/28443**. Nếu project/volume này đã tồn tại, block dừng thay vì ghi đè; dùng một tên mới nhất quán hoặc xử lý dữ liệu thử cũ sau khi đã xác nhận có thể xóa.

Trên EC2 2 GiB, chạy hai stack đầy đủ có thể OOM. Thực hiện trong downtime, **stop cả live stack** khi thử; hoặc dùng một Docker host local đủ RAM và bộ artifacts/backup đã tải về. Kiểm tra còn đủ disk để chứa bản restore. DB cloud không được publish để phục vụ restore.

Trong SSM, thay `REPLACE_BACKUP_TIMESTAMP`; phải còn bundle đúng release tag trong `/opt/greytest/releases/`:

```bash
sudo -i
set -euo pipefail
umask 077
backup_dir=/opt/greytest/backups/REPLACE_BACKUP_TIMESTAMP
(cd "$backup_dir" && sha256sum --check SHA256SUMS)
restore_tag=$(sed -n 's/^RELEASE_TAG=//p' "$backup_dir/release-tag.env")
[[ "$restore_tag" =~ ^[a-zA-Z0-9_.-]+$ ]]
test -f "/opt/greytest/releases/$restore_tag/images.tar"
test ! -e /opt/greytest/config/restore.env
test -z "$(docker ps -aq --filter label=com.docker.compose.project=greytest-restore)"
for suffix in postgres_data backend_storage backend_logs caddy_data caddy_config; do
    if docker volume inspect "greytest-restore_$suffix" >/dev/null 2>&1; then
        echo "STOP: restore volume already exists: greytest-restore_$suffix" >&2
        exit 1
    fi
done
cp /opt/greytest/config/.env /opt/greytest/config/restore.env
sed -i '/^RELEASE_TAG=/d; /^SITE_ADDRESS=/d; /^WEB_BIND_ADDRESS=/d; /^WEB_HTTP_PORT=/d; /^WEB_HTTPS_PORT=/d; /^LLM_PROVIDER=/d' /opt/greytest/config/restore.env
printf '\nRELEASE_TAG=%s\nSITE_ADDRESS=:80\nWEB_BIND_ADDRESS=127.0.0.1\nWEB_HTTP_PORT=28080\nWEB_HTTPS_PORT=28443\nLLM_PROVIDER=mock\n' "$restore_tag" >> /opt/greytest/config/restore.env
live_postgres_id=$(docker compose -p greytest-aws --env-file /opt/greytest/config/.env -f /opt/greytest/backend/docker-compose.aws.yml ps -aq postgres)
test -n "$live_postgres_id"
live_postgres_image=$(docker inspect --format '{{.Image}}' "$live_postgres_id")
docker load --input "/opt/greytest/releases/$restore_tag/images.tar"
docker image tag "$live_postgres_image" postgres:15-alpine
docker compose -p greytest-restore --env-file /opt/greytest/config/restore.env -f "$backup_dir/docker-compose.aws.yml" config --quiet
docker compose -p greytest-restore --env-file /opt/greytest/config/restore.env -f "$backup_dir/docker-compose.aws.yml" create --no-build --pull never
resume_after_restore() {
    result=$?
    trap - EXIT
    docker compose -p greytest-restore --env-file /opt/greytest/config/restore.env -f "$backup_dir/docker-compose.aws.yml" stop || true
    if ! docker compose -p greytest-aws --env-file /opt/greytest/config/.env -f /opt/greytest/backend/docker-compose.aws.yml start --wait --wait-timeout 300; then
        echo 'ERROR: could not resume live stack; inspect both projects' >&2
        result=1
    fi
    exit "$result"
}
trap resume_after_restore EXIT
docker compose -p greytest-aws --env-file /opt/greytest/config/.env -f /opt/greytest/backend/docker-compose.aws.yml stop web backend postgres
docker compose -p greytest-restore --env-file /opt/greytest/config/restore.env -f "$backup_dir/docker-compose.aws.yml" up -d --no-build --pull never --wait --wait-timeout 180 postgres
docker compose -p greytest-restore --env-file /opt/greytest/config/restore.env -f "$backup_dir/docker-compose.aws.yml" exec -T postgres pg_restore --exit-on-error --no-owner --no-privileges --username=greytest --dbname=greytest < "$backup_dir/database.dump"
for suffix in backend_storage caddy_data caddy_config; do
    docker volume inspect "greytest-restore_$suffix" >/dev/null
    docker run --rm --user 0 --entrypoint tar --mount "type=volume,src=greytest-restore_$suffix,dst=/data" --mount "type=bind,src=$backup_dir,dst=/backup,readonly" postgres:15-alpine -xzf "/backup/$suffix.tar.gz" -C /data
done
docker run --rm --user 0 --entrypoint chown --mount type=volume,src=greytest-restore_backend_storage,dst=/data postgres:15-alpine -R 10001:10001 /data
docker compose -p greytest-restore --env-file /opt/greytest/config/restore.env -f "$backup_dir/docker-compose.aws.yml" up -d --no-build --pull never --wait --wait-timeout 300
python3 /opt/greytest/backend/smoke-aws.py http://127.0.0.1:28080
docker compose -p greytest-restore --env-file /opt/greytest/config/restore.env -f "$backup_dir/docker-compose.aws.yml" exec -T postgres psql --username=greytest --dbname=greytest -v ON_ERROR_STOP=1 -c 'SELECT installed_rank, version, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 5;'
```

Tạm giữ session root này để kiểm tra UI qua tunnel. Nếu chỉ thực hiện HTTP smoke, kết quả mới chứng minh đường phục vụ, chưa chứng minh dữ liệu khôi phục đầy đủ. `pg_restore` dừng khi có lỗi; `--no-owner --no-privileges` cho phép role `greytest` do container mới tạo sở hữu dữ liệu. [PostgreSQL restore](https://www.postgresql.org/docs/15/app-pgrestore.html). [Compose create](https://docs.docker.com/reference/cli/docker/compose/create/) tạo container/volume trước, không chạy backend/Flyway trước khi nạp dump. Khi resume live dùng `start` để giữ container/image cũ, tránh recreate PostgreSQL theo tag đã bị bundle cũ thay đổi. [Compose start](https://docs.docker.com/reference/cli/docker/compose/start/).

Lệnh tag lại giữ image PostgreSQL hiện đang dùng cho cả live và restore; dump logical vẫn cùng major 15. Việc nâng/downgrade PostgreSQL không thuộc rehearsal hoặc rollback BE/FE.

Để mở UI restore từ máy quản trị, bật SSH My IP tạm rồi chạy PowerShell trong cửa sổ khác:

```powershell
ssh -i 'C:\keys\greytest-demo.pem' -N -L 127.0.0.1:28080:127.0.0.1:28080 ubuntu@REPLACE_PUBLIC_IP
```

Mở `http://127.0.0.1:28080`. `-L` chỉ forward port trên loopback local, không mở 28080 public. [OpenSSH forwarding](https://man.openbsd.org/ssh#L).

Đối chiếu với dữ liệu tại thời điểm backup: login, số project, đọc source, revision/coverage, download unit test và export. Ghi thời gian restore cùng kết quả; không đánh dấu pass chỉ vì tar/dump được tạo.

Khi xong, **chạy `exit` trong root session ở trên**: trap stop stack restore và khởi động lại live, sau đó smoke live. Đóng tunnel/SSH rule. Giữ dữ liệu restore riêng cho tới khi operator quyết định xóa; không tự cleanup volume. Nếu restore hỏng, giữ lại log để chẩn đoán và không đổi domain/ports của production.

## 9. Cập nhật release

1. Test/review cặp commit mới, build/export bundle mới theo mục 3; chuyển folder release mới theo mục 5.
2. Chờ job kết thúc, tạo backup mục 7 và ghi thư mục backup/old release tag.
3. Kiểm tra mới/cũ vẫn PostgreSQL major 15, DB/user/storage path và project giữ nguyên. Không ghép migration với việc đổi DB credentials hoặc đổi volume.
4. Trong SSM, thay tag mới; config trước đó được giữ ngoài Git với quyền 600:

```bash
sudo -i
set -euo pipefail
umask 077
new_tag=REPLACE_NEW_RELEASE_TAG
new_dir="/home/ubuntu/greytest-releases/$new_tag"
(cd "$new_dir" && sha256sum --check SHA256SUMS)
test ! -e "/opt/greytest/releases/$new_tag"
cp -a "$new_dir" "/opt/greytest/releases/$new_tag"
live_postgres_id=$(docker compose -p greytest-aws --env-file /opt/greytest/config/.env -f /opt/greytest/backend/docker-compose.aws.yml ps -aq postgres)
test -n "$live_postgres_id"
live_postgres_image=$(docker inspect --format '{{.Image}}' "$live_postgres_id")
docker load --input "/opt/greytest/releases/$new_tag/images.tar"
docker image tag "$live_postgres_image" postgres:15-alpine
cp /opt/greytest/config/.env "/opt/greytest/config/before-$new_tag.env"
chmod 600 "/opt/greytest/config/before-$new_tag.env"
docker compose -p greytest-aws --env-file /opt/greytest/config/.env -f /opt/greytest/backend/docker-compose.aws.yml stop web backend
install -m 644 "$new_dir/docker-compose.aws.yml" /opt/greytest/backend/docker-compose.aws.yml
install -m 644 "$new_dir/smoke-aws.py" /opt/greytest/backend/smoke-aws.py
python3 - "$new_tag" <<'PY'
from pathlib import Path
import re
import sys
path = Path('/opt/greytest/config/.env')
text, count = re.subn(r'^RELEASE_TAG=.*$', 'RELEASE_TAG=' + sys.argv[1], path.read_text(), flags=re.MULTILINE)
assert count == 1, 'Expected exactly one RELEASE_TAG'
path.write_text(text)
PY
docker compose -p greytest-aws --env-file /opt/greytest/config/.env -f /opt/greytest/backend/docker-compose.aws.yml config --quiet
docker compose -p greytest-aws --env-file /opt/greytest/config/.env -f /opt/greytest/backend/docker-compose.aws.yml up -d --no-deps --no-build --pull never --wait --wait-timeout 300 backend web
python3 /opt/greytest/backend/smoke-aws.py https://REPLACE_DOMAIN
install -m 644 "$new_dir/release-manifest.json" /opt/greytest/backend/release-manifest.json
exit
```

Đợt cập nhật này giữ PostgreSQL container đang chạy, không tự nâng/downgrade image DB. Đọc release notes trước khi thay env có thêm biến bắt buộc. Nếu health/migration/smoke lỗi, giữ cửa sổ maintenance, đọc log và chọn rollback theo mục 10; không tự chạy lại mutation hoặc sửa migration đã áp dụng.

## 10. Rollback

### Image cũ còn tương thích schema hiện tại

Chỉ dùng sau khi đã xác minh điều kiện này. Đổi image không đảo Flyway migration. Thay tag release cũ và tag bản cập nhật lỗi; file `before-...env` được tạo ở mục 9:

```bash
sudo -i
set -euo pipefail
old_tag=REPLACE_OLD_RELEASE_TAG
failed_tag=REPLACE_FAILED_RELEASE_TAG
old_dir="/opt/greytest/releases/$old_tag"
test -f "$old_dir/images.tar"
test -f "/opt/greytest/config/before-$failed_tag.env"
(cd "$old_dir" && sha256sum --check SHA256SUMS)
live_postgres_id=$(docker compose -p greytest-aws --env-file /opt/greytest/config/.env -f /opt/greytest/backend/docker-compose.aws.yml ps -aq postgres)
test -n "$live_postgres_id"
live_postgres_image=$(docker inspect --format '{{.Image}}' "$live_postgres_id")
docker load --input "$old_dir/images.tar"
docker image tag "$live_postgres_image" postgres:15-alpine
docker compose -p greytest-aws --env-file /opt/greytest/config/.env -f /opt/greytest/backend/docker-compose.aws.yml stop web backend
install -m 600 "/opt/greytest/config/before-$failed_tag.env" /opt/greytest/config/.env
install -m 644 "$old_dir/docker-compose.aws.yml" /opt/greytest/backend/docker-compose.aws.yml
install -m 644 "$old_dir/smoke-aws.py" /opt/greytest/backend/smoke-aws.py
docker compose -p greytest-aws --env-file /opt/greytest/config/.env -f /opt/greytest/backend/docker-compose.aws.yml config --quiet
docker compose -p greytest-aws --env-file /opt/greytest/config/.env -f /opt/greytest/backend/docker-compose.aws.yml up -d --no-deps --no-build --pull never --wait --wait-timeout 300 backend web
python3 /opt/greytest/backend/smoke-aws.py https://REPLACE_DOMAIN
install -m 644 "$old_dir/release-manifest.json" /opt/greytest/backend/release-manifest.json
exit
```

Kiểm tra `RELEASE_TAG` trong file `before-...env` đúng old tag đã chọn trước khi chạy. Giữ DB container/volumes đang dùng, chỉ recreate backend/web. Cập nhật inventory sau smoke.

### Schema không tương thích image cũ

Đây là một đợt khôi phục dữ liệu có downtime: chọn **cặp DB + storage cùng backup** và release tương ứng; operator phải chấp nhận dữ liệu mới sau backup sẽ không nằm trong bản phục hồi.

Làm mục 8 vào project/volumes mới, kiểm chứng đầy đủ rồi operator chọn cutover domain/ports trong cửa sổ maintenance. Giữ volume production cũ nguyên trạng. Sau cutover, ghi **active project, env file, volume prefix và release tag mới** trong inventory, đồng thời sửa các lệnh backup/update cho active project đó. Runbook không tự promote restore project hoặc ghi đè live volume. Không đổi schema/nghiệp vụ chỉ để image cũ khởi động.

## 11. Troubleshooting

| Triệu chứng | Kiểm tra và xử lý trong phạm vi vận hành |
| --- | --- |
| Compose báo biến bắt buộc | Kiểm tra đúng `--env-file`, `RELEASE_TAG`, `SITE_ADDRESS`, `POSTGRES_PASSWORD`, `GREYTEST_AUTH_TOKEN_SECRET`; không dump config chứa secrets lên chat |
| DB authentication failed | Env DB password phải khớp password đã khởi tạo trong volume; sửa env về giá trị đúng hoặc lập thao tác đổi password có kiểm soát, không xóa volume để chữa lỗi |
| DB healthy nhưng backend lỗi migration | Xem log backend/Flyway; `pg_isready` không chứng minh schema/credentials; đối chiếu release và backup, không sửa migration cũ |
| Access denied khi ghi source/revisions/log | Backend runtime là UID/GID 10001; volume restored cần owner `10001:10001`; chỉ chown đúng storage/log volume khi backend đã dừng, không chmod 777 |
| Exit 137 / OOMKilled | Lấy container ID từ `compose ps -q backend`, xem `docker inspect --format '{{.State.OOMKilled}}' ID`, `docker stats`, `free -h`; không build trên EC2, giữ concurrency 1, đo workload rồi mới chỉnh RAM/heap/máy |
| Backend unhealthy | `logs --tail 100 backend`; healthcheck dùng curl có trong image AWS và `/api/ping`; thiếu secret, DB hoặc crash cần sửa nguyên nhân trước restart |
| Web 502 | Backend phải healthy; upstream là `backend:8080` trong cùng Compose network; không mở public 8080 để né proxy |
| TLS không cấp/renew | DNS A đúng Elastic IP, không AAAA sai, SG80/443 mở, Caddy logs và volume `/data` ghi được; không xóa certificate volume để thử liên tục |
| Trang sâu React lỗi nhưng `/` mở | Kiểm tra đúng frontend image/Caddyfile; SPA fallback phải là `/index.html`, API vẫn nằm trong handle riêng |
| Sau reboot không lên | Docker enabled; container trước đó có bị stop thủ công không; chạy đúng `up -d --no-build --pull never --wait` và xem logs |
| Disk gần đầy | `df -h`, `docker system df`; chuyển backup cũ ra ngoài và xác nhận bản giữ trước cleanup; không `volume prune`/`system prune --volumes` |
| SSM không kết nối | Role instance profile, SSM agent và outbound443/route public; SSH chỉ My IP tạm, không nâng Paid hoặc tạo NAT để chữa lỗi |

## 12. Bằng chứng nghiệm thu cần lưu

- Hai full commit IDs/link, release tag, checksum bundle, kết quả test/build/reviewer.
- Free plan/credit/expiry và Sydney được xác minh; inventory tài nguyên, domain, active project, email budget.
- Smoke HTTPS và luồng user/owner/ADMIN/HITL/export/coverage thực tế; ghi rõ mock hay provider thật.
- RAM/CPU credit/disk trong workload demo, cold start và reboot.
- Backup ngoài VM, restore DB/storage thành công, thời gian khôi phục và giới hạn mất dữ liệu rollback.
- Không đưa `.pem`, env thật, API key, JWT secret, QR/MFA hoặc backup người dùng vào repo/evidence công khai.

Chỉ đánh dấu cloud/restore/rollback đạt sau khi thực chạy; các block hướng dẫn chưa được xem là bằng chứng. Không tự tạo thêm RDS/ECR/ALB/NAT, không nâng Paid plan trong runbook này.
