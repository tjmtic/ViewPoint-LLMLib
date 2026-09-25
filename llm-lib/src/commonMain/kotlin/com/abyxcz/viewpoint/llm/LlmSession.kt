package com.abyxcz.viewpoint.llm

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/** How a model is loaded. */
data class LlmConfig(
    /** Context length in tokens: prompt plus generated text must fit. 0 = the model's own. */
    val contextTokens: Int = 2048,
    /** CPU threads for inference. */
    val threads: Int = 4,
    /** Layers offloaded to the GPU: 0 = CPU only, -1 = all. */
    val gpuLayers: Int = 0,
)

/** How one generation picks tokens. */
data class Sampling(
    /** Upper bound on generated tokens; the context size also bounds it. */
    val maxTokens: Int = 256,
    /** 0 = greedy (deterministic); higher = more varied. */
    val temperature: Float = 0.7f,
    /** Nucleus sampling cut-off; 1 disables it. */
    val topP: Float = 0.95f,
    /** Same seed + same prompt + same settings = same text. Negative = random. */
    val seed: Int = -1,
)

class LlmException(message: String) : RuntimeException(message)

/**
 * One loaded GGUF model and its context, backed by llama.cpp through the lm_shim C shim.
 *
 * Not thread-safe: run one generation at a time and collect it to completion or cancel it
 * before starting the next (starting a new one discards an unfinished one). The prompt is
 * passed as-is: format it for the model's chat template first.
 */
class LlmSession private constructor(private val native: NativeLlm) : AutoCloseable {

    private var closed = false

    /** Context length in tokens. */
    val contextTokens: Int
        get() = live().contextSize()

    /** Tokens [text] occupies, without BOS/EOS; chat-template special tokens count as one. */
    fun countTokens(text: String): Int = live().countTokens(text)

    /**
     * Generates a continuation of [prompt] as pieces of UTF-8 text. A piece never ends inside
     * a character. Each piece costs one model step, so the flow runs on [dispatcher].
     * Cancelling the collector stops generation after the current token.
     */
    fun generate(
        prompt: String,
        sampling: Sampling = Sampling(),
        dispatcher: CoroutineDispatcher = Dispatchers.Default,
    ): Flow<String> =
        flow {
                val llm = live()
                llm.prompt(prompt, sampling)
                while (true) {
                    val piece = llm.nextPiece()
                    if (piece.isEmpty()) break
                    emit(piece)
                }
                llm.error().takeIf { it.isNotEmpty() }?.let { throw LlmException(it) }
            }
            .flowOn(dispatcher)

    override fun close() {
        if (closed) return
        closed = true
        native.close()
    }

    private fun live(): NativeLlm {
        check(!closed) { "LlmSession is closed" }
        return native
    }

    companion object {
        /** Loads the model at [modelPath]. Blocking and slow for large models: call off the main thread. */
        fun load(modelPath: String, config: LlmConfig = LlmConfig()): LlmSession =
            LlmSession(NativeLlm.load(modelPath, config))
    }
}

/** Platform access to the shim: JNI on Android, cinterop on iOS. */
internal expect class NativeLlm {
    fun contextSize(): Int

    fun countTokens(text: String): Int

    /** Throws [LlmException] with the shim's reason when the prompt is refused. */
    fun prompt(text: String, sampling: Sampling)

    /** Next piece; empty at the end of the stream. */
    fun nextPiece(): String

    /** The shim's last error; empty after a normal end. */
    fun error(): String

    fun close()

    companion object {
        /** Throws [LlmException] with the shim's reason when loading fails. */
        fun load(path: String, config: LlmConfig): NativeLlm
    }
}
