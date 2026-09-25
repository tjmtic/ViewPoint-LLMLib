#!/bin/bash
# Builds llama.cpp (pinned tag) + the shim for this Mac and runs the C tests:
#   lm_utf8_test                 the UTF-8 splitter, every cut point
#   lm_shim_test <model.gguf>    the real shim against a model (default: stories260K)
# usage: scripts/host-test.sh [model.gguf]
# Needs CMake on PATH (the Android SDK's works: PATH=~/Library/Android/sdk/cmake/<v>/bin:$PATH).
set -euo pipefail

TAG="b11165"
SRC_SHA256="01d0270e83f3f3d8460a879172d31347b4bd91a4530aecb83fa09b73d5c02c27"
MODEL_SHA256="270cba1bd5109f42d03350f60406024560464db173c0e387d91f0426d3bd256d"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$ROOT/build/host"
mkdir -p "$WORK"

command -v cmake >/dev/null || { echo "cmake not on PATH" >&2; exit 1; }

if [ ! -d "$WORK/llama.cpp-$TAG" ]; then
    curl -sL --retry 5 -o "$WORK/src.tar.gz" "https://github.com/ggml-org/llama.cpp/archive/refs/tags/$TAG.tar.gz"
    echo "$SRC_SHA256  $WORK/src.tar.gz" | shasum -a 256 -c -
    tar xzf "$WORK/src.tar.gz" -C "$WORK" && rm "$WORK/src.tar.gz"
fi

MODEL="${1:-$WORK/stories260K.gguf}"
if [ -z "${1:-}" ] && [ ! -f "$MODEL" ]; then
    curl -sL --retry 5 -o "$MODEL" https://huggingface.co/ggml-org/models/resolve/main/tinyllamas/stories260K.gguf
    echo "$MODEL_SHA256  $MODEL" | shasum -a 256 -c -
fi

cmake -S "$ROOT/llm-lib/native" -B "$WORK/cmake" -DLM_HOST_TESTS=ON \
    -DCBINDING_LLAMA_DIR="$WORK/llama.cpp-$TAG" -DCMAKE_BUILD_TYPE=Release >/dev/null
cmake --build "$WORK/cmake" -j 8 --target lm_utf8_test lm_shim_test lm_generate >/dev/null
"$WORK/cmake/lm_utf8_test"
"$WORK/cmake/lm_shim_test" "$MODEL"
