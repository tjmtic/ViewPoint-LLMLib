package com.abyxcz.viewpoint.llm

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Where the platform put stories260K.gguf for this run. */
expect fun testModelPath(): String

/**
 * Real llama.cpp inference through the shim on each platform (iOS simulator, Android device or
 * emulator) against stories260K, a 1 MB model that answers in milliseconds.
 */
class LlmSessionTest {

    private lateinit var session: LlmSession

    @BeforeTest
    fun load() {
        session = LlmSession.load(testModelPath(), LlmConfig(contextTokens = 256, threads = 2))
    }

    @AfterTest
    fun close() {
        if (::session.isInitialized) session.close()
    }

    private val greedy = Sampling(maxTokens = 24, temperature = 0f)

    @Test
    fun missingModelFailsWithTheReason() {
        val e = assertFailsWith<LlmException> { LlmSession.load("/nonexistent/model.gguf") }
        assertContains(e.message.orEmpty(), "/nonexistent/model.gguf")
    }

    @Test
    fun reportsContextAndTokens() {
        assertEquals(256, session.contextTokens)
        assertEquals(0, session.countTokens(""))
        assertTrue(session.countTokens("Once upon a time") in 1..15)
    }

    @Test
    fun greedyGenerationIsDeterministicAndBounded() = runTest {
        val first = session.generate("Once upon a time", greedy).toList()
        val second = session.generate("Once upon a time", greedy).toList()
        assertTrue(first.isNotEmpty())
        assertTrue(first.size <= 24, "${first.size} pieces for maxTokens 24")
        assertEquals(first.joinToString(""), second.joinToString(""))
        println("greedy: ${first.joinToString("")}")
    }

    @Test
    fun seededSamplingIsReproducible() = runTest {
        val sampling = Sampling(maxTokens = 16, temperature = 0.8f, topP = 0.95f, seed = 42)
        val a = session.generate("The cat", sampling).toList().joinToString("")
        val b = session.generate("The cat", sampling).toList().joinToString("")
        assertEquals(a, b)
    }

    @Test
    fun cancellingMidStreamLeavesTheSessionUsable() = runTest {
        assertEquals(3, session.generate("Once upon a time", greedy).take(3).toList().size)
        val full = session.generate("Once upon a time", greedy).toList()
        assertTrue(full.size > 3)
    }

    @Test
    fun oversizedPromptIsRefusedWithTheReason() = runTest {
        val prompt = "once upon ".repeat(400)
        val e = assertFailsWith<LlmException> { session.generate(prompt, greedy).toList() }
        assertContains(e.message.orEmpty(), "context holds 256")
    }

    @Test
    fun closedSessionRefusesUse() {
        val s = LlmSession.load(testModelPath(), LlmConfig(contextTokens = 128, threads = 1))
        s.close()
        s.close()
        assertFailsWith<IllegalStateException> { s.countTokens("x") }
    }
}
