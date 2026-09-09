#!/usr/bin/env sh
set -eu

if [ "$(id -u)" -eq 0 ]; then
    printf '%s\n' "Run this as the intended Source operator, not directly as root." >&2
    exit 1
fi

repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
configured_root=$(sed -n 's/^SOURCE_DATA_ROOT=//p' "$repo_root/.env" 2>/dev/null | tail -n 1)
data_root=${SOURCE_DATA_ROOT:-${configured_root:-$repo_root/data}}
case "$data_root" in
    /*) ;;
    *) data_root="$repo_root/$data_root" ;;
esac
printf '%s\n' "This creates Source Node state below $data_root."
printf '%s\n' "It does not start containers, alter router forwarding, or expose a public port."
printf '%s' "Type SOURCE to continue: "
read -r answer
[ "$answer" = "SOURCE" ] || exit 1

install -d -m 0700 \
    "$data_root" \
    "$data_root/node" \
    "$data_root/vaults" \
    "$data_root/gateway" \
    "$data_root/gateway/data" \
    "$data_root/gateway/config" \
    "$data_root/ollama"
install -d -m 0755 "$repo_root/artifacts"

printf '%s\n' "Source directories created."
printf '%s\n' "Next: review .env, run scripts/preflight.sh, then provision the model."
printf '%s\n' "Repository: $repo_root"
