package com.abyxcz.viewpoint.llm

import androidx.test.platform.app.InstrumentationRegistry
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** The model read in place from the test APK's assets, as a shipped app reads its own. */
class BundledModelTest {

    private val testApk = InstrumentationRegistry.getInstrumentation().context
    private val config = LlmConfig(contextTokens = 256, threads = 2)
    private val greedy = Sampling(maxTokens = 24, temperature = 0f)

    @Test
    fun assetLoadsInPlaceAndMatchesAPathLoad() = runTest {
        val fromAsset =
            LlmSession.loadAsset(testApk, "stories260K.gguf", config).use {
                println("asset model memory-mapped: ${it.isMemoryMapped}")
                it.generate("Once upon a time", greedy).toList().joinToString("")
            }
        val fromFile =
            LlmSession.load(testModelPath(), config).use {
                it.generate("Once upon a time", greedy).toList().joinToString("")
            }
        assertEquals(fromFile, fromAsset)
    }

    @Test
    fun missingAssetExplainsTheStorageRule() {
        val e = assertFailsWith<LlmException> { LlmSession.loadAsset(testApk, "nope.gguf", config) }
        assertContains(e.message.orEmpty(), "noCompress")
    }
}
