package com.abyxcz.viewpoint.llm

import com.abyxcz.viewpoint.llm.generated.lm_context_sizeJNI
import com.abyxcz.viewpoint.llm.generated.lm_count_tokensJNI
import com.abyxcz.viewpoint.llm.generated.lm_errorJNI
import com.abyxcz.viewpoint.llm.generated.lm_freeJNI
import com.abyxcz.viewpoint.llm.generated.lm_load_errorJNI
import com.abyxcz.viewpoint.llm.generated.lm_loadJNI
import com.abyxcz.viewpoint.llm.generated.lm_next_tokenJNI
import com.abyxcz.viewpoint.llm.generated.lm_promptJNI
import com.abyxcz.viewpoint.llm.generated.lm_set_grammarJNI

// Everything below goes through CBindingKMP's generated wrappers, which do the UTF-8
// encoding, the out-buffer sizing and the decoding.
internal actual class NativeLlm private constructor(private var handle: Long) {

    actual fun contextSize(): Int = lm_context_sizeJNI(handle)

    actual fun countTokens(text: String): Int = lm_count_tokensJNI(handle, text)

    actual fun prompt(text: String, sampling: Sampling) {
        if (lm_set_grammarJNI(handle, sampling.grammar.orEmpty()) != 0) {
            throw LlmException(lm_errorJNI(handle))
        }
        val rc =
            lm_promptJNI(handle, text, sampling.maxTokens, sampling.temperature, sampling.topP, sampling.seed)
        if (rc != 0) throw LlmException(lm_errorJNI(handle))
    }

    actual fun nextPiece(): String = lm_next_tokenJNI(handle)

    actual fun error(): String = lm_errorJNI(handle)

    actual fun close() {
        if (handle != 0L) {
            lm_freeJNI(handle)
            handle = 0L
        }
    }

    actual companion object {
        private val loaded by lazy { System.loadLibrary("lmshim") }

        actual fun load(path: String, config: LlmConfig): NativeLlm {
            loaded
            val handle = lm_loadJNI(path, config.contextTokens, config.threads, config.gpuLayers)
            if (handle == 0L) throw LlmException(lm_load_errorJNI())
            return NativeLlm(handle)
        }
    }
}
