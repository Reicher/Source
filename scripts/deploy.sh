#!/usr/bin/env sh
set -eu

repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$repo_root"

[ "$(uname -s)" = Linux ] || {
    printf '%s\n' "Source deployment needs a Linux host for mDNS." >&2
    exit 1
}
command -v docker >/dev/null 2>&1 || {
    printf '%s\n' "Docker is required." >&2
    exit 1
}
command -v curl >/dev/null 2>&1 || {
    printf '%s\n' "curl is required for health checks." >&2
    exit 1
}
command -v python3 >/dev/null 2>&1 || {
    printf '%s\n' "Python 3 is required to provision the Source model." >&2
    exit 1
}

SOURCE_LAN_ADDRESS=${SOURCE_LAN_ADDRESS:-}
python3 scripts/validate_lan_address.py "$SOURCE_LAN_ADDRESS"
SOURCE_DATA_ROOT=${SOURCE_DATA_ROOT:-"$HOME/.local/share/source-v1"}
case "$SOURCE_DATA_ROOT" in
    /*) ;;
    *) printf '%s\n' "SOURCE_DATA_ROOT must be an absolute path." >&2; exit 1 ;;
esac
SOURCE_MODEL_ROOT=${SOURCE_MODEL_ROOT:-"$SOURCE_DATA_ROOT/models"}
case "$SOURCE_MODEL_ROOT" in
    /*) ;;
    *) printf '%s\n' "SOURCE_MODEL_ROOT must be an absolute path." >&2; exit 1 ;;
esac
export SOURCE_LAN_ADDRESS SOURCE_DATA_ROOT SOURCE_MODEL_ROOT
SOURCE_UID=$(id -u)
SOURCE_GID=$(id -g)
SOURCE_REVISION=${SOURCE_REVISION:-$(git rev-parse HEAD)}
export SOURCE_UID SOURCE_GID SOURCE_REVISION

mkdir -p "$SOURCE_DATA_ROOT" "$SOURCE_MODEL_ROOT"
chmod 700 "$SOURCE_DATA_ROOT" "$SOURCE_MODEL_ROOT"
python3 scripts/provision_models.py source
docker compose config --quiet
docker compose up -d --build

deadline=$(( $(date +%s) + 600 ))
while :; do
    container_id=$(docker compose ps -q source)
    model_container_id=$(docker compose ps -q source-model)
    if [ -n "$container_id" ] && \
        [ -n "$model_container_id" ] && \
        [ "$(docker inspect --format '{{.State.Status}}' "$container_id")" = running ] && \
        [ "$(docker inspect --format '{{.State.Status}}' "$model_container_id")" = running ] && \
        curl --fail --silent --output /dev/null --noproxy '*' \
            --connect-timeout 3 --max-time 10 http://127.0.0.1:8082/health && \
        curl --fail --silent --output /dev/null --noproxy '*' \
            --connect-timeout 3 --max-time 10 http://127.0.0.1:8081/ && \
        curl --fail --silent --insecure --output /dev/null --noproxy '*' \
            --connect-timeout 3 --max-time 10 \
            --cert "$SOURCE_DATA_ROOT/pairing/source.crt" \
            --key "$SOURCE_DATA_ROOT/pairing/source.key" \
            "https://$SOURCE_LAN_ADDRESS:8443/healthz"; then
        docker compose ps
        printf 'Source revision %s is healthy.\n' "$SOURCE_REVISION"
        exit 0
    fi
    if [ "$(date +%s)" -ge "$deadline" ]; then
        printf '%s\n' "Source and its local model did not become healthy within 600 seconds." >&2
        docker compose ps >&2
        docker compose logs --tail=80 source source-model >&2
        exit 1
    fi
    sleep 2
done
