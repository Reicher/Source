#!/usr/bin/env sh
set -eu

project_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
manifest_file="$project_dir/models/source-ai-models.json"
model_dir="$project_dir/.models/client"
manifest_values=$(mktemp "${TMPDIR:-/tmp}/source-client-model.XXXXXX")
split_dir=

cleanup() {
    rm -f "$manifest_values"
    if [ -n "$split_dir" ] && [ -d "$split_dir" ]; then
        rm -rf "$split_dir"
    fi
}
trap cleanup EXIT HUP INT TERM

command -v python3 >/dev/null 2>&1 || {
    printf '%s\n' "python3 is required to read $manifest_file." >&2
    exit 1
}
sha256_file() {
    if command -v shasum >/dev/null 2>&1; then
        shasum -a 256 "$1" | awk '{print $1}'
    elif command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | awk '{print $1}'
    else
        printf '%s\n' "shasum or sha256sum is required to verify the Source Client model." >&2
        return 1
    fi
}

python3 - "$manifest_file" > "$manifest_values" <<'PY'
import json
import re
import sys

path = sys.argv[1]
try:
    with open(path, encoding="utf-8") as source:
        manifest = json.load(source)
    client = manifest["client"]
    parts = client["parts"]
    required_strings = ("repository", "revision", "file", "sha256", "family", "parameters", "quantization")
    for key in required_strings:
        if not isinstance(client[key], str) or not client[key] or "\n" in client[key]:
            raise ValueError(f"client.{key} must be a non-empty single-line string")
    if not isinstance(client["bytes"], int) or client["bytes"] <= 0:
        raise ValueError("client.bytes must be a positive integer")
    if len(parts) != 3:
        raise ValueError("client.parts must contain the three Android asset-pack parts")
    for part in parts:
        if not isinstance(part["file"], str) or not part["file"] or "\n" in part["file"]:
            raise ValueError("each client part file must be a non-empty single-line string")
        if not isinstance(part["bytes"], int) or part["bytes"] <= 0:
            raise ValueError("each client part size must be a positive integer")
        if not isinstance(part["sha256"], str) or not re.fullmatch(r"[0-9a-f]{64}", part["sha256"]):
            raise ValueError("each client part sha256 must be a lowercase SHA-256 digest")
    if not re.fullmatch(r"[0-9a-f]{64}", client["sha256"]):
        raise ValueError("client.sha256 must be a lowercase SHA-256 digest")
    if sum(part["bytes"] for part in parts) != client["bytes"]:
        raise ValueError("client part sizes do not add up to client.bytes")
except (OSError, KeyError, TypeError, ValueError, json.JSONDecodeError) as error:
    print(f"Invalid Source AI model manifest {path}: {error}", file=sys.stderr)
    raise SystemExit(1)

for value in (
    client["repository"],
    client["revision"],
    client["file"],
    client["bytes"],
    client["sha256"],
    f'{client["family"]}-{client["parameters"]} {client["quantization"]}',
    len(parts),
):
    print(value)
for part in parts:
    print(f'{part["file"]}\t{part["bytes"]}\t{part["sha256"]}')
PY

model_repository=$(sed -n '1p' "$manifest_values")
model_revision=$(sed -n '2p' "$manifest_values")
model_name=$(sed -n '3p' "$manifest_values")
model_bytes=$(sed -n '4p' "$manifest_values")
model_sha256=$(sed -n '5p' "$manifest_values")
model_label=$(sed -n '6p' "$manifest_values")
part_count=$(sed -n '7p' "$manifest_values")
model_file="$model_dir/$model_name"
download_file="$model_file.download"
model_url="https://huggingface.co/$model_repository/resolve/$model_revision/$model_name"

part_value() {
    awk -F '\t' -v row="$((7 + $1))" -v column="$2" 'NR == row { print $column }' "$manifest_values"
}

parts_are_current() {
    current_index=1
    while [ "$current_index" -le "$part_count" ]; do
        current_file=$(part_value "$current_index" 1)
        current_bytes=$(part_value "$current_index" 2)
        current_sha256=$(part_value "$current_index" 3)
        current_path="$project_dir/clients/android/source_ai_model_$current_index/src/main/assets/$current_file"
        [ -f "$current_path" ] || return 1
        actual_bytes=$(wc -c < "$current_path" | tr -d ' ')
        [ "$actual_bytes" = "$current_bytes" ] || return 1
        actual_sha256=$(sha256_file "$current_path")
        [ "$actual_sha256" = "$current_sha256" ] || return 1
        current_index=$((current_index + 1))
    done
}

printf 'Reading Source Client model definition from %s.\n' "$manifest_file"
if parts_are_current; then
    printf '%s\n' "The $model_label Android asset-pack parts are already provisioned and verified."
    exit 0
fi

mkdir -p "$model_dir"
if [ ! -f "$model_file" ]; then
    command -v curl >/dev/null 2>&1 || {
        printf '%s\n' "curl is required to download the Source Client model." >&2
        exit 1
    }
    printf 'Downloading pinned %s model (approximately 3.0 GB; resumable).\n' "$model_label"
    if ! curl -fL -C - "$model_url" -o "$download_file"; then
        printf '%s\n' "Model download failed; rerun the command to resume it." >&2
        exit 1
    fi
    mv "$download_file" "$model_file"
fi

actual_bytes=$(wc -c < "$model_file" | tr -d ' ')
if [ "$actual_bytes" != "$model_bytes" ]; then
    printf 'Source Client model size mismatch: expected %s bytes, got %s.\n' "$model_bytes" "$actual_bytes" >&2
    exit 1
fi
printf '%s\n' "Verifying the complete Source Client model."
actual_sha256=$(sha256_file "$model_file")
if [ "$actual_sha256" != "$model_sha256" ]; then
    printf 'Source Client model checksum mismatch: expected %s, got %s.\n' "$model_sha256" "$actual_sha256" >&2
    exit 1
fi

split_dir=$(mktemp -d "$model_dir/split.XXXXXX")
first_part_bytes=$(part_value 1 2)
split -b "$first_part_bytes" -d -a 2 "$model_file" "$split_dir/part."
set -- "$split_dir"/part.*
if [ "$#" -ne "$part_count" ]; then
    printf 'Expected %s model parts, got %s.\n' "$part_count" "$#" >&2
    exit 1
fi

index=1
for part in "$@"; do
    expected_file=$(part_value "$index" 1)
    expected_bytes=$(part_value "$index" 2)
    expected_sha256=$(part_value "$index" 3)
    actual_part_bytes=$(wc -c < "$part" | tr -d ' ')
    actual_part_sha256=$(sha256_file "$part")
    if [ "$actual_part_sha256" != "$expected_sha256" ] || [ "$actual_part_bytes" != "$expected_bytes" ]; then
        printf 'Client model part %s does not match %s.\n' "$index" "$manifest_file" >&2
        exit 1
    fi
    asset_dir="$project_dir/clients/android/source_ai_model_$index/src/main/assets"
    mkdir -p "$asset_dir"
    mv "$part" "$asset_dir/$expected_file"
    printf '%s  %s\n' "$actual_part_sha256" "$expected_file"
    index=$((index + 1))
done

printf '%s\n' "Source Client model was verified and split across three install-time asset packs."
