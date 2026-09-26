#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
android_root="$repo_root/android/Nagram"

for name in ANDROID_HOME JAVA_HOME TGWEAR_KEYSTORE_PATH TGWEAR_KEYSTORE_PASS TGWEAR_KEY_ALIAS TGWEAR_KEY_PASS; do
  if [[ -z "${!name:-}" ]]; then
    echo "Missing required environment variable: $name" >&2
    exit 2
  fi
done

if [[ ! -x "$android_root/gradlew" ]]; then
  echo "Gradle wrapper missing: $android_root/gradlew" >&2
  exit 2
fi
if [[ ! -f "$TGWEAR_KEYSTORE_PATH" ]]; then
  echo "TG Wear keystore not found at TGWEAR_KEYSTORE_PATH" >&2
  exit 2
fi
shopt -s nullglob
wear_sdk=("$android_root"/TMessagesProj/libs/*.aar "$android_root"/TMessagesProj/libs/*.jar)
if (( ${#wear_sdk[@]} == 0 )); then
  echo "Xiaomi Wearable SDK AAR/JAR is missing from TMessagesProj/libs; obtain it from an authorized source." >&2
  exit 2
fi
if [[ ! -s "$android_root/TMessagesProj/google-services.json" ]]; then
  echo "TMessagesProj/google-services.json is missing. Supply the correct local config or use the documented debug-only alternative." >&2
  exit 2
fi

export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_HOME}"
export NATIVE_TARGET="arm64-v8a"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"

cd "$android_root"
./gradlew --version
./gradlew --no-daemon :TMessagesProj:assembleDebug

printf '\nBuilt APK candidates:\n'
find TMessagesProj/build/outputs/apk -type f -name '*.apk' -print
