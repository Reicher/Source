#!/bin/sh
set -eu

project_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
model_dir="$project_dir/.models/client"
model_file="$model_dir/Qwen_Qwen3.5-4B-Q4_K_M.gguf"
download_file="$model_file.download"
model_revision="4168f45a16a1290d65a4ec0fa312ae917a4c15d6"
model_sha256="13c16f426047e2de38cd075bdade4a7bcbc8c774384876f677740cda65f8a983"
model_url="https://huggingface.co/bartowski/Qwen_Qwen3.5-4B-GGUF/resolve/$model_revision/Qwen_Qwen3.5-4B-Q4_K_M.gguf"
split_prefix="$model_dir/source-client-model.gguf.part."

mkdir -p "$model_dir"
if [ ! -f "$model_file" ]; then
    echo "Downloading pinned Qwen3.5-4B Q4_K_M (approximately 3.0 GB; resumable)..."
    curl -fL -C - "$model_url" -o "$download_file"
    mv "$download_file" "$model_file"
fi

actual_sha256=$(shasum -a 256 "$model_file" | awk '{print $1}')
if [ "$actual_sha256" != "$model_sha256" ]; then
    echo "Source Client model checksum mismatch: $actual_sha256" >&2
    exit 1
fi

rm -f "${split_prefix}"*
split -b 1100m -d -a 2 "$model_file" "$split_prefix"
set -- "${split_prefix}"*
[ "$#" -eq 3 ] || {
    echo "Expected exactly three model parts, got $#" >&2
    exit 1
}

index=1
for part in "$@"; do
    asset_dir="$project_dir/clients/android/source_ai_model_$index/src/main/assets"
    mkdir -p "$asset_dir"
    destination="$asset_dir/source-client-model-$index-of-3.gguf.part"
    mv "$part" "$destination"
    actual_part_sha256=$(shasum -a 256 "$destination" | awk '{print $1}')
    actual_part_bytes=$(wc -c < "$destination" | tr -d ' ')
    case "$index" in
        1) expected_part_sha256="dfb7019e3435bf5015594b5297a66853fc646659ec1c52e408f71f96ae7a3d01"; expected_part_bytes=1153433600 ;;
        2) expected_part_sha256="79a9e69f1ffef9aab5af7d1678d8b2658b14409922e017f5264126bd1a59b120"; expected_part_bytes=1153433600 ;;
        3) expected_part_sha256="6391ae6c31169a435ac37a6c04d4f3f6887e6c08770436abde14091c83537761"; expected_part_bytes=706160608 ;;
    esac
    if [ "$actual_part_sha256" != "$expected_part_sha256" ] || [ "$actual_part_bytes" != "$expected_part_bytes" ]; then
        printf 'Client model part %s does not match the pinned manifest.\n' "$index" >&2
        exit 1
    fi
    printf '%s  %s\n' "$actual_part_sha256" "$(basename "$destination")"
    index=$((index + 1))
done

echo "Source Client model was verified and split across three install-time asset packs."
