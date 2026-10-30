# Backup/restore cho installation CI/CD

Áp dụng cho `/opt/greytest`, active project **greytest-aws**, cấu hình từ [CI/CD guide](aws-auto-deploy.md). Không yêu cầu bundle release, RELEASE_TAG thực hoặc release-manifest của cách deploy thủ công. Quy trình dưới **chưa thực thi trên AWS**; cần thử restore trước khi coi là bảo đảm recovery.

## 1. Backup đồng bộ database, storage và đúng image đang chạy

Đặt AWS_DEPLOY_ENABLED=false ở cả hai repo, đợi mọi GitHub run và SSM command đang chạy kết thúc. Chờ job AI hoàn tất, thông báo cửa sổ downtime; ghi inventory domain/instance/credit. Xác nhận ba services đang chạy và project đúng là greytest-aws. Không áp dụng lệnh với installation/project đã đổi tên.

Trong SSM mở `sudo -i`, rồi chạy block dưới sau khi đã đọc. Không dán lặp lệnh khi terminal còn đang chờ newline. Backup chứa dữ liệu và secrets, chỉ root đọc; không đưa vào Git hoặc ECR.

```bash
set -Eeuo pipefail
umask 077
exec 9>/opt/greytest/config/deploy.lock
flock -w 300 9
stamp=$(date -u +%Y%m%dT%H%M%SZ)
backup="/opt/greytest/backups/ci-$stamp"
test ! -e "$backup"
install -d -m 700 "$backup"
dc=(docker compose -p greytest-aws --env-file /opt/greytest/config/.env -f /opt/greytest/backend/docker-compose.aws.yml)
for service in postgres backend web; do
  container=$("${dc[@]}" ps --quiet "$service")
  test -n "$container"
  test "$(docker inspect --format '{{.State.Running}}' "$container")" = true
  image_id=$(docker inspect --format '{{.Image}}' "$container")
  docker tag "$image_id" "greytest-backup-$service:$stamp"
done
docker image inspect "greytest-backup-postgres:$stamp" "greytest-backup-backend:$stamp" "greytest-backup-web:$stamp" > "$backup/image-inventory.json"
docker save --output "$backup/images.tar" "greytest-backup-postgres:$stamp" "greytest-backup-backend:$stamp" "greytest-backup-web:$stamp"
printf 'services:\n  postgres:\n    image: greytest-backup-postgres:%s\n  backend:\n    image: greytest-backup-backend:%s\n  web:\n    image: greytest-backup-web:%s\n' "$stamp" "$stamp" "$stamp" > "$backup/snapshot-images.yml"
cp -p /opt/greytest/config/.env "$backup/.env"
cp -p /opt/greytest/config/deploy.conf "$backup/deploy.conf"
cp -p /opt/greytest/backend/docker-compose.aws.yml "$backup/docker-compose.aws.yml"
cp -p /opt/greytest/backend/smoke-aws.py "$backup/smoke-aws.py"
cp -p /opt/greytest/bin/deploy-image.sh "$backup/deploy-image.sh"
trap '"${dc[@]}" up -d --no-build --pull never --wait --wait-timeout 300 backend web' EXIT
"${dc[@]}" stop web backend
"${dc[@]}" exec -T postgres pg_dump --username=greytest --dbname=greytest --format=custom > "$backup/database.dump"
for logical in backend_storage caddy_data caddy_config; do
  docker volume inspect "greytest-aws_$logical" >/dev/null
  docker run --rm --network none --mount "type=volume,source=greytest-aws_$logical,target=/data,readonly" --mount "type=bind,source=$backup,target=/backup" --entrypoint tar "greytest-backup-postgres:$stamp" -C /data -czf "/backup/$logical.tar.gz" .
done
"${dc[@]}" exec -T postgres pg_restore --list < "$backup/database.dump" >/dev/null
"${dc[@]}" up -d --no-build --pull never --wait --wait-timeout 300 backend web
trap - EXIT
(cd "$backup" && sha256sum .env deploy.conf docker-compose.aws.yml smoke-aws.py deploy-image.sh image-inventory.json snapshot-images.yml images.tar database.dump backend_storage.tar.gz caddy_data.tar.gz caddy_config.tar.gz > SHA256SUMS.tmp && mv SHA256SUMS.tmp SHA256SUMS)
printf 'Completed backup: %s\n' "$backup"
exit
```

EXIT trap cố khởi động lại app nếu backup lỗi sau lúc bắt đầu stop; không bảo đảm restart thành công. Sau mỗi lần luôn kiểm tra `ps`, health/HTTPS và logs. Chỉ bản có SHA256SUMS hoàn chỉnh được dùng cho restore. Nếu script lỗi giữa chừng, kiểm tra đĩa/service trước; không coi folder đã tạo là backup hoàn chỉnh. Trong lệnh tar, volume source đã inspect và readonly nên không khởi tạo nhầm volume rỗng. PostgreSQL được dump, không tar volume DB đang chạy.

Alias backup được tạo từ **image ID của container đang chạy**, kể cả PostgreSQL, nên không phụ thuộc tag registry đã thay đổi. Archive chứa đúng image và có thể restore khi tag ECR cũ hết retention. Ghi lại full ECR image URIs trong .env và image IDs trong inventory; override snapshot-images.yml mới là nguồn chọn image lúc restore. Không lấy .env hiện tại thay .env trong backup.

Chuyển nguyên backup (bao gồm .env ẩn) ra nơi riêng an toàn ngoài EC2, giữ quyền truy cập hạn chế, kiểm tra SHA256SUMS sau khi chuyển. Backup nằm cùng đĩa EC2 không bảo vệ trước mất EBS/instance/account. Không tự xóa backup/volumes để lấy chỗ.

## 2. Restore thử vào project mới, chỉ loopback

Giữ auto deploy tắt. Không ghi đè project hoặc volume đang chạy. Quy trình này phục hồi **bản thử độc lập**, không tự thay thế website production.

**Không chạy thêm stack restore trên EC2 2 GiB đang phục vụ GreyTest.** Dùng host Linux/Docker kiểm thử đủ RAM (ví dụ máy local riêng) với ít nhất 4 GiB RAM và còn tối thiểu 2 GiB available, rồi chuyển backup an toàn tới host đó. Lệnh dưới có guard RAM trước khi tạo restore. Nếu chỉ có EC2 2 GiB hiện tại, cần kế hoạch downtime dừng/resume live được xác nhận riêng; không bỏ guard để thử song song. Không tự tạo thêm EC2 có phí chỉ để làm theo mục này.

Mở `sudo -i`, điền đúng đường dẫn backup và tên project mới chưa dùng (chữ thường, số và dấu gạch nối):

```bash
set -Eeuo pipefail
umask 077
awk '/MemTotal:/{total=$2} /MemAvailable:/{available=$2} END {exit !(total>=3900000 && available>=2097152)}' /proc/meminfo || { echo 'Restore requires a host with at least 4 GiB RAM and 2 GiB available; do not run alongside live on 2 GiB' >&2; exit 1; }
backup=/opt/greytest/backups/ci-REPLACE_TIMESTAMP
project=greytest-restore-REPLACE_UNIQUE_ID
[[ $project =~ ^greytest-restore-[a-z0-9-]+$ ]]
test -f "$backup/SHA256SUMS"
(cd "$backup" && sha256sum --check SHA256SUMS)
test -z "$(docker ps -aq --filter "label=com.docker.compose.project=$project")"
for logical in postgres_data backend_storage backend_logs caddy_data caddy_config; do
  if docker volume inspect "${project}_$logical" >/dev/null 2>&1; then
    echo "Existing restore volume: ${project}_$logical; choose a new project" >&2
    exit 1
  fi
done
restore="/opt/greytest/restores/$project"
test ! -e "$restore"
install -d -m 700 "$restore"
cp -p "$backup/.env" "$restore/.env"
python3 -c 'from pathlib import Path; import sys; p=Path(sys.argv[1]); text=p.read_text(); updates={"SITE_ADDRESS":":80","WEB_BIND_ADDRESS":"127.0.0.1","WEB_HTTP_PORT":"28080","WEB_HTTPS_PORT":"28443","LLM_PROVIDER":"mock"}; text="\n".join(line for line in text.splitlines() if not any(line.startswith(key+"=") for key in updates)); p.write_text(text+"\n"+"\n".join(key+"="+value for key,value in updates.items())+"\n")' "$restore/.env"
docker load --input "$backup/images.tar"
dc=(docker compose -p "$project" --env-file "$restore/.env" -f "$backup/docker-compose.aws.yml" -f "$backup/snapshot-images.yml")
"${dc[@]}" config --quiet
"${dc[@]}" create --no-build --pull never
postgres_image=$("${dc[@]}" config --format json | python3 -c 'import json,sys; print(json.load(sys.stdin)["services"]["postgres"]["image"])')
for logical in backend_storage caddy_data caddy_config; do
  docker volume inspect "${project}_$logical" >/dev/null
  docker run --rm --network none --mount "type=volume,source=${project}_$logical,target=/data" --mount "type=bind,source=$backup,target=/backup,readonly" --entrypoint tar "$postgres_image" -C /data -xzf "/backup/$logical.tar.gz"
done
"${dc[@]}" up -d --no-build --pull never --wait --wait-timeout 300 postgres
"${dc[@]}" exec -T postgres pg_restore --username=greytest --dbname=greytest --exit-on-error --no-owner < "$backup/database.dump"
"${dc[@]}" up -d --no-build --pull never --wait --wait-timeout 300
python3 "$backup/smoke-aws.py" http://127.0.0.1:28080
"${dc[@]}" ps
exit
```

Kiểm tra login, project/source, BR/Plan/Case/Unit Test, coverage/history/traceability và export/download so với snapshot. Mock được ép lại để tránh gọi provider thật. Không mở port 28080/28443 trên SG. Dừng restore bằng đúng project nếu không dùng; không xóa volume gốc.

Muốn cutover production sau khi restore đã đạt cần cửa sổ bảo trì và kế hoạch được xác nhận: stop traffic/jobs cũ, cập nhật active project/config/ports/root deploy script/lock theo inventory, kiểm tra TLS và pipeline rồi mới bật CI lại. Script CI hiện cố định project greytest-aws; **không bật lại auto deploy để nó ghi vào stack cũ**. Không copy dữ liệu đè lên volume production hoặc down -v theo hướng dẫn restore thử này.
