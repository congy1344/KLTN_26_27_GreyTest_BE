#!/bin/sh
set -e

if [ "$(id -u)" = '0' ]; then
    mkdir -p /app/storage/projects /app/storage/coverage /app/storage/workspaces /app/log
    chown greytest:greytest /app/storage /app/storage/projects /app/storage/coverage /app/storage/workspaces /app/log
    exec runuser -u greytest -- java -jar app.jar "$@"
else
    exec java -jar app.jar "$@"
fi
