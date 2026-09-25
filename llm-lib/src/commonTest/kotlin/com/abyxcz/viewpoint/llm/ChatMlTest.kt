package com.abyxcz.viewpoint.llm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The exact text MiniCPM5's own template renders for one turn (checked against llama-cli --jinja).
 */
class ChatMlTest {

    @Test
    fun miniCpm5WithSystemAndThinkingOff() {
        assertEquals(
            "<s><|im_start|>system\nUse only the facts.<|im_end|>\n" +
                "<|im_start|>user\nTell me about Vega.<|im_end|>\n" +
                "<|im_start|>assistant\n<think>\n\n</think>\n\n",
            ChatMl.MiniCpm5.prompt(user = "Tell me about Vega.", system = "Use only the facts."),
        )
    }

    @Test
    fun thinkingOnOpensAThinkBlockAndSystemIsOptional() {
        assertEquals(
            "<s><|im_start|>user\nWhy?<|im_end|>\n<|im_start|>assistant\n<think>\n",
            ChatMl.MiniCpm5.prompt(user = "Why?", thinking = true),
        )
    }

    @Test
    fun modelsWithoutTextBosTakeAnEmptyOne() {
        assertEquals(
            "<|im_start|>user\nhi<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n",
            ChatMl(bos = "").prompt("hi"),
        )
    }

    @Test
    fun templatesWithoutThinkingGetAPlainAssistantTurn() {
        assertEquals(
            "<|im_start|>user\nhi<|im_end|>\n<|im_start|>assistant\n",
            ChatMl(bos = "", thinkingSwitch = false).prompt("hi"),
        )
        assertFailsWith<IllegalArgumentException> {
            ChatMl(bos = "", thinkingSwitch = false).prompt("hi", thinking = true)
        }
    }
}
