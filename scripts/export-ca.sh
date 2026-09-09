#!/usr/bin/env sh
set -eu

repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
configured_root=$(sed -n 's/^SOURCE_DATA_ROOT=//p' "$repo_root/.env" 2>/dev/null | tail -n 1)
data_root=${SOURCE_DATA_ROOT:-${configured_root:-$repo_root/data}}
source_path="$data_root/gateway/data/caddy/pki/authorities/local/root.crt"
destination="$repo_root/artifacts/source-node-ca.crt"

if [ ! -f "$source_path" ]; then
    printf '%s\n' "Caddy root certificate does not exist yet: $source_path" >&2
    printf '%s\n' "Start the Source gateway once, then retry." >&2
    exit 1
fi

mkdir -p "$repo_root/artifacts"
install -m 0644 "$source_path" "$destination"
printf '%s\n' "$destination"
printf '%s\n' "This public CA is embedded in pairing QRs; clients do not install it globally."
printf '%s\n' "Never copy Caddy's root.key."
