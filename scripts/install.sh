#!/usr/bin/env bash
# Install the debug APK on the connected phone (needs adb on the host: sudo dnf install android-tools).
set -euo pipefail
cd "$(dirname "$0")/.."
adb install -r app/build/outputs/apk/debug/app-debug.apk
