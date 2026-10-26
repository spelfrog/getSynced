#!/usr/bin/env bash
# Build the app inside a Podman container. Usage: scripts/build.sh [gradle tasks...]  (default: assembleDebug)
set -euo pipefail
cd "$(dirname "$0")/.."
IMAGE=getsynced-builder
if ! podman image exists "$IMAGE"; then
  podman build -t "$IMAGE" -f Containerfile .
fi
exec podman run --rm \
  -v "$PWD":/src:Z \
  -v getsynced-gradle:/root/.gradle \
  -v getsynced-android:/root/.android \
  -w /src \
  -e GRADLE_OPTS="-Dorg.gradle.daemon=false" \
  "$IMAGE" ./gradlew --console=plain "${@:-assembleDebug}"
