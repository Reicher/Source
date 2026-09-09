#!/usr/bin/env sh
set -eu

repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$repo_root"

if [ "$#" -eq 0 ]; then
    printf '%s\n' "Usage: $0 status|list" >&2
    printf '%s\n' "Create users through http://127.0.0.1:${SOURCE_ADMIN_PORT:-9090}." >&2
    exit 2
fi

docker compose --env-file .env exec -T node node src/admin.mjs "$@"
