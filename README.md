# Source / Self

Source / Self is being rebuilt as V1. [SOURCE_SELF_V1_UPDATED.md](SOURCE_SELF_V1_UPDATED.md) is the product specification and source of truth. The previous prototype is preserved in Git history at the `prototype-final-2026-09-21` tag.

Issue #69 establishes empty application shells. Source is a local Go server; Self is a native Android app in Kotlin. Pairing, UI, storage, and AI inference come later.

## Build and start Source

Go 1.25 or newer is required.

```sh
cd source
go test ./...
go build ./...
go run .
```

Source listens on `127.0.0.1:8080` by default. `GET /healthz` returns HTTP 204. Pass `-listen 127.0.0.1:PORT` to use another local port.

## Build and start Self

Install Java 17 and Android SDK 36. The Gradle wrapper installs the required Gradle version.

```sh
cd self/android
./gradlew :app:testDebugUnitTest :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.source.self/.MainActivity
```

The app opens as a blank Android activity. The debug APK builds without model files so that CI and initial development stay fast.

## Provision the initial models

The initial models are Qwen 3.5 4B Q4_K_M for Self and Qwen 3.5 9B Q5_K_M for Source. Exact revisions, sizes, and SHA-256 checksums are pinned in [models/models.json](models/models.json). They are starting choices, not permanent architecture.

From the repository root, run:

```sh
python3 scripts/provision_models.py --check
python3 scripts/provision_models.py self
python3 scripts/provision_models.py source
python3 scripts/provision_models.py --verify
```

Downloads resume from `.download` files after interruption and are verified before use. Source's model is placed in `data/models/`. Self's verified model is split into three install-time Android asset packs under `self/android/model_pack_*/src/main/assets/`. These large files are ignored by Git. Once Self's model is provisioned, `cd self/android && ./gradlew :app:bundleDebug` builds an Android App Bundle containing the packs. Installing that bundle and its packs on a device requires an APK set generated with bundletool; a plain debug APK does not include the model packs.

To install the bundle and its model packs on one connected Android device, run `./scripts/install_self.sh` from the repository root. Set `ANDROID_SERIAL` if multiple devices are connected. The script verifies the Self model, builds the bundle, downloads a pinned bundletool, installs the generated APK set, and starts Self.

CI validates the empty projects and the pinned manifest without downloading either multi-gigabyte model. No model is loaded or used for inference in issue #69.
