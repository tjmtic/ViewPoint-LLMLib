package com.abyxcz.viewpoint.llm

import cnames.structs.lm_ctx
import com.abyxcz.viewpoint.llm.cinterop.lm_context_size
import com.abyxcz.viewpoint.llm.cinterop.lm_count_tokens
import com.abyxcz.viewpoint.llm.cinterop.lm_error
import com.abyxcz.viewpoint.llm.cinterop.lm_free
import com.abyxcz.viewpoint.llm.cinterop.lm_is_mapped
import com.abyxcz.viewpoint.llm.cinterop.lm_load
import com.abyxcz.viewpoint.llm.cinterop.lm_load_error
import com.abyxcz.viewpoint.llm.cinterop.lm_next_token
import com.abyxcz.viewpoint.llm.cinterop.lm_prompt
import com.abyxcz.viewpoint.llm.cinterop.lm_set_grammar
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.readBytes

// cinterop passes `const char*` as real UTF-8 itself; out-strings follow the shim's
// convention by hand: bytes written, or -(capacity needed) with nothing consumed.
@OptIn(ExperimentalForeignApi::class)
internal actual class NativeLlm private constructor(private var handle: CPointer<lm_ctx>?) {

    private fun ctx(): CPointer<lm_ctx> = checkNotNull(handle) { "closed" }

    actual fun contextSize(): Int = lm_context_size(ctx())

    actual fun mapped(): Boolean = lm_is_mapped(ctx()) != 0

    actual fun countTokens(text: String): Int = lm_count_tokens(ctx(), text)

    actual fun prompt(text: String, sampling: Sampling) {
        if (lm_set_grammar(ctx(), sampling.grammar.orEmpty()) != 0) throw LlmException(error())
        val rc =
            lm_prompt(
                ctx(),
                text,
                sampling.maxTokens,
                sampling.temperature,
                sampling.topP,
                sampling.seed,
            )
        if (rc != 0) throw LlmException(error())
    }

    actual fun nextPiece(): String = readOut { buf, cap -> lm_next_token(ctx(), buf, cap) }

    actual fun error(): String = readOut { buf, cap -> lm_error(ctx(), buf, cap) }

    actual fun close() {
        handle?.let { lm_free(it) }
        handle = null
    }

    actual companion object {
        actual fun load(path: String, config: LlmConfig): NativeLlm {
            val handle =
                lm_load(path, config.contextTokens, config.threads, config.gpuLayers)
                    ?: throw LlmException(readOut { buf, cap -> lm_load_error(buf, cap) })
            return NativeLlm(handle)
        }
    }
}

/** First out-buffer size; the shim reports the size it needs and the call is retried once. */
private const val FIRST_BUFFER_BYTES = 256

@OptIn(ExperimentalForeignApi::class)
private inline fun readOut(call: (CPointer<ByteVar>, Int) -> Int): String = memScoped {
    var cap = FIRST_BUFFER_BYTES
    var buf = allocArray<ByteVar>(cap)
    var n = call(buf, cap)
    if (n < 0) {
        cap = -n
        buf = allocArray(cap)
        n = call(buf, cap)
    }
    check(n in 0..cap) { "lm_shim returned $n for a $cap-byte buffer" }
    buf.readBytes(n).decodeToString()
}
