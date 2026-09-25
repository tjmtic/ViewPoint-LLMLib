# ViewPoint-LLMLib

On-device LLM inference for Kotlin Multiplatform (Android + iOS): llama.cpp behind a small C
shim, bound with [CBindingKMP](https://github.com/tjmtic/CBindingKMP). First consumer:
Starpoints' fact-sheet features (the app computes every fact; the model only narrates or
returns an intent). Default model: MiniCPM5-1B Q4_K_M (656 MB, Apache-2.0), downloaded by the
app on first use — never bundled.

```kotlin
LlmSession.load(modelPath, LlmConfig(contextTokens = 2048)).use { llm ->
    llm.generate(prompt, Sampling(maxTokens = 200, temperature = 0.7f))
        .collect { piece -> print(piece) }       // UTF-8 pieces, never split mid-character
}
```

- `countTokens(text)` for prompt budgeting; `contextTokens` for the limit.
- `temperature = 0` is greedy and deterministic; a fixed `seed` makes sampling reproducible.
- Cancelling the collector stops after the current token; the next `generate` starts clean.
- One generation at a time per session. The prompt is used as-is, so format it with
  `ChatMl.MiniCpm5.prompt(user, system, thinking = false)`. MiniCPM5 needs the literal `<s>`
  its template writes (its tokenizer adds no BOS); without it the model emits newlines or loops.

## Layout

| Path | What |
|---|---|
| `llm-lib/native/include/lm_shim.h` | The C API, in CBindingKMP's subset: opaque `lm_ctx`, `const char*` in, caller-buffer out, polling for streaming |
| `llm-lib/native/src/` | `lm_shim.c` over llama.h; `lm_utf8.c` holds back an incomplete trailing character |
| `llm-lib/native/CMakeLists.txt` | Android: llama.cpp from source, static, into `liblmshim.so`. Host: the C tests |
| `llm-lib/src/commonMain` | `LlmSession`, `LlmConfig`, `Sampling` |
| `llm-lib/src/androidMain`, `iosMain` | JNI (generated wrappers) / cinterop actuals |
| `llm-lib/src/commonTest` | Real inference against stories260K, run on the iOS simulator and on Android devices |

## llama.cpp

Pinned to tag `b11165` everywhere, via `cbinding.prebuilt("llama")` in `llm-lib/build.gradle.kts`:

- **Android**: the source tarball (sha256-pinned) compiled by CMake for `arm64-v8a` and
  `x86_64`, CPU only. arm64 targets `armv8.2-a+dotprod+fp16` (Cortex-A55/A75 and newer,
  ~2018+); `lm_load` refuses an older CPU with a reason instead of crashing on an illegal
  instruction. llama.cpp is compiled `-O2` even in debug builds; only the shim stays
  debuggable.
- **iOS**: `llama.xcframework` with `ios-arm64` and `ios-arm64_x86_64-simulator`, built from the
  same tag by `scripts/build-llama-xcframework.sh` — the release asset has no simulator slice.
  It is a **dynamic** framework (min iOS 16.4): an app must link and **embed** it in Xcode.
  Until it is hosted as a release asset the zip is read from `third_party/` (gitignored);
  `-Pllama.xcframework.url=…` overrides. The sha256 in the build file pins that exact zip.

`n_gpu_layers = 0` (the default) initialises no GPU backend at all; Metal cannot create a
command queue in a headless simulator test.

### Why those build settings (measured on the arm64 emulator, 1 core, stories15M Q4_0)

| Build | Generation |
|---|---|
| 4 threads on 1 core, any build | 0.5 tok/s — spinning workers fight over the core |
| Debug (`-O0`), 1 thread | 30 tok/s |
| Release, generic `armv8-a`, 1 thread | 615 tok/s |
| Release, `armv8.2-a+dotprod+fp16`, 1 thread | 1560 tok/s |
| This library's debug build, threads auto or 4 | 760–980 tok/s |

Hence `threads = 0` means min(4, online cores), explicit values are capped at the core count,
and the dot-product build is the default. i8mm (ARMv8.6, newer flagships) is not enabled:
the emulator lacks it, so its gain is unmeasured.

## Tests

```bash
./gradlew :llm-lib:iosSimulatorArm64Test
```

```bash
./gradlew :llm-lib:connectedDebugAndroidTest
```

```bash
scripts/host-test.sh
```

`build/host/cmake/lm_generate <model.gguf> <prompt-file> [max_tokens] [temperature]` (built by
the script) runs one prompt through the shim and prints load, prompt and generation speed —
MiniCPM5-1B Q4_K_M on an M-series CPU: ~400 tok/s prompt, ~100 tok/s generation.

The host script builds llama.cpp + the shim for macOS and runs the C tests (UTF-8 splitter;
the shim's retry, end-of-stream, oversized-prompt and determinism contract). Pass a GGUF path
to run them against another model.

## Build requirements

A sibling `../CBindingKMP` checkout (composite build), or a token with `read:packages` for the
published plugin (1.3.1+). CMake for the scripts; AGP brings its own for Android.
