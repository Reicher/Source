#!/usr/bin/env sh
set -eu

repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
configured_root=$(sed -n 's/^SOURCE_DATA_ROOT=//p' "$repo_root/.env" 2>/dev/null | tail -n 1)
data_root=${SOURCE_DATA_ROOT:-${configured_root:-$repo_root/data}}
backup_root=${SOURCE_BACKUP_ROOT:-$repo_root/backups}
timestamp=$(date -u +%Y%m%dT%H%M%SZ)
archive="$backup_root/source-node-$timestamp.tar.gz"

printf '%s\n' "Stop Source Node before taking a consistency-sensitive backup."
printf '%s\n' "The archive includes accounts, ciphertext, metadata, and the local CA."
printf '%s\n' "Downloaded model files are intentionally excluded."
printf '%s' "Type BACKUP to continue: "
read -r answer
[ "$answer" = "BACKUP" ] || exit 1

install -d -m 0700 "$backup_root"
tar --one-file-system -C "$data_root" -czf "$archive" ./gateway ./node ./vaults
chmod 0600 "$archive"
printf '%s\n' "$archive"
