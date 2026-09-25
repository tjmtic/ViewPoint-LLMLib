/* Host test for the UTF-8 splitter: every sequence length, every cut point, malformed input. */
#include <stdio.h>
#include <string.h>

#include "lm_utf8.h"

static int failures = 0;

static void expect(const char* label, const char* buf, int32_t len, int32_t want) {
    int32_t got = lm_utf8_complete_len(buf, len);
    if (got != want) {
        fprintf(stderr, "FAIL %s: len %d -> %d, want %d\n", label, len, got, want);
        failures++;
    }
}

int main(void) {
    expect("empty", "", 0, 0);
    expect("ascii", "abc", 3, 3);

    /* "aé🌍東": a(1) é(2) 🌍(4) 東(3) = 10 bytes; cut after every byte. */
    const char* s = "a\xC3\xA9\xF0\x9F\x8C\x8D\xE6\x9D\xB1";
    const int32_t boundaries[] = {0, 1, 3, 7, 10}; /* complete prefixes */
    for (int32_t len = 0; len <= 10; len++) {
        int32_t want = 0;
        for (int i = 0; i < 5; i++) if (boundaries[i] <= len) want = boundaries[i];
        char label[32];
        snprintf(label, sizeof label, "mixed cut %d", len);
        expect(label, s, len, want);
    }

    expect("lone continuation", "\x9F", 1, 1);
    expect("five continuations", "a\x80\x80\x80\x80\x80", 6, 6);
    expect("invalid lead F8", "a\xF8", 2, 2);
    expect("overlong run after complete 2-byte", "\xC3\xA9\x80", 3, 3);

    if (failures == 0) printf("lm_utf8_test: all passed\n");
    return failures == 0 ? 0 : 1;
}
