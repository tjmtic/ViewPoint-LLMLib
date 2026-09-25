#ifndef LM_SHIM_H
#define LM_SHIM_H

/*
 * Thin C shim over llama.cpp for Kotlin Multiplatform, written in CBindingKMP's supported
 * C subset (docs/supported-c-subset.md): opaque handle, scalars, `const char*` inputs and
 * the caller-buffer out-string convention. llama.h itself cannot be bound directly (structs
 * by value, callbacks, arrays of tokens), so everything crosses through these functions.
 *
 * Out-string convention (every function with `char* out, int32_t cap`): writes UTF-8 into
 * out without a NUL, returns the bytes written, or -(capacity needed) WITHOUT consuming any
 * state when cap is too small — the generated Kotlin wrapper retries once with that size.
 *
 * Threading: one lm_ctx is used by one thread at a time. Different contexts are independent.
 */

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct lm_ctx lm_ctx;

/*
 * Loads a GGUF model and creates an inference context. Returns NULL on failure; call
 * lm_load_error on the same thread for the reason. The llama backend is initialised on the
 * first call. n_ctx: context length in tokens (0 = the model's). n_threads: CPU threads;
 * 0 = min(4, online CPUs), and never more than the CPU count (llama.cpp's threads spin, so
 * oversubscribing a core is ~1000x slower). On Android arm64 a CPU without the ARMv8.2
 * dot-product/fp16 instructions is refused here, with the reason in lm_load_error.
 * n_gpu_layers: layers offloaded to the GPU (-1 = all). 0 = CPU only: no GPU backend is
 * initialised at all (needed where there is no GPU queue, e.g. the iOS simulator).
 */
lm_ctx* lm_load(const char* model_path, int32_t n_ctx, int32_t n_threads, int32_t n_gpu_layers);

/* Why the last lm_load on this thread failed; 0 bytes if it did not. */
int32_t lm_load_error(char* out, int32_t cap);

/* Tokens in text, without BOS/EOS; special tokens such as <|im_start|> count as one. -1 on failure. */
int32_t lm_count_tokens(lm_ctx* ctx, const char* text);

/* Context length in tokens. */
int32_t lm_context_size(lm_ctx* ctx);

/*
 * Constrains the following generations to a GBNF grammar (llama.cpp's format, root rule
 * "root"), e.g. a JSON object whose fields can only take listed values: output then always
 * parses, and the model only chooses among what the grammar allows. "" removes it.
 * Returns 0, or -1 when the grammar does not parse (reason in lm_error; the previous
 * grammar, if any, stays).
 */
int32_t lm_set_grammar(lm_ctx* ctx, const char* gbnf);

/*
 * Starts a generation: forgets the previous one, evaluates the prompt (already formatted
 * for the model's chat template; special tokens are parsed), and sets up sampling.
 * temperature <= 0 samples greedily (deterministic). seed < 0 picks a random seed.
 * Returns 0, or a negative code with the reason in lm_error:
 *   -1 prompt does not tokenize, -2 prompt leaves no room in the context, -3 evaluation failed.
 */
int32_t lm_prompt(lm_ctx* ctx, const char* prompt, int32_t max_tokens, float temperature, float top_p, int32_t seed);

/*
 * The next piece of generated text. Returns the bytes written; 0 at the end of the stream
 * (end-of-generation token, max_tokens, a full context, or an error — lm_error is empty on a
 * normal end). A piece never ends inside a multi-byte UTF-8 character: a token that does is
 * held back and emitted with the next one. Too small a cap returns -(needed) and keeps the
 * piece for the retry.
 */
int32_t lm_next_token(lm_ctx* ctx, char* out, int32_t cap);

/* The last error on this context; 0 bytes if there is none. */
int32_t lm_error(lm_ctx* ctx, char* out, int32_t cap);

/* Frees the context and its model. NULL is ignored. */
void lm_free(lm_ctx* ctx);

#ifdef __cplusplus
}
#endif

#endif /* LM_SHIM_H */
