#!/usr/bin/env sh
set -eu

repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$repo_root"

printf '%s\n' "This temporarily allows outbound network access to download the configured local model."
printf '%s\n' "The normal Ollama service remains on an internal-only Docker network."
printf '%s' "Type MODEL to continue: "
read -r answer
[ "$answer" = "MODEL" ] || exit 1

container_name=source-model-provision
cleanup() {
    docker rm -f "$container_name" >/dev/null 2>&1 || true
}
trap cleanup EXIT HUP INT TERM

docker compose --env-file .env --profile provision run --rm \
    --name "$container_name" model-pull
printf '%s\n' "Model provisioning finished. Start Source without the provision profile."
