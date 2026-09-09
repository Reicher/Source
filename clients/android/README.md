# Source Client for Android

This directory is an independently buildable Android application inside the
Source monorepo. It intentionally implements only the first vertical slice:

1. create and password-protect a local Source identity;
2. discover `_source._tcp` Nodes on the LAN;
3. scan and validate the Node's protocol-v1 invitation QR;
4. complete the existing mutual Ed25519 pairing handshake;
5. persist Node trust and the one-time client credential in an encrypted vault;
6. detect loss of the local network and reconnect automatically after DNS-SD
   rediscovery and a fresh signed Node identity proof.

The app has no cloud SDK, account service, telemetry, analytics, or background
service. The bundled ML Kit barcode model runs on-device and works offline.

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
- HTTPS verification is never bypassed. Before pairing, install the local CA
  produced by `../../scripts/export-ca.sh` as a user CA certificate on the
  Android device. Android's network security policy trusts system and explicitly
  installed user anchors, but never cleartext traffic.

The first version requires Android 13 (API 33) or newer so the platform Ed25519
provider is available consistently.

## Build and test

With Android SDK 36 installed:

```sh
cd clients/android
./gradlew testDebugUnitTest lintDebug assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Manual vertical-slice check

1. Configure `SOURCE_BIND_IP` and `SOURCE_GATEWAY_HOST` to the Node's reserved
   LAN address, start the Compose deployment, and initialize the Node.
2. Export and install `artifacts/source-node-ca.crt` on the Android device.
3. Install and open the debug APK, then create the local identity.
4. Create a pairing invitation on the Node. The client should discover the Node,
   show **Anslut**, request camera access, scan the QR, and show **Ansluten**.
5. Force-stop and reopen the app. After the local password is entered, it should
   rediscover and authenticate without another QR scan.
6. Turn Wi-Fi off and on, and restart the Node. The state should move through
   unavailable and return to connected without losing trust.

The Compose `discovery` service uses host networking so mDNS can reach the LAN.
On Docker Desktop, host networking must be enabled; native Linux supports it
directly. A Node started directly with `node/src/server.mjs` advertises itself
without the sidecar.
