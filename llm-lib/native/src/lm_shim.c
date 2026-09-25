#include "lm_shim.h"

#include <pthread.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "llama.h"
#include "lm_utf8.h"

#define LM_ERROR_CAP 512

struct lm_ctx {
    struct llama_model* model;
    struct llama_context* ctx;
    const struct llama_vocab* vocab;
    struct llama_sampler* sampler;

    int32_t remaining; /* tokens this generation may still produce */
    int done;          /* generation finished; lm_next_token returns 0 */

    char* pending;     /* complete UTF-8 ready to hand out */
    int32_t pending_len;
    int32_t pending_cap;
    char tail[4];      /* incomplete trailing UTF-8 sequence, carried into the next piece */
    int32_t tail_len;

    char error[LM_ERROR_CAP];
};

/* ---- errors -------------------------------------------------------------------------- */

static _Thread_local char g_load_error[LM_ERROR_CAP];

static void set_error(char* dst, const char* fmt, ...) {
    va_list args;
    va_start(args, fmt);
    vsnprintf(dst, LM_ERROR_CAP, fmt, args);
    va_end(args);
}

/* The out-string convention for a NUL-terminated message. */
static int32_t copy_out(const char* msg, char* out, int32_t cap) {
    int32_t len = (int32_t)strlen(msg);
    if (len > cap) return -len;
    memcpy(out, msg, (size_t)len);
    return len;
}

/* ---- backend ------------------------------------------------------------------------- */

static void quiet_log(enum ggml_log_level level, const char* text, void* user) {
    (void)user;
    if (level == GGML_LOG_LEVEL_ERROR) fputs(text, stderr);
}

static pthread_once_t g_backend_once = PTHREAD_ONCE_INIT;

static void backend_init(void) {
    llama_log_set(quiet_log, NULL);
    llama_backend_init();
}

/* ---- buffers ------------------------------------------------------------------------- */

static int ensure_pending(lm_ctx* c, int32_t needed) {
    if (needed <= c->pending_cap) return 1;
    int32_t cap = c->pending_cap ? c->pending_cap : 256;
    while (cap < needed) cap *= 2;
    char* grown = realloc(c->pending, (size_t)cap);
    if (!grown) return 0;
    c->pending = grown;
    c->pending_cap = cap;
    return 1;
}

/* ---- API ----------------------------------------------------------------------------- */

lm_ctx* lm_load(const char* model_path, int32_t n_ctx, int32_t n_threads, int32_t n_gpu_layers) {
    pthread_once(&g_backend_once, backend_init);
    g_load_error[0] = '\0';

    struct llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = n_gpu_layers;
    /* CPU only means no GPU device at all: with the default (all devices) llama.cpp still
     * initialises Metal for the context, which fails where no GPU queue is available (the
     * iOS simulator in a test process). An empty NULL-terminated list offloads nothing. */
    static ggml_backend_dev_t no_devices[] = {NULL};
    if (n_gpu_layers == 0) mp.devices = no_devices;
    struct llama_model* model = llama_model_load_from_file(model_path, mp);
    if (!model) {
        set_error(g_load_error, "could not load model '%s' (missing, unreadable or not GGUF)", model_path);
        return NULL;
    }

    struct llama_context_params cp = llama_context_default_params();
    cp.n_ctx = n_ctx > 0 ? (uint32_t)n_ctx : 0;
    cp.n_batch = cp.n_ctx > 0 && cp.n_ctx < 512 ? cp.n_ctx : 512;
    if (n_threads > 0) {
        cp.n_threads = n_threads;
        cp.n_threads_batch = n_threads;
    }
    struct llama_context* ctx = llama_init_from_model(model, cp);
    if (!ctx) {
        llama_model_free(model);
        set_error(g_load_error, "could not create a %d-token context for '%s' (out of memory?)", n_ctx, model_path);
        return NULL;
    }

    lm_ctx* c = calloc(1, sizeof(lm_ctx));
    if (!c) {
        llama_free(ctx);
        llama_model_free(model);
        set_error(g_load_error, "out of memory");
        return NULL;
    }
    c->model = model;
    c->ctx = ctx;
    c->vocab = llama_model_get_vocab(model);
    c->done = 1;
    return c;
}

int32_t lm_load_error(char* out, int32_t cap) {
    return copy_out(g_load_error, out, cap);
}

int32_t lm_count_tokens(lm_ctx* c, const char* text) {
    int32_t n = llama_tokenize(c->vocab, text, (int32_t)strlen(text), NULL, 0, false, true);
    if (n == INT32_MIN) return -1;
    return n < 0 ? -n : n;
}

int32_t lm_context_size(lm_ctx* c) {
    return (int32_t)llama_n_ctx(c->ctx);
}

static void reset_generation(lm_ctx* c) {
    c->pending_len = 0;
    c->tail_len = 0;
    c->remaining = 0;
    c->done = 1;
    c->error[0] = '\0';
    if (c->sampler) {
        llama_sampler_free(c->sampler);
        c->sampler = NULL;
    }
}

int32_t lm_prompt(lm_ctx* c, const char* prompt, int32_t max_tokens, float temperature, float top_p, int32_t seed) {
    reset_generation(c);
    llama_memory_clear(llama_get_memory(c->ctx), true);

    const int32_t text_len = (int32_t)strlen(prompt);
    int32_t n = -llama_tokenize(c->vocab, prompt, text_len, NULL, 0, true, true);
    if (n <= 0) {
        set_error(c->error, "prompt did not tokenize");
        return -1;
    }
    llama_token* tokens = malloc(sizeof(llama_token) * (size_t)n);
    if (!tokens || llama_tokenize(c->vocab, prompt, text_len, tokens, n, true, true) != n) {
        free(tokens);
        set_error(c->error, "prompt did not tokenize");
        return -1;
    }

    const int32_t n_ctx = (int32_t)llama_n_ctx(c->ctx);
    if (n >= n_ctx) {
        free(tokens);
        set_error(c->error, "prompt is %d tokens; the context holds %d", n, n_ctx);
        return -2;
    }

    const int32_t n_batch = (int32_t)llama_n_batch(c->ctx);
    for (int32_t i = 0; i < n; i += n_batch) {
        int32_t chunk = n - i < n_batch ? n - i : n_batch;
        if (llama_decode(c->ctx, llama_batch_get_one(tokens + i, chunk)) != 0) {
            free(tokens);
            set_error(c->error, "evaluating the prompt failed at token %d", i);
            return -3;
        }
    }
    free(tokens);

    struct llama_sampler* s = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (temperature <= 0.0f) {
        llama_sampler_chain_add(s, llama_sampler_init_greedy());
    } else {
        if (top_p > 0.0f && top_p < 1.0f) llama_sampler_chain_add(s, llama_sampler_init_top_p(top_p, 1));
        llama_sampler_chain_add(s, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(s, llama_sampler_init_dist(seed < 0 ? LLAMA_DEFAULT_SEED : (uint32_t)seed));
    }
    c->sampler = s;

    int32_t room = n_ctx - n;
    c->remaining = max_tokens > 0 && max_tokens < room ? max_tokens : room;
    c->done = 0;
    return 0;
}

/* Samples and evaluates one token, appending its text to pending/tail. 0 = stream ended. */
static int advance(lm_ctx* c) {
    if (c->remaining <= 0) return 0;
    llama_token tok = llama_sampler_sample(c->sampler, c->ctx, -1);
    if (llama_vocab_is_eog(c->vocab, tok)) return 0;

    char small[256];
    char* piece = small;
    int32_t n = llama_token_to_piece(c->vocab, tok, small, (int32_t)sizeof(small), 0, false);
    if (n < 0) {
        piece = malloc((size_t)-n);
        if (!piece) {
            set_error(c->error, "out of memory");
            return 0;
        }
        n = llama_token_to_piece(c->vocab, tok, piece, -n, 0, false);
    }

    /* tail + piece, split at the last complete UTF-8 character. */
    if (!ensure_pending(c, c->pending_len + c->tail_len + n)) {
        if (piece != small) free(piece);
        set_error(c->error, "out of memory");
        return 0;
    }
    char* joined = c->pending + c->pending_len;
    memcpy(joined, c->tail, (size_t)c->tail_len);
    memcpy(joined + c->tail_len, piece, (size_t)n);
    if (piece != small) free(piece);
    int32_t joined_len = c->tail_len + n;
    int32_t complete = lm_utf8_complete_len(joined, joined_len);
    c->tail_len = joined_len - complete;
    memcpy(c->tail, joined + complete, (size_t)c->tail_len);
    c->pending_len += complete;

    c->remaining--;
    if (llama_decode(c->ctx, llama_batch_get_one(&tok, 1)) != 0) {
        set_error(c->error, "evaluating a generated token failed");
        return 0;
    }
    return 1;
}

int32_t lm_next_token(lm_ctx* c, char* out, int32_t cap) {
    /* Keep going until there is something to hand out, or the stream ends. */
    while (c->pending_len == 0 && !c->done) {
        if (!advance(c)) {
            c->done = 1;
            /* Flush an incomplete trailing character rather than lose it. */
            if (c->tail_len > 0 && ensure_pending(c, c->pending_len + c->tail_len)) {
                memcpy(c->pending + c->pending_len, c->tail, (size_t)c->tail_len);
                c->pending_len += c->tail_len;
                c->tail_len = 0;
            }
        }
    }
    if (c->pending_len == 0) return 0;
    if (c->pending_len > cap) return -c->pending_len; /* nothing consumed: the retry gets it */
    int32_t n = c->pending_len;
    memcpy(out, c->pending, (size_t)n);
    c->pending_len = 0;
    return n;
}

int32_t lm_error(lm_ctx* c, char* out, int32_t cap) {
    return copy_out(c->error, out, cap);
}

void lm_free(lm_ctx* c) {
    if (!c) return;
    if (c->sampler) llama_sampler_free(c->sampler);
    llama_free(c->ctx);
    llama_model_free(c->model);
    free(c->pending);
    free(c);
}
