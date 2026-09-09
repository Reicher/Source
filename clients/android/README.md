# Source Client for Android

This directory is an independently buildable Android application inside the
Source monorepo. It intentionally implements only the first vertical slices:

1. create and password-protect a local Source identity;
2. discover `_source._tcp` Nodes on the LAN;
3. scan and validate the Node's protocol-v1 invitation QR;
4. complete the existing mutual Ed25519 pairing handshake;
5. persist Node trust and the one-time client credential in an encrypted vault;
6. detect loss of the local network and reconnect automatically after DNS-SD
   rediscovery and a fresh signed Node identity proof.
7. keep one encrypted conversation and run it either through a local on-device
   model or the authenticated Source Node chat API;
8. back up the encrypted conversation as an opaque Source snapshot.

The app has no cloud SDK, account service, telemetry, analytics, or background
service. Its ZXing QR decoder and LiteRT-LM runtime run on-device and have no
runtime service or internet integration.

## Local client model

Source Client uses the CPU-compatible, Apache-2.0 Qwen3 0.6B no-think INT4
LiteRT-LM model. The checksum-pinned model is provisioned before the Android
build and packaged in the APK. There is deliberately no model download or
model-management UI inside Source Client. The model artifact and its license
are published by the [LiteRT Community](https://huggingface.co/litert-community/Qwen3-0.6B-int4).

`Auto` uses an authenticated Node when one is connected and otherwise uses
`This device`. A selected Node that becomes unavailable also falls back to the
device. Node connectivity continues independently for encrypted backup even
when `This device` is selected for AI.

## Security model

- The password is processed locally with PBKDF2-HMAC-SHA256 and is never stored
  or sent to a Node.
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
./gradlew testDebugUnitTest lintDebug assembleDebug
```

The provisioning step downloads and verifies the Apache-2.0 Qwen3 0.6B
no-think model used for fully offline Client inference. The model is packaged
in the APK as `source-client-model.litertlm`; the running app never downloads a
model or contacts an AI service.

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Manual vertical-slice check

1. Configure `SOURCE_BIND_IP` and `SOURCE_GATEWAY_HOST` to the Node's reserved
   LAN address, start the Compose deployment, and initialize the Node.
2. Export `artifacts/source-node-ca.crt`; the Node embeds this public certificate
   in each pairing QR automatically.
3. Install and open the debug APK, then create the local identity.
4. Create a pairing invitation on the Node. The client should discover the Node,
   show **Anslut**, request camera access, scan the QR, and show **Ansluten**.
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
directly. Running `node/src/server.mjs` directly keeps the safe loopback and
discovery-disabled defaults and does not provide the required HTTPS gateway.
A non-Compose LAN deployment must add a local-CA HTTPS gateway, configure an
explicit LAN bind address and pairing URL, and opt in to direct discovery.
