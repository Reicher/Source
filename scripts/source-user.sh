#!/usr/bin/env sh
set -eu

repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$repo_root"

if [ "$#" -eq 0 ]; then
    printf '%s\n' "Usage: $0 create|list|reset-password|sessions|revoke-session|disable|enable|delete [username] [argument]" >&2
    exit 2
fi

docker compose --env-file .env exec -T node node src/admin.mjs "$@"
