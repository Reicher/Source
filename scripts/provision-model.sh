#!/usr/bin/env sh
set -eu

repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
configured_root=$(sed -n 's/^SOURCE_DATA_ROOT=//p' "$repo_root/.env" 2>/dev/null | tail -n 1)
data_root=${SOURCE_DATA_ROOT:-${configured_root:-$repo_root/data}}
case "$data_root" in
    /*) ;;
    *) data_root="$repo_root/$data_root" ;;
esac

model_dir="$data_root/models"
model_file="$model_dir/Qwen_Qwen3.5-9B-Q5_K_M.gguf"
download_file="$model_file.download"
model_revision="182be2fd6c7bc44887d88a91cb03ff009cc9f549"
model_sha256="a686d88ec1e6881f9bf161526826cd6d6874b7f0e80e0f79acf6144a132c5d7e"
model_url="https://huggingface.co/bartowski/Qwen_Qwen3.5-9B-GGUF/resolve/$model_revision/Qwen_Qwen3.5-9B-Q5_K_M.gguf"

printf '%s\n' "This downloads the pinned Qwen3.5-9B Q5_K_M model (about 7.1 GB)."
printf '%s' "Type MODEL to continue: "
read -r answer
[ "$answer" = "MODEL" ] || exit 1

mkdir -p "$model_dir"
if [ ! -f "$model_file" ]; then
    curl -fL -C - "$model_url" -o "$download_file"
    mv "$download_file" "$model_file"
fi

actual_sha256=$(shasum -a 256 "$model_file" | awk '{print $1}')
if [ "$actual_sha256" != "$model_sha256" ]; then
    printf 'Source Node model checksum mismatch: %s\n' "$actual_sha256" >&2
    exit 1
fi
printf 'Source Node model is ready at: %s\n' "$model_file"
