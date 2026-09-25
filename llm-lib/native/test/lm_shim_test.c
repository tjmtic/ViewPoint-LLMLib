/*
 * Host integration test: drives the real shim + llama.cpp against a GGUF model.
 * usage: lm_shim_test <model.gguf>
 */
#include <stdio.h>
#include <stdlib.h>
#include <fcntl.h>
#include <string.h>
#include <unistd.h>

#include "lm_shim.h"

static int failures = 0;
#define CHECK(cond, ...) do { if (!(cond)) { fprintf(stderr, "FAIL %s:%d: ", __FILE__, __LINE__); fprintf(stderr, __VA_ARGS__); fputc('\n', stderr); failures++; } } while (0)

/* Greedy generation into buf; returns the byte count and the number of pieces. */
static int32_t generate(lm_ctx* c, const char* prompt, int32_t max_tokens, char* buf, int32_t cap, int* pieces) {
    int32_t total = 0;
    *pieces = 0;
    CHECK(lm_prompt(c, prompt, max_tokens, 0.0f, 1.0f, 0) == 0, "lm_prompt failed");
    for (;;) {
        char piece[256];
        int32_t n = lm_next_token(c, piece, sizeof piece);
        CHECK(n >= 0, "256 bytes should always fit a piece, got %d", n);
        if (n <= 0) break;
        CHECK(total + n < cap, "output overflow");
        memcpy(buf + total, piece, (size_t)n);
        total += n;
        (*pieces)++;
    }
    buf[total] = '\0';
    return total;
}

int main(int argc, char** argv) {
    if (argc < 2) {
        fprintf(stderr, "usage: %s <model.gguf>\n", argv[0]);
        return 2;
    }

    /* Load failure is reported, not crashed on. */
    CHECK(lm_load("/nonexistent/model.gguf", 256, 2, 0) == NULL, "loading a missing file should fail");
    char msg[512];
    int32_t mlen = lm_load_error(msg, sizeof msg);
    CHECK(mlen > 0, "load error should be set");
    msg[mlen > 0 ? mlen : 0] = '\0';
    CHECK(strstr(msg, "/nonexistent/model.gguf") != NULL, "load error names the path: %s", msg);
    CHECK(lm_load_error(msg, 3) < -3, "too-small buffer reports the size needed");

    lm_ctx* c = lm_load(argv[1], 256, 2, 0);
    CHECK(c != NULL, "load %s", argv[1]);
    if (!c) return 1;
    CHECK(lm_load_error(msg, sizeof msg) == 0, "no load error after success");
    CHECK(lm_context_size(c) == 256, "context size %d", lm_context_size(c));
    CHECK(lm_count_tokens(c, "") == 0, "empty text has no tokens");
    int32_t nt = lm_count_tokens(c, "Once upon a time");
    CHECK(nt > 0 && nt < 16, "token count %d", nt);

    /* Greedy is deterministic, and max_tokens bounds the stream. */
    char a[4096], b[4096];
    int pa, pb;
    int32_t la = generate(c, "Once upon a time", 24, a, sizeof a, &pa);
    int32_t lb = generate(c, "Once upon a time", 24, b, sizeof b, &pb);
    CHECK(la > 0, "generated nothing");
    CHECK(la == lb && memcmp(a, b, (size_t)la) == 0, "greedy runs differ:\n[%s]\n[%s]", a, b);
    CHECK(pa > 0 && pa <= 24, "pieces %d not within max_tokens", pa);
    printf("greedy (%d pieces): %s\n", pa, a);

    /* A too-small buffer consumes nothing: the retry returns exactly the same piece. */
    CHECK(lm_prompt(c, "Once upon a time", 24, 0.0f, 1.0f, 0) == 0, "lm_prompt");
    char tiny[1], piece[256];
    int32_t need = lm_next_token(c, tiny, 0);
    CHECK(need < 0, "cap 0 must report the size, got %d", need);
    int32_t again = lm_next_token(c, tiny, 0);
    CHECK(again == need, "asking twice changes nothing (%d vs %d)", again, need);
    int32_t got = lm_next_token(c, piece, -need);
    CHECK(got == -need, "retry with the reported size gets the piece (%d vs %d)", got, -need);
    CHECK(got > 0 && memcmp(piece, a, (size_t)got) == 0, "retried piece is the first piece of the greedy run");

    /* Ending: after 0 the stream stays ended and the error is empty. */
    while (lm_next_token(c, piece, sizeof piece) > 0) {}
    CHECK(lm_next_token(c, piece, sizeof piece) == 0, "stream stays ended");
    CHECK(lm_error(c, msg, sizeof msg) == 0, "normal end leaves no error");

    /* A prompt that overflows the context is refused with a reason. */
    char* big = malloc(8192);
    big[0] = '\0';
    for (int i = 0; i < 400; i++) strcat(big, "once upon ");
    CHECK(lm_prompt(c, big, 8, 0.0f, 1.0f, 0) == -2, "oversized prompt is refused");
    mlen = lm_error(c, msg, sizeof msg);
    msg[mlen > 0 ? mlen : 0] = '\0';
    CHECK(strstr(msg, "context holds 256") != NULL, "error explains: %s", msg);
    CHECK(lm_next_token(c, piece, sizeof piece) == 0, "refused prompt generates nothing");
    free(big);

    /* Sampling with a seed is reproducible. */
    char s1[4096], s2[4096];
    int32_t l1 = 0, l2 = 0;
    CHECK(lm_prompt(c, "The cat", 16, 0.8f, 0.95f, 42) == 0, "lm_prompt sampled");
    for (int32_t n; (n = lm_next_token(c, s1 + l1, 256)) > 0;) l1 += n;
    CHECK(lm_prompt(c, "The cat", 16, 0.8f, 0.95f, 42) == 0, "lm_prompt sampled again");
    for (int32_t n; (n = lm_next_token(c, s2 + l2, 256)) > 0;) l2 += n;
    CHECK(l1 == l2 && memcmp(s1, s2, (size_t)l1) == 0, "same seed, same text");

    /* A grammar restricts output to what it allows; a bad one is refused and changes nothing. */
    CHECK(lm_set_grammar(c, "root ::= (") == -1, "unparseable grammar is refused");
    mlen = lm_error(c, msg, sizeof msg);
    CHECK(mlen > 0, "grammar error explains");
    CHECK(lm_set_grammar(c, "root ::= \"yes\" | \"no\"") == 0, "yes/no grammar");
    for (int seed = 1; seed <= 5; seed++) {
        char g[64];
        int32_t gl = 0;
        CHECK(lm_prompt(c, "Once upon a time", 16, 0.9f, 1.0f, seed) == 0, "grammar prompt");
        for (int32_t n; (n = lm_next_token(c, g + gl, 32)) > 0;) gl += n;
        g[gl] = '\0';
        CHECK(strcmp(g, "yes") == 0 || strcmp(g, "no") == 0, "grammar output '%s' is yes or no", g);
    }
    CHECK(lm_set_grammar(c, "") == 0, "grammar removed");
    char free_text[4096];
    int pf;
    int32_t lf = generate(c, "Once upon a time", 24, free_text, sizeof free_text, &pf);
    CHECK(lf == la && memcmp(free_text, a, (size_t)la) == 0, "without the grammar, greedy output is back to normal");

    lm_free(c);
    lm_free(NULL);

    /* Loading a model embedded in a larger file (an APK asset): mapped when aligned, copied
     * when not, same output either way. */
    FILE* src = fopen(argv[1], "rb");
    fseek(src, 0, SEEK_END);
    long model_size = ftell(src);
    rewind(src);
    char* model_bytes = malloc((size_t)model_size);
    fread(model_bytes, 1, (size_t)model_size, src);
    fclose(src);
    const long offsets[] = {64, 48}; /* 48: the data section lands 16 bytes off alignment */
    for (int k = 0; k < 2; k++) {
        char container[] = "/tmp/lm_shim_test_container_XXXXXX";
        int wfd = mkstemp(container);
        char junk[64];
        memset(junk, 0x5A, sizeof junk);
        write(wfd, junk, (size_t)offsets[k]);
        write(wfd, model_bytes, (size_t)model_size);
        write(wfd, junk, sizeof junk); /* bytes after it, as in a zip */
        close(wfd);
        int rfd = open(container, O_RDONLY);
        lm_ctx* e = lm_load_fd(rfd, offsets[k], 256, 2, 0);
        close(rfd); /* the shim dup'ed it */
        unlink(container);
        CHECK(e != NULL, "load at offset %ld", offsets[k]);
        if (!e) continue;
        CHECK(lm_is_mapped(e) == (k == 0), "offset %ld: mapped %d", offsets[k], lm_is_mapped(e));
        char out[4096];
        int pieces;
        int32_t len = generate(e, "Once upon a time", 24, out, sizeof out, &pieces);
        CHECK(len == la && memcmp(out, a, (size_t)la) == 0, "offset %ld: same text as the path load", offsets[k]);
        printf("embedded at offset %ld: %s, same output\n", offsets[k], lm_is_mapped(e) ? "mapped" : "copied");
        lm_free(e);
    }
    free(model_bytes);
    CHECK(lm_load_fd(-1, 0, 256, 1, 0) == NULL, "bad descriptor is refused");
    mlen = lm_load_error(msg, sizeof msg);
    CHECK(mlen > 0, "bad descriptor explains");

    if (failures == 0) printf("lm_shim_test: all passed\n");
    return failures == 0 ? 0 : 1;
}
