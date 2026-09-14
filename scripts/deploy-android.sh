#!/usr/bin/env sh
set -eu

repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
android_dir="$repo_root/clients/android"
manifest_file="$repo_root/models/source-ai-models.json"
package_name="com.source.client"
model_stamp="files/source-client-model.sha256"
bundletool_version="1.18.3"
bundletool_sha256="a099cfa1543f55593bc2ed16a70a7c67fe54b1747bb7301f37fdfd6d91028e29"
bundletool_dir="$repo_root/.deps/bundletool"
bundletool_jar="$bundletool_dir/bundletool-all-$bundletool_version.jar"
bundletool_download="$bundletool_jar.download"
debug_apk="$android_dir/app/build/outputs/apk/debug/app-debug.apk"
debug_bundle="$android_dir/app/build/outputs/bundle/debug/app-debug.aab"
device_apks="$android_dir/app/build/outputs/apks/debug/source-client-debug.apks"

fail() {
    printf 'Android deployment failed: %s\n' "$1" >&2
    exit 1
}

command -v python3 >/dev/null 2>&1 || fail "python3 is required to read $manifest_file."
command -v java >/dev/null 2>&1 || fail "Java 17 is required to build and package Source Client."
[ -x "$android_dir/gradlew" ] || fail "the Android Gradle wrapper is missing or not executable."

sha256_file() {
    if command -v shasum >/dev/null 2>&1; then
        shasum -a 256 "$1" | awk '{print $1}'
    elif command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | awk '{print $1}'
    else
        fail "shasum or sha256sum is required to verify deployment tools."
    fi
}

model_values=$(python3 - "$manifest_file" <<'PY'
import json
import re
import sys

path = sys.argv[1]
try:
    with open(path, encoding="utf-8") as source:
        client = json.load(source)["client"]
    sha256 = client["sha256"]
    parts = client["parts"]
    label = f'{client["family"]}-{client["parameters"]} {client["quantization"]}'
    if not isinstance(sha256, str) or not re.fullmatch(r"[0-9a-f]{64}", sha256):
        raise ValueError("client.sha256 must be a lowercase SHA-256 digest")
    if len(parts) != 3:
        raise ValueError("client.parts must contain the three Android asset-pack parts")
except (OSError, KeyError, TypeError, ValueError, json.JSONDecodeError) as error:
    print(f"Invalid Source AI model manifest {path}: {error}", file=sys.stderr)
    raise SystemExit(1)
print(sha256)
print(len(parts))
print(label)
PY
) || fail "could not read the Source Client model definition."
model_sha256=$(printf '%s\n' "$model_values" | sed -n '1p')
part_count=$(printf '%s\n' "$model_values" | sed -n '2p')
model_label=$(printf '%s\n' "$model_values" | sed -n '3p')

find_adb() {
    if [ -n "${ADB:-}" ]; then
        printf '%s\n' "$ADB"
        return
    fi
    if command -v adb >/dev/null 2>&1; then
        command -v adb
        return
    fi
    for sdk_root in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/Library/Android/sdk"; do
        if [ -n "$sdk_root" ] && [ -x "$sdk_root/platform-tools/adb" ]; then
            printf '%s\n' "$sdk_root/platform-tools/adb"
            return
        fi
    done
    return 1
}

adb=$(find_adb) || fail "adb was not found; install Android SDK Platform Tools or set ADB."
[ -x "$adb" ] || fail "ADB does not point to an executable: $adb"

device_list=$("$adb" devices -l) || fail "adb could not list devices."
if [ -n "${ANDROID_SERIAL:-}" ]; then
    serial=$ANDROID_SERIAL
    device_line=$(printf '%s\n' "$device_list" | awk -v serial="$serial" '$1 == serial { print; exit }')
    [ -n "$device_line" ] || fail "ANDROID_SERIAL=$serial is not connected."
    device_state=$(printf '%s\n' "$device_line" | awk '{print $2}')
    [ "$device_state" = "device" ] || fail "device $serial is $device_state; unlock it and authorize USB debugging."
else
    online_devices=$(printf '%s\n' "$device_list" | awk 'NR > 1 && $2 == "device" { print $1 }')
    device_count=$(printf '%s\n' "$online_devices" | awk 'NF { count++ } END { print count + 0 }')
    if [ "$device_count" -eq 0 ]; then
        printf '%s\n' "$device_list" >&2
        fail "no authorized Android device was found; connect one by USB, unlock it, and authorize USB debugging."
    fi
    if [ "$device_count" -ne 1 ]; then
        printf '%s\n' "$device_list" >&2
        fail "multiple Android devices are connected; set ANDROID_SERIAL to the USB device to deploy to."
    fi
    serial=$online_devices
    device_line=$(printf '%s\n' "$device_list" | awk -v serial="$serial" '$1 == serial { print; exit }')
fi

case "$device_line" in
    *" usb:"*) ;;
    *) fail "device $serial is not connected over USB." ;;
esac

device_model=$("$adb" -s "$serial" shell getprop ro.product.model | tr -d '\r') || fail "could not query device $serial."
device_api=$("$adb" -s "$serial" shell getprop ro.build.version.sdk | tr -d '\r') || fail "could not query the Android API level."
device_abis=$("$adb" -s "$serial" shell getprop ro.product.cpu.abilist | tr -d '\r') || fail "could not query device ABIs."
case "$device_api" in ''|*[!0-9]*) fail "device returned an invalid Android API level: $device_api" ;; esac
[ "$device_api" -ge 33 ] || fail "$device_model runs Android API $device_api; Source Client requires API 33 or newer."
case ",$device_abis," in *,arm64-v8a,*) ;; *) fail "$device_model does not support the required arm64-v8a ABI ($device_abis)." ;; esac

adb_device() {
    "$adb" -s "$serial" "$@"
}

model_is_current() {
    package_paths=$(adb_device shell pm path "$package_name" 2>/dev/null | tr -d '\r') || return 1
    [ -n "$package_paths" ] || return 1
    installed_stamp=$(adb_device shell run-as "$package_name" cat "$model_stamp" 2>/dev/null | tr -d '\r\n') || return 1
    [ "$installed_stamp" = "$model_sha256" ] || return 1
    pack_index=1
    while [ "$pack_index" -le "$part_count" ]; do
        printf '%s\n' "$package_paths" | grep "source_ai_model_$pack_index" >/dev/null || return 1
        pack_index=$((pack_index + 1))
    done
}

write_model_stamp() {
    adb_device shell run-as "$package_name" mkdir -p files >/dev/null || \
        fail "the app was installed, but its private files directory is unavailable."
    if ! printf '%s\n' "$model_sha256" | adb_device shell run-as "$package_name" tee "$model_stamp" >/dev/null; then
        fail "the app was installed, but its model identity could not be recorded."
    fi
}

printf 'Deploying Source Client to %s (%s, API %s).\n' "$device_model" "$serial" "$device_api"
printf 'Expected model: %s (%s).\n' "$model_label" "$model_sha256"

if model_is_current; then
    printf '%s\n' "The correct model and all three asset packs are already installed; the 3 GB model will not be transferred."
    printf '%s\n' "Building the debug Client APK."
    if ! (cd "$android_dir" && ./gradlew :app:assembleDebug); then
        fail "the Android debug build failed."
    fi
    [ -f "$debug_apk" ] || fail "Gradle succeeded but did not produce $debug_apk."
    printf '%s\n' "Updating the Client while retaining the installed model asset packs."
    if ! adb_device install-multiple -r -p "$package_name" "$debug_apk"; then
        fail "the base APK update failed; the installed app may have a newer version code or a different signing key."
    fi
    if ! model_is_current; then
        fail "the base APK was updated, but Android did not retain the model asset packs; rerun to reprovision them."
    fi
else
    printf '%s\n' "The model is missing or does not match the manifest; provisioning the complete install."
    "$repo_root/scripts/provision-client-model.sh"

    mkdir -p "$bundletool_dir"
    if [ ! -f "$bundletool_jar" ]; then
        command -v curl >/dev/null 2>&1 || fail "curl is required to download pinned bundletool $bundletool_version."
        printf 'Downloading pinned bundletool %s.\n' "$bundletool_version"
        if ! curl -fL "https://github.com/google/bundletool/releases/download/$bundletool_version/bundletool-all-$bundletool_version.jar" -o "$bundletool_download"; then
            fail "bundletool download failed."
        fi
        mv "$bundletool_download" "$bundletool_jar"
    fi
    actual_bundletool_sha256=$(sha256_file "$bundletool_jar")
    if [ "$actual_bundletool_sha256" != "$bundletool_sha256" ]; then
        fail "bundletool checksum mismatch; remove $bundletool_jar and rerun."
    fi

    printf '%s\n' "Building the debug Client bundle with its model asset packs."
    if ! (cd "$android_dir" && ./gradlew :app:bundleDebug); then
        fail "the Android debug bundle build failed."
    fi
    [ -f "$debug_bundle" ] || fail "Gradle succeeded but did not produce $debug_bundle."
    mkdir -p "$(dirname -- "$device_apks")"
    printf '%s\n' "Creating an APK set for the connected device."
    if ! java -jar "$bundletool_jar" build-apks \
        --bundle="$debug_bundle" \
        --output="$device_apks" \
        --adb="$adb" \
        --device-id="$serial" \
        --connected-device \
        --local-testing \
        --overwrite; then
        fail "bundletool could not create the device APK set."
    fi
    printf '%s\n' "Installing the Client and transferring the model asset packs (approximately 3 GB)."
    if ! java -jar "$bundletool_jar" install-apks \
        --apks="$device_apks" \
        --adb="$adb" \
        --device-id="$serial" \
        --timeout-millis=1800000; then
        fail "bundletool could not install the Client; check free device storage and signing-key compatibility."
    fi
    write_model_stamp
    model_is_current || fail "installation completed, but the expected model asset packs were not found on the device."
fi

printf 'Source Client deployment completed on %s; app data was preserved.\n' "$device_model"
