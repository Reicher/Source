# Source AI migration status

## Fixed target

- Client: Qwen3.5-4B Q4_K_M, 4,096-token initial context.
- Node: Qwen3.5-9B Q5_K_M, 8,192-token initial context.
- Runtime: llama.cpp v0.4.0 at commit
  `5266f24da75dc449bd56cbed7addb9c8e4a6a73e` on both systems.
- Prompt policy: `none-v1`. Only explicit user and assistant messages are
  passed to the official model chat template. Source adds no system prompt.

The exact model revisions, byte sizes and SHA-256 values are recorded in
`models/source-ai-models.json`.

## Implemented

- A runtime-neutral Source AI v1 contract covers messages, run identifiers,
  streaming events, cancellation and advertised `text`/`vision` capabilities.
- Source Node now targets a private llama.cpp server instead of Ollama. Its
  image and model provisioning are pinned, and disconnected HTTP clients abort
  their active model request.
- The Android build contains three install-time Play Asset Delivery packs.
  The 3,013,027,808-byte GGUF is split into deterministic raw byte parts below
  the per-pack limit and verified during provisioning.
- Android's `AssetManager` parts are exposed to llama.cpp as one seekable
  virtual `FILE`. The model is read directly; it is not joined, converted or
  copied into app-private storage.
- The Android runtime now keeps the model loaded between foreground requests,
  streams visible answer tokens through the Source AI contract, supports native
  decode cancellation, performs token-based history truncation, and releases
  the model after Android reports that the UI is hidden.
- Client startup removes the former LiteRT model copy and its known XNNPACK
  cache files during upgrade; the encrypted vault and unrelated cache remain
  untouched.
- Source Node on `plattserver` now runs the pinned 9B model through the same
  llama.cpp revision. The LAN health endpoint advertises `llmAvailable: true`
  and `promptPolicy: none-v1`; the old Ollama container and adapter are removed.

## Device-spike result

Verified on a Samsung SM-S931B:

- clean install of the 3.1 GB AAB/APKS set;
- metadata and official Qwen chat-template load from all three PAD parts;
- full tensor load from the virtual stream;
- update install followed by another successful direct-read test;
- no extra Qwen model file in private app storage.

A seeded (`42`) end-to-end generation run using the official chat template,
four CPU threads and a 1,024-token test context measured:

- model load: 2.45 s;
- prompt evaluation: 0.79 s for 10 tokens;
- generation: 15 tokens in 1.54 s (9.74 tokens/s);
- natural stop, 53 output bytes;
- battery temperature immediately afterwards: 28.3 C.

This benchmark deliberately uses a smaller context than the 4,096-token
production target. Context size, sustained thermals and memory pressure still
need to be measured in the long-lived engine.

The PAD parts are byte ranges of one GGUF rather than independently valid
GGUF split files. This is intentional: llama.cpp's public split loader accepts
filesystem paths, while install-time PAD exposes assets through `AssetManager`.
The virtual stream avoids the extra model copy but cannot use filesystem mmap.

The unprompted Qwen3.5 baseline emitted a separate internal `reasoning_content`
phase and could spend hundreds of completion tokens before producing a short
answer. Source now disables reasoning explicitly through llama.cpp's official
chat-template/runtime controls on both targets: `--reasoning off` on Node and
Jinja `enable_thinking=false` on Android. This adds no system message, persona,
`/no_think` suffix, or other prompt text. The shared contract advertises
`reasoning: off`.

The Node measurements were made on an Intel Core i5-1250P (16 logical CPUs,
AVX2/FMA) with 15 GiB RAM and 4 GiB swap. The deployed service uses eight
threads, an 8,192-token context and a 13 GiB container limit. A final request
through the deployed Source adapter returned `Hej! Vad kan jag hjälpa dig med
idag? 😊` after 153 seconds and 758 generated tokens. This is correct baseline
behavior, but far too slow for a greeting; latency and thinking policy remain
an explicit product decision rather than a hidden prompt workaround.

## Remaining hardening work

1. Add cross-runtime behavior fixtures and end-to-end fallback tests, including
   cancellation, Node loss mid-stream and repeated-question regressions.
2. Let normal interactive use guide later latency and output-token tuning.
