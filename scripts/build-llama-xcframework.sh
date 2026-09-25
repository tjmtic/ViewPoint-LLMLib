#!/bin/bash
# Builds llama.xcframework (ios-arm64 + ios-arm64_x86_64-simulator) from the pinned llama.cpp
# source and zips it to third_party/, printing the sha256 to pin in llm-lib/build.gradle.kts.
#
# Why not the release asset: ggml-org's llama-<tag>-xcframework.zip ships ios-arm64 and
# macOS only — no simulator slice, so nothing could run on the iOS simulator. This uses
# llama.cpp's own build-xcframework.sh on the same tag the Android build compiles.
#
# Needs Xcode and CMake (the Android SDK's works: PATH=~/Library/Android/sdk/cmake/<v>/bin:$PATH).
set -euo pipefail

TAG="b11165"
SRC_SHA256="01d0270e83f3f3d8460a879172d31347b4bd91a4530aecb83fa09b73d5c02c27"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/third_party/llama-$TAG-xcframework-ios.zip"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

command -v cmake >/dev/null || { echo "cmake not on PATH" >&2; exit 1; }

curl -sL --retry 5 -o "$WORK/src.tar.gz" "https://github.com/ggml-org/llama.cpp/archive/refs/tags/$TAG.tar.gz"
echo "$SRC_SHA256  $WORK/src.tar.gz" | shasum -a 256 -c -
tar xzf "$WORK/src.tar.gz" -C "$WORK"

(cd "$WORK/llama.cpp-$TAG" && ./build-xcframework.sh ios-sim ios-device)

mkdir -p "$ROOT/third_party"
rm -f "$OUT"
(cd "$WORK/llama.cpp-$TAG/build-apple" && ditto -c -k --keepParent llama.xcframework "$OUT")
echo "wrote $OUT"
shasum -a 256 "$OUT"
