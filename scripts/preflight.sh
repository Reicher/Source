#!/usr/bin/env sh
set -eu

repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$repo_root"

if [ ! -f .env ]; then
    printf '%s\n' "Missing .env; copy .env.example and review it." >&2
    exit 1
fi

data_root=$(sed -n 's/^SOURCE_DATA_ROOT=//p' .env | tail -n 1)
data_root=${data_root:-$repo_root/data}
bind_ip=$(sed -n 's/^SOURCE_BIND_IP=//p' .env | tail -n 1)
gateway_host=$(sed -n 's/^SOURCE_GATEWAY_HOST=//p' .env | tail -n 1)
bind_ip=${bind_ip:-127.0.0.1}
gateway_host=${gateway_host:-127.0.0.1}

case "$bind_ip" in
    0.0.0.0|::|'')
        printf '%s\n' "SOURCE_BIND_IP must be one explicit loopback or LAN address." >&2
        exit 1
        ;;
esac

if [ "$bind_ip" != "$gateway_host" ]; then
    printf '%s\n' "SOURCE_BIND_IP and SOURCE_GATEWAY_HOST must match for the current IP certificate." >&2
    exit 1
fi

failed=0
for path in \
    "$data_root/node" \
    "$data_root/vaults" \
    "$data_root/gateway/data" \
    "$data_root/gateway/config" \
    "$data_root/models"
do
    if [ ! -d "$path" ]; then
        printf 'missing: %s\n' "$path" >&2
        failed=1
    fi
done

docker compose --env-file .env config --quiet
[ "$failed" -eq 0 ] || exit 1
printf '%s\n' "Source Node preflight passed. No service was started."
