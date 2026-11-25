#!/bin/sh
set -e

if [ "$(id -u)" = '0' ]; then
    mkdir -p /app/storage/projects /app/storage/coverage /app/storage/workspaces /app/log
    # File do bản chạy bằng root tạo trước đây vẫn phải ghi/xóa được.
    find /app/storage /app/log ! -user greytest -exec chown greytest:greytest {} + || true
    # Sandbox cần gọi Docker: cho greytest vào nhóm sở hữu socket (GID khác nhau theo máy chủ).
    if [ -S /var/run/docker.sock ]; then
        sock_gid=$(stat -c %g /var/run/docker.sock)
        getent group "$sock_gid" >/dev/null || groupadd --gid "$sock_gid" dockersock
        usermod -aG "$(getent group "$sock_gid" | cut -d: -f1)" greytest
    fi
    exec runuser -u greytest -- java -jar app.jar "$@"
else
    exec java -jar app.jar "$@"
fi
