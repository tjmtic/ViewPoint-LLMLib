#ifndef LM_UTF8_H
#define LM_UTF8_H

#include <stdint.h>

/*
 * Length of the longest prefix of buf[0, len) that does not end inside a multi-byte UTF-8
 * sequence. Bytes after it are an incomplete character to carry into the next piece.
 * Malformed input (stray continuation bytes, invalid lead bytes) is never held back.
 */
int32_t lm_utf8_complete_len(const char* buf, int32_t len);

#endif /* LM_UTF8_H */
