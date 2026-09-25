package com.abyxcz.viewpoint.llm

/**
 * One-turn prompts in the ChatML family (MiniCPM5, Qwen, …), rendered the way the model's own
 * Jinja chat template renders them. llama.cpp's C API cannot run those templates and the shim
 * passes prompts through untouched, so the format lives here.
 *
 * [bos] matters: MiniCPM5's tokenizer does not add a beginning-of-sequence token itself
 * (`add_bos = false`) — its template writes `<s>` as text. Without it the model degenerates
 * into newlines or loops. Models whose tokenizer adds BOS, or that use none, take `""`.
 */
class ChatMl(val bos: String) {

    /**
     * [thinking] = false renders the empty `<think></think>` block that switches reasoning off
     * (fast, direct answers); true opens a `<think>` block for the model to reason in first.
     */
    fun prompt(user: String, system: String? = null, thinking: Boolean = false): String = buildString {
        append(bos)
        if (system != null) append("<|im_start|>system\n").append(system).append("<|im_end|>\n")
        append("<|im_start|>user\n").append(user).append("<|im_end|>\n")
        append("<|im_start|>assistant\n")
        append(if (thinking) "<think>\n" else "<think>\n\n</think>\n\n")
    }

    companion object {
        /** openbmb MiniCPM5 (1B, 2B). */
        val MiniCpm5 = ChatMl(bos = "<s>")
    }
}
