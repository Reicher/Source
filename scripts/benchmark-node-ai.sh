#!/usr/bin/env sh
set -eu

repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
url=${LLAMA_BENCHMARK_URL:-http://127.0.0.1:18080}
model=${LLAMA_MODEL:-source-qwen3.5-9b}
maximum_tokens=${LLAMA_BENCHMARK_MAX_TOKENS:-2048}

case "$model" in
    *[!A-Za-z0-9._-]*) printf 'Invalid LLAMA_MODEL.\n' >&2; exit 1 ;;
esac
case "$maximum_tokens" in
    ''|*[!0-9]*) printf 'LLAMA_BENCHMARK_MAX_TOKENS must be an integer.\n' >&2; exit 1 ;;
esac

response=$(curl -fsS \
    -H 'content-type: application/json' \
    -d "{\"model\":\"$model\",\"messages\":[{\"role\":\"user\",\"content\":\"hej\"}],\"stream\":false,\"temperature\":0.6,\"top_p\":0.9,\"seed\":42,\"max_tokens\":$maximum_tokens}" \
    "$url/v1/chat/completions")

printf '%s' "$response" | (cd "$repo_root/node" && go run ./cmd/benchmark-summary)
