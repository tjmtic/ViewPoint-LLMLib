/*
 * Host tool: runs one prompt through the shim and reports speed. For trying models and
 * prompt packs on the Mac with the exact code path the apps use.
 * usage: lm_generate <model.gguf> <prompt-file> [max_tokens] [temperature] [threads]
 */
#include <stdio.h>
#include <stdlib.h>
#include <time.h>

#include "lm_shim.h"

static double now(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec + ts.tv_nsec / 1e9;
}

int main(int argc, char** argv) {
    if (argc < 3) {
        fprintf(stderr, "usage: %s <model.gguf> <prompt-file> [max_tokens] [temperature] [threads]\n", argv[0]);
        return 2;
    }
    FILE* f = fopen(argv[2], "rb");
    if (!f) { perror(argv[2]); return 2; }
    fseek(f, 0, SEEK_END);
    long size = ftell(f);
    rewind(f);
    char* prompt = malloc((size_t)size + 1);
    fread(prompt, 1, (size_t)size, f);
    prompt[size] = '\0';
    fclose(f);

    int32_t max_tokens = argc > 3 ? atoi(argv[3]) : 200;
    float temperature = argc > 4 ? (float)atof(argv[4]) : 0.0f;
    int32_t threads = argc > 5 ? atoi(argv[5]) : 0; /* 0 = the shim's default */

    double t0 = now();
    lm_ctx* c = lm_load(argv[1], 4096, threads, 0);
    if (!c) {
        char msg[512];
        int32_t n = lm_load_error(msg, sizeof msg - 1);
        msg[n > 0 ? n : 0] = '\0';
        fprintf(stderr, "load failed: %s\n", msg);
        return 1;
    }
    double t1 = now();
    int32_t prompt_tokens = lm_count_tokens(c, prompt);
    if (lm_prompt(c, prompt, max_tokens, temperature, 0.95f, 42) != 0) {
        char msg[512];
        int32_t n = lm_error(c, msg, sizeof msg - 1);
        msg[n > 0 ? n : 0] = '\0';
        fprintf(stderr, "prompt failed: %s\n", msg);
        return 1;
    }
    double t2 = now();
    int pieces = 0;
    char piece[512];
    for (int32_t n; (n = lm_next_token(c, piece, sizeof piece)) > 0; pieces++) fwrite(piece, 1, (size_t)n, stdout);
    double t3 = now();
    fprintf(stderr, "\n---\nload %.2fs | prompt %d tokens in %.2fs (%.0f tok/s) | %d pieces in %.2fs (%.1f tok/s)\n",
            t1 - t0, prompt_tokens, t2 - t1, prompt_tokens / (t2 - t1), pieces, t3 - t2, pieces / (t3 - t2));
    lm_free(c);
    free(prompt);
    return 0;
}
