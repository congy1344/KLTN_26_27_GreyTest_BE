#!/usr/bin/env bash
# Cập nhật một service, giữ volume và khôi phục image cũ nếu healthcheck thất bại.
set -Eeuo pipefail
umask 077
lock_seconds=300
login_seconds=60
pull_seconds=300
start_seconds=330
rollback_seconds=330
health_attempts=12
health_seconds=10
health_sleep_seconds=5
[[ $# == 2 ]] || { echo 'Usage: deploy-image.sh backend|web IMAGE' >&2; exit 1; }
base=${GREYTEST_ROOT:-/opt/greytest}
env_file="$base/config/.env"
compose_file="$base/backend/docker-compose.aws.yml"
service=$1
image=$2
case "$service" in
  backend) repository=greytest/backend; image_key=BACKEND_IMAGE ;;
  web) repository=greytest/frontend; image_key=FRONTEND_IMAGE ;;
  *) echo 'Unknown service' >&2; exit 1 ;;
esac
# File này do quản trị viên tạo, không đọc .env ứng dụng bằng shell vì có giá trị chứa dấu cách.
# shellcheck source=/dev/null
source "$base/config/deploy.conf"
[[ ${AWS_ACCOUNT_ID:-} =~ ^[0-9]{12}$ && ${AWS_REGION:-} =~ ^[a-z]{2}-[a-z]+-[0-9]+$ ]] || exit 1
registry="$AWS_ACCOUNT_ID.dkr.ecr.$AWS_REGION.amazonaws.com"
[[ $image == "$registry/$repository:sha-"* && ${image##*:sha-} =~ ^[0-9a-f]{40}$ ]] || {
  echo 'Image must belong to this account, region and service, with a full commit SHA' >&2; exit 1;
}
exec 9>"$base/config/deploy.lock"
flock -w "$lock_seconds" 9 || { echo 'Another deployment still holds the lock' >&2; exit 1; }
available=$(df --output=avail -B1 "$base" | tail -n 1)
(( available >= 2147483648 )) || { echo 'Keep at least 2 GiB free before pulling an image' >&2; exit 1; }
compose=(docker compose -p greytest-aws --env-file "$env_file" -f "$compose_file")
container=$("${compose[@]}" ps --all --quiet "$service")
[[ -n $container && $container != *$'\n'* ]] || { echo 'Bootstrap this service before enabling auto deploy' >&2; exit 1; }
[[ $(docker inspect --format '{{.State.Running}}' "$container") == true ]] || {
  echo 'Current service is stopped; inspect it before deploying' >&2; exit 1;
}
work=$(mktemp -d "$base/config/.deploy.XXXXXXXX")
changed=false
# Hàm được gọi gián tiếp qua EXIT trap.
# shellcheck disable=SC2329
cleanup() {
  result=$?
  trap - EXIT
  if $changed && (( result != 0 )); then
    echo 'Deployment failed; restoring previous image configuration' >&2
    cp -p "$work/previous.env" "$env_file"
    if ! timeout "$rollback_seconds" "${compose[@]}" up -d --no-deps --no-build --pull never --wait --wait-timeout 300 "$service"; then
      echo 'ROLLBACK FAILED: inspect EC2 through Session Manager' >&2
    fi
  fi
  rm -rf -- "$work"
  exit "$result"
}
trap 'cleanup' EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
export DOCKER_CONFIG="$work/docker"
mkdir -m 700 "$DOCKER_CONFIG"
timeout "$login_seconds" aws ecr get-login-password --region "$AWS_REGION" | timeout "$login_seconds" docker login --username AWS --password-stdin "$registry"
timeout "$pull_seconds" docker pull "$image"
if [[ $service == backend ]]; then
  old_schema=$(docker inspect --format '{{ index .Config.Labels "com.greytest.schema-sha256" }}' "$container")
  new_schema=$(docker image inspect --format '{{ index .Config.Labels "com.greytest.schema-sha256" }}' "$image")
  [[ $old_schema =~ ^[0-9a-f]{64}$ && $old_schema == "$new_schema" ]] || {
    echo 'Database migrations differ or schema label is missing: backup and deploy manually' >&2; exit 1;
  }
fi
cp -p "$env_file" "$work/previous.env"
python3 - "$env_file" "$work/next.env" "$image_key" "$image" <<'PY'
import os
from pathlib import Path
import re
import sys
source, target, key, image = sys.argv[1:]
text = Path(source).read_text()
pattern = rf"(?m)^{key}=.*$"
if len(re.findall(pattern, text)) > 1:
    raise SystemExit(f"Duplicate {key} in env file")
if re.search(pattern, text):
    text = re.sub(pattern, f"{key}={image}", text)
else:
    text = text.rstrip('\n') + f"\n{key}={image}\n"
Path(target).write_text(text)
os.chmod(target, 0o600)
PY
docker compose -p greytest-aws --env-file "$work/next.env" -f "$compose_file" config --quiet
site=$(python3 - "$env_file" <<'PY'
from pathlib import Path
import sys
values = [line.split('=', 1)[1] for line in Path(sys.argv[1]).read_text().splitlines() if line.startswith('SITE_ADDRESS=')]
if len(values) != 1:
    raise SystemExit('Expected one SITE_ADDRESS')
print(values[0])
PY
)
[[ $site =~ ^[a-zA-Z0-9][a-zA-Z0-9.-]*[a-zA-Z0-9]$ ]] || { echo 'SITE_ADDRESS must be a public hostname' >&2; exit 1; }
changed=true
mv "$work/next.env" "$env_file"
timeout "$start_seconds" "${compose[@]}" up -d --no-deps --no-build --pull never --wait --wait-timeout 300 "$service"
for ((attempt=1; attempt<=health_attempts; attempt++)); do
  if curl --fail --silent --show-error --connect-timeout 5 --max-time "$health_seconds" \
    --resolve "$site:443:127.0.0.1" "https://$site/api/ping"; then
    echo "Deployed $service: $image"
    exit 0
  fi
  sleep "$health_sleep_seconds"
done
echo 'HTTPS API healthcheck failed' >&2
exit 1
