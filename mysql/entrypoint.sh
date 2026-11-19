#!/bin/bash
set -Eeuo pipefail
mkdir -p /var/log/mysql
chown mysql:mysql /var/log/mysql
touch /var/log/mysql/error.log /var/log/mysql/slow.log /var/log/mysql/entrypoint.log
chown mysql:mysql /var/log/mysql/*.log
# Preserve the upstream initialization and PID 1 signal handling, including its seed scripts.
exec > >(tee -a /var/log/mysql/entrypoint.log) 2>&1
exec /usr/local/bin/docker-entrypoint.sh "$@"
