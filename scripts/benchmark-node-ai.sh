#!/usr/bin/env sh
set -eu

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

printf '%s' "$response" | node -e '
let input = "";
process.stdin.on("data", (chunk) => { input += chunk; });
process.stdin.on("end", () => {
  const body = JSON.parse(input);
  const choice = body.choices?.[0];
  const message = choice?.message ?? {};
  const timings = body.timings ?? {};
  const output = {
    finishReason: choice?.finish_reason ?? null,
    promptTokens: body.usage?.prompt_tokens ?? timings.prompt_n ?? null,
    outputTokens: body.usage?.completion_tokens ?? timings.predicted_n ?? null,
    promptTokensPerSecond: timings.prompt_per_second ?? null,
    outputTokensPerSecond: timings.predicted_per_second ?? null,
    answerBytes: Buffer.byteLength(message.content ?? ""),
    reasoningPresent: typeof message.reasoning_content === "string" && message.reasoning_content.length > 0,
    model: body.model ?? null,
  };
  process.stdout.write(`${JSON.stringify(output, null, 2)}\n`);
});
'
