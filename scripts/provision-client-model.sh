#!/bin/sh
set -eu

project_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
asset_dir="$project_dir/clients/android/app/src/main/assets"
model_file="$asset_dir/source-client-model.litertlm"
download_file="$model_file.download"
model_sha256="2df6821ec12702dafd33915e7a1a1adc7c4b053f3672fd9555dfaf3a114c4139"
model_url="https://huggingface.co/litert-community/Qwen3-0.6B-int4/resolve/6aa2daf8aba4aa456797fb8040b36a3948bcfda7/qwen3_0.6b_nothink_q4_block32_ekv1280.litertlm"

mkdir -p "$asset_dir"
if [ ! -f "$model_file" ]; then
    echo "Downloading Qwen3 0.6B no-think for Source Client (approximately 347 MB; resumable)..."
    curl -fL -C - "$model_url" -o "$download_file"
    mv "$download_file" "$model_file"
fi

actual_sha256=$(shasum -a 256 "$model_file" | awk '{print $1}')
if [ "$actual_sha256" != "$model_sha256" ]; then
    echo "Source Client model checksum mismatch: $actual_sha256" >&2
    exit 1
fi

echo "Source Client model is ready at: $model_file"
