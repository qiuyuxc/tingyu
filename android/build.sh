#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

SDK_DIR="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-${HOME}/android-sdk}}"
APK_OUT="${TINGYU_APK_OUT:-tingyu-open-source.apk}"
if [[ ! -f local.properties && -d "$SDK_DIR" ]]; then
  printf 'sdk.dir=%s\n' "$SDK_DIR" > local.properties
fi

GRADLE_ARGS=(--console=plain "--max-workers=${TINGYU_BUILD_WORKERS:-2}")
if [[ -n "${ANDROID_AAPT2_OVERRIDE:-}" ]]; then
  GRADLE_ARGS+=("-Pandroid.aapt2FromMavenOverride=$ANDROID_AAPT2_OVERRIDE")
elif [[ "$(uname -m)" == aarch64 ]] && command -v aapt2 >/dev/null; then
  GRADLE_ARGS+=("-Pandroid.aapt2FromMavenOverride=$(command -v aapt2)")
fi

if command -v gradle >/dev/null; then
  gradle assembleDebug "${GRADLE_ARGS[@]}" "$@"
else
  ./gradlew assembleDebug "${GRADLE_ARGS[@]}" "$@"
fi

APKS=(build/outputs/apk/debug/*.apk)
if [[ ${#APKS[@]} != 1 || ! -f "${APKS[0]}" ]]; then
  printf '无法确定调试 APK: build/outputs/apk/debug/\n' >&2
  exit 1
fi
cp "${APKS[0]}" "$APK_OUT"
ls -lh "$APK_OUT"
