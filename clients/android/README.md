# Source Client for Android

This directory is an independently buildable Android application inside the
Source monorepo. It intentionally implements only the first vertical slices:

1. create multiple password-protected local Source users, switch between them,
   and log out without deleting their separate encrypted data;
2. discover `_source._tcp` Nodes on the LAN;
3. scan and validate the Node's protocol-v1 invitation QR;
4. complete the existing mutual Ed25519 pairing handshake;
5. persist Node trust and the one-time client credential in an encrypted vault;
6. model discovery, pairing, recovery, authentication, connection loss, and
   reconnect as explicit connection states;
7. keep one encrypted conversation and run it either through a local on-device
   model or the authenticated Source Node chat API;
8. store, version, reconcile, back up, and restore chat through a generic
   encrypted Source-data path that additional datasets can reuse.

The app has no cloud SDK, account service, telemetry, analytics, or background
service. Its QR decoder and AI runtimes run on-device and have no runtime
service or internet integration.

## Local client model

Source Client uses the pinned Qwen3.5-4B Q4_K_M GGUF and llama.cpp.
The model is delivered in three install-time Play Asset Delivery packs and is
read directly as one virtual seekable file, without joining it or copying it to
private app storage. There is deliberately no model download or model-management
UI inside Source Client. Exact model revisions, sizes, and checksums are recorded
in `../../models/source-ai-models.json`. Android and Source Node use llama.cpp
v0.4.0 at commit `5266f24da75dc449bd56cbed7addb9c8e4a6a73e`.

The local engine remains loaded across foreground requests, streams only the
visible answer, supports cancellation during decode, and truncates complete
conversation history against the official chat template's real token count.
Android memory-pressure callbacks release the model after the UI is hidden.
Both the local engine and the authenticated Node adapter implement the same
Source AI runtime contract. A runtime router owns placement and safe fallback,
so chat consumes the same started/delta/completed/failed stream regardless of
where inference runs. Source adds no system prompt and disables model reasoning
through each runtime's supported controls.

`Auto` uses an authenticated Node when one is connected and otherwise uses
`This device`. A selected Node that becomes unavailable also falls back to the
device. Node connectivity continues independently for encrypted backup even
when `This device` is selected for AI.

## Security model

- The password is processed locally with PBKDF2-HMAC-SHA256 and is never stored
  or sent to a Node.
- Every local user has a separate identity, vault, trusted-Node credentials, and
  conversation. Existing single-user installations migrate in place.
- Each paired Node gets a client-generated recovery key and data key. The app
  shows the recovery key while connected; an administrator-approved recovery
  QR plus that key can attach a replacement client to the existing Node user
  and decrypt its snapshots.
- The Ed25519 Client private key and trusted-Node credentials live only in the
  encrypted local vault. A random vault key is password-wrapped and then wrapped
  again by a non-exportable Android Keystore AES key.
- Android backup and device transfer are disabled for all application data.
- DNS-SD metadata is only a routing hint. Pairing verifies the QR-bound Node
  signature; reconnect verifies `POST /api/v1/identity/challenge` with the
  persisted Node public key.
- HTTPS verification is never bypassed. The QR contains the Node's public local
  CA certificate, which the app uses as a private trust anchor only for that
  Node. The CA is stored in the encrypted vault for reconnects; no Android
  system certificate or security-setting change is required.

The first version requires Android 13 (API 33) or newer so the platform Ed25519
provider is available consistently.

## Build and test

With Android SDK 36 installed:

```sh
./scripts/provision-client-model.sh
cd clients/android
./gradlew testInstrumentedUnitTest lintDebug assembleDebug
./gradlew connectedInstrumentedAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.sourceFullModelProbe=true \
  -Pandroid.testInstrumentationRunnerArguments.sourceQwenRuntime=true
```

Instrumentation uses the separate `com.source.client.instrumented` application
ID, so running it on a physical device cannot uninstall or clear the normal
`com.source.client` app.

CI compiles the instrumentation APK so device-test regressions fail at build
time. The vault and model tests require the platform crypto implementation,
the provisioned 3 GB model, and a real ARM64 device. The full command above is
therefore a required device smoke test before every release and before merging
changes to vault, model packaging, JNI, or local inference.
`sourceQwenBenchmark=true` remains an optional benchmark.

The provisioning step downloads and SHA-256 verifies the pinned client model.
The running app never downloads a model or contacts an AI service.

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Manual vertical-slice check

1. Configure `SOURCE_BIND_IP` and `SOURCE_GATEWAY_HOST` to the Node's reserved
   LAN address, start the Compose deployment, and initialize the Node.
2. Export `artifacts/source-node-ca.crt`; the Node embeds this public certificate
   in each pairing QR automatically.
3. Install and open the debug APK, then create or select a local user and log in.
4. Create a pairing invitation on the Node. The client should discover the Node,
   show **Connect**, request camera access, scan the QR, and show **Connected**.
5. Force-stop and reopen the app. After the local password is entered, it should
   rediscover and authenticate without another QR scan.
6. Turn Wi-Fi off and on, and restart the Node. The state should move through
   unavailable and return to connected without losing trust;
7. Select **This device**, turn off Wi-Fi, and send a message. Restart the app,
   unlock it, and confirm that both messages remain.
8. Reconnect, select the Node (or **Auto**), send another message, and confirm
   that a `source-client` snapshot appears on the Node.

The Compose `discovery` service uses host networking so mDNS can reach the LAN.
On Docker Desktop, host networking must be enabled; native Linux supports it
directly. Running `go run ./node/cmd/source-node` directly keeps the safe loopback and
discovery-disabled defaults and does not provide the required HTTPS gateway.
A non-Compose LAN deployment must add a local-CA HTTPS gateway, configure an
explicit LAN bind address and pairing URL, and opt in to direct discovery.
