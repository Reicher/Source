#!/usr/bin/env sh
set -eu

repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)

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

devices=$("$adb" devices | awk 'NR > 1 && $2 == "device" { print $1 }')
if [ -n "${ANDROID_SERIAL:-}" ]; then
    serial=$ANDROID_SERIAL
    if ! printf '%s\n' "$devices" | grep -Fxq -- "$serial"; then
        printf 'Android device %s is not connected and authorized.\n' "$serial" >&2
        exit 1
    fi
else
    device_count=$(printf '%s\n' "$devices" | awk 'NF { count++ } END { print count + 0 }')
    if [ "$device_count" -ne 1 ]; then
        printf '%s\n' 'Connect one authorized Android device or set ANDROID_SERIAL.' >&2
        exit 1
    fi
    serial=$devices
fi

printf '%s\n' 'Fetching the latest Self from origin/main.'
git -C "$repo_root" fetch origin main
revision=$(git -C "$repo_root" rev-parse FETCH_HEAD)
build_root=$(mktemp -d "${TMPDIR:-/tmp}/self-latest.XXXXXX")
trap 'rm -rf -- "$build_root"' EXIT
git -C "$repo_root" archive "$revision" self/android | tar -xf - -C "$build_root"

android_dir="$build_root/self/android"
(cd "$android_dir" && ./gradlew --no-daemon :app:assembleDebug)
apk="$android_dir/app/build/outputs/apk/debug/app-debug.apk"
if [ ! -f "$apk" ]; then
    printf '%s\n' 'Self debug APK was not produced.' >&2
    exit 1
fi

"$adb" -s "$serial" install -r "$apk"
"$adb" -s "$serial" shell am start -W -n com.source.self/.MainActivity
printf 'Installed Self from %s on %s.\n' "$revision" "$serial"
