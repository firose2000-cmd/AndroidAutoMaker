#!/usr/bin/env bash
set -euo pipefail
project_root=$(cd "$(dirname "$0")/.." && pwd)
sdk_root=${ANDROID_HOME:-${ANDROID_SDK_ROOT:?Set ANDROID_HOME or ANDROID_SDK_ROOT}}
fixture_root="$project_root/android/app/src/androidTest/fixtures"
fixture_work=$(mktemp -d)
trap 'rm -rf "$fixture_work"' EXIT
"$sdk_root/build-tools/35.0.0/aapt2" compile --dir "$fixture_root/res" -o "$fixture_work/resources.zip"
mkdir -p "$project_root/android/app/src/androidTest/assets"
"$sdk_root/build-tools/35.0.0/aapt2" link -I "$sdk_root/platforms/android-36/android.jar" \
    --manifest "$fixture_root/AndroidManifest.xml" \
    -o "$project_root/android/app/src/androidTest/assets/sample.apk" "$fixture_work/resources.zip"
