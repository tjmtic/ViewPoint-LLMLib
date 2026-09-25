#include "lm_utf8.h"

/* Bytes in a sequence starting with lead byte c; 0 for a continuation or invalid byte. */
static int32_t sequence_length(unsigned char c) {
    if (c < 0x80) return 1;
    if ((c & 0xE0) == 0xC0) return 2;
    if ((c & 0xF0) == 0xE0) return 3;
    if ((c & 0xF8) == 0xF0) return 4;
    return 0;
}

int32_t lm_utf8_complete_len(const char* buf, int32_t len) {
    /* The last sequence starts within the final 4 bytes; find its lead byte. */
    for (int32_t back = 1; back <= 4 && back <= len; back++) {
        unsigned char c = (unsigned char)buf[len - back];
        if ((c & 0xC0) == 0x80) continue; /* continuation byte: keep looking */
        int32_t need = sequence_length(c);
        if (need == 0) return len;         /* invalid lead byte: malformed, emit as is */
        return back >= need ? len : len - back;
    }
    return len; /* only continuation bytes: malformed, emit as is */
}
