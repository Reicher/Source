#!/usr/bin/env sh
set -eu

if [ "$(id -u)" -eq 0 ]; then
    printf '%s\n' "Run deployment as the Source operator, not directly as root." >&2
    exit 1
fi

repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$repo_root"

deploy_timeout=${SOURCE_DEPLOY_TIMEOUT_SECONDS:-300}
case "$deploy_timeout" in
    ''|*[!0-9]*)
        printf '%s\n' "SOURCE_DEPLOY_TIMEOUT_SECONDS must be a positive integer." >&2
        exit 1
        ;;
    0)
        printf '%s\n' "SOURCE_DEPLOY_TIMEOUT_SECONDS must be greater than zero." >&2
        exit 1
        ;;
esac

command -v docker >/dev/null 2>&1 || {
    printf '%s\n' "Docker is required to deploy Source." >&2
    exit 1
}
command -v curl >/dev/null 2>&1 || {
    printf '%s\n' "curl is required for the final health check." >&2
    exit 1
}

./scripts/preflight.sh

printf '%s\n' "Building and updating Source services."
docker compose --env-file .env up -d --build --remove-orphans

services=$(docker compose --env-file .env config --services)
deadline=$(( $(date +%s) + deploy_timeout ))

while :; do
    ready=1
    for service in $services; do
        container_id=$(docker compose --env-file .env ps --all -q "$service")
        if [ -z "$container_id" ]; then
            ready=0
            break
        fi

        state=$(docker inspect --format '{{.State.Status}} {{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$container_id")
        case "$state" in
            'running healthy'|'running none') ;;
            *)
                ready=0
                break
                ;;
        esac
    done

    [ "$ready" -eq 0 ] || break
    if [ "$(date +%s)" -ge "$deadline" ]; then
        printf '%s\n' "Source services did not become healthy within ${deploy_timeout} seconds." >&2
        docker compose --env-file .env ps >&2
        exit 1
    fi
    sleep 2
done

admin_port=$(sed -n 's/^SOURCE_ADMIN_PORT=//p' .env | tail -n 1)
admin_port=${admin_port:-9090}
curl --fail --silent --show-error --noproxy '*' \
    --connect-timeout 5 --max-time 15 \
    "http://127.0.0.1:${admin_port}/healthz" >/dev/null

docker compose --env-file .env ps
printf '%s\n' "Source deployment completed and passed its health checks."
