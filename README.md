# ViewPoint-LLMLib

[![CI](https://github.com/tjmtic/ViewPoint-LLMLib/actions/workflows/ci.yml/badge.svg)](https://github.com/tjmtic/ViewPoint-LLMLib/actions/workflows/ci.yml)

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
- `Sampling(grammar = gbnf)` constrains output to a GBNF grammar: for intents, a JSON object
  whose fields can only take listed values always parses, and the model only chooses. Costs
  speed (the grammar is checked against the whole vocabulary), so use it for short outputs.
- `temperature = 0` is greedy and deterministic; a fixed `seed` makes sampling reproducible.
- Cancelling the collector stops after the current token; the next `generate` starts clean.
- One generation at a time per session. The prompt is used as-is, so format it with
  `ChatMl.MiniCpm5.prompt(user, system, thinking = false)`. MiniCPM5 needs the literal `<s>`
  its template writes (its tokenizer adds no BOS); without it the model emits newlines or loops.

## Shipping the model in the app

The model ships inside the app, not downloaded after install. MiniCPM5-1B Q4_K_M is 656 MB.

**Android.** Google Play caps the base module at 500 MB and a single asset pack at 1.5 GB, so
put the `.gguf` in an **install-time asset pack** (Play Asset Delivery). It arrives with the
install, it is read through `AssetManager`, and install-time packs plus modules may total 4 GB.
Store it uncompressed, and load it in place — no copy on disk:

```kotlin
// the module holding the asset
android { androidResources { noCompress += "gguf" } }

val llm = LlmSession.loadAsset(context, "models/MiniCPM5-1B-Q4_K_M.gguf", LlmConfig(contextTokens = 2048))
```

The weights are memory-mapped when their data lands 32-byte aligned inside the APK and are
read into memory otherwise. **For an install-time pack, plan on the copy.** Measured with
`samples/` (bundletool 1.18.3, the tool Play builds install APKs with): the pack APK holds the
compressed pack manifest and then the model, so the model's offset moves with the manifest's
compressed size — which changes with every versionCode — and it landed 16 bytes off. Output is
identical; the copy costs load time and model-size of private memory (native heap grew by the
model's 19 MB in the sample; 656 MB for MiniCPM5-1B). `llm.isMemoryMapped` says which.

`samples/android` + `samples/modelpack` are the reference setup: an install-time pack holding
the model, `noCompress += "gguf"`, and `loadAsset`. To run it as Play would install it:

```bash
./gradlew :samples:android:bundleRelease
```

```bash
java -jar bundletool-all.jar build-apks --bundle=samples/android/build/outputs/bundle/release/android-release.aab --output=sample.apks --local-testing --connected-device --ks=$HOME/.android/debug.keystore --ks-key-alias=androiddebugkey --ks-pass=pass:android && java -jar bundletool-all.jar install-apks --apks=sample.apks
```

The app logs `mapped=… loadMs=… genMs=… text=…` under the `LlmSample` tag.

**iOS.** Add the `.gguf` to the app target's Copy Bundle Resources; a bundle resource is an
ordinary file, so it is always memory-mapped. The App Store allows 4 GB; above 200 MB users on
cellular are asked before downloading.

```kotlin
val llm = LlmSession.loadBundled("MiniCPM5-1B-Q4_K_M", config = LlmConfig(contextTokens = 2048))
```

Only one tier ships this way: bundling the 2B too would add 1.5 GB to every install.

## Layout

| Path | What |
|---|---|
| `llm-lib/native/include/lm_shim.h` | The C API, in CBindingKMP's subset: opaque `lm_ctx`, `const char*` in, caller-buffer out, polling for streaming |
| `llm-lib/native/src/` | `lm_shim.c` over llama.h; `lm_utf8.c` holds back an incomplete trailing character |
| `llm-lib/native/CMakeLists.txt` | Android: llama.cpp from source, static, into `liblmshim.so`. Host: the C tests |
| `llm-lib/src/commonMain` | `LlmSession`, `LlmConfig`, `Sampling` |
| `llm-lib/src/androidMain`, `iosMain` | JNI (generated wrappers) / cinterop actuals |
| `llm-lib/src/commonTest` | Real inference against stories260K, run on the iOS simulator and on Android devices |
| `spike/` | Starpoints prompt pack + runner scoring model tiers on fact fidelity and intents (see `spike/README.md`) |

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
  Hosted as the [`llama-b11165-ios`](https://github.com/tjmtic/ViewPoint-LLMLib/releases/tag/llama-b11165-ios)
  release asset; the sha256 in the build file pins that exact zip. Rebuilding produces a
  different hash, so a rebuilt zip is a new release plus a new pin (`-Pllama.xcframework.url=…`
  overrides for local experiments).

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
