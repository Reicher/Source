#!/usr/bin/env sh
set -eu

repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
android_dir="$repo_root/self/android"
bundle="$android_dir/app/build/outputs/bundle/debug/app-debug.aab"
apks="$android_dir/app/build/outputs/apks/debug/self-debug.apks"
bundletool_version=1.18.3
bundletool_sha256=a099cfa1543f55593bc2ed16a70a7c67fe54b1747bb7301f37fdfd6d91028e29
bundletool="$repo_root/.tools/bundletool-all-$bundletool_version.jar"

if [ -n "${ADB:-}" ]; then
    adb=$ADB
elif command -v adb >/dev/null 2>&1; then
    adb=$(command -v adb)
elif [ -x "${ANDROID_HOME:-}/platform-tools/adb" ]; then
    adb="$ANDROID_HOME/platform-tools/adb"
elif [ -x "$HOME/Library/Android/sdk/platform-tools/adb" ]; then
    adb="$HOME/Library/Android/sdk/platform-tools/adb"
else
    printf '%s\n' 'Android platform-tools (adb) is required.' >&2
    exit 1
fi

if [ -z "${ANDROID_HOME:-}" ] && [ -d "$HOME/Library/Android/sdk" ]; then
    ANDROID_HOME="$HOME/Library/Android/sdk"
    export ANDROID_HOME
fi

if [ -n "${ANDROID_SERIAL:-}" ]; then
    serial=$ANDROID_SERIAL
else
    serial=$(
        "$adb" devices |
            awk 'NR > 1 && $2 == "device" { print $1 }'
    )
    device_count=$(printf '%s\n' "$serial" | awk 'NF { count++ } END { print count + 0 }')
    if [ "$device_count" -ne 1 ]; then
        printf '%s\n' 'Connect one authorized Android device or set ANDROID_SERIAL.' >&2
        exit 1
    fi
fi

python3 "$repo_root/scripts/provision_models.py" self --verify

mkdir -p "$(dirname -- "$bundletool")" "$(dirname -- "$apks")"
if [ ! -f "$bundletool" ]; then
    curl -fL -C - \
        "https://github.com/google/bundletool/releases/download/$bundletool_version/bundletool-all-$bundletool_version.jar" \
        -o "$bundletool.download"
    mv "$bundletool.download" "$bundletool"
fi
if command -v shasum >/dev/null 2>&1; then
    actual_sha256=$(shasum -a 256 "$bundletool" | awk '{print $1}')
else
    actual_sha256=$(sha256sum "$bundletool" | awk '{print $1}')
fi
if [ "$actual_sha256" != "$bundletool_sha256" ]; then
    printf '%s\n' 'bundletool SHA-256 mismatch.' >&2
    exit 1
fi

(cd "$android_dir" && ./gradlew :app:bundleDebug)
java -jar "$bundletool" build-apks \
    --bundle="$bundle" \
    --output="$apks" \
    --adb="$adb" \
    --device-id="$serial" \
    --connected-device \
    --local-testing \
    --overwrite
java -jar "$bundletool" install-apks \
    --apks="$apks" \
    --adb="$adb" \
    --device-id="$serial"
"$adb" -s "$serial" shell am start -W -n com.source.self/.MainActivity
