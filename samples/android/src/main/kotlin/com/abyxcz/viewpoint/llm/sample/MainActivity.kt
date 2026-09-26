package com.abyxcz.viewpoint.llm.sample

import android.app.Activity
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.widget.TextView
import com.abyxcz.viewpoint.llm.LlmConfig
import com.abyxcz.viewpoint.llm.Sampling
import com.abyxcz.viewpoint.llm.play.ModelPack
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

/**
 * Waits for the model Play delivers in the fast-follow pack, loads it memory-mapped, generates a
 * few tokens and reports how it went: on screen, and in logcat (tag LlmSample) for scripted runs.
 */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val view = TextView(this).apply { text = "loading" }
        setContentView(view)
        Thread {
            val report = runCatching { run() }.getOrElse { "error=${it.message}" }
            Log.i(TAG, report)
            runOnUiThread { view.text = report }
        }
            .start()
    }

    private fun run(): String {
        val pack = ModelPack(this, PACK, MODEL)
        val start = SystemClock.elapsedRealtime()
        val path = runBlocking { pack.awaitPath() }
        val delivered = SystemClock.elapsedRealtime()
        return runBlocking { pack.load(LlmConfig(contextTokens = 512)) }
            .use { llm ->
                val loaded = SystemClock.elapsedRealtime()
                val text = runBlocking {
                    llm.generate(PROMPT, Sampling(maxTokens = 32, temperature = 0f))
                        .toList()
                        .joinToString("")
                }
                val done = SystemClock.elapsedRealtime()
                "mapped=${llm.isMemoryMapped} waitMs=${delivered - start} loadMs=${loaded - delivered} " +
                    "genMs=${done - loaded} path=$path text=${text.replace('\n', ' ')}"
            }
    }

    private companion object {
        const val TAG = "LlmSample"
        const val PACK = "modelpack"
        const val MODEL = "models/stories15M-q4_0.gguf"
        const val PROMPT = "Once upon a time"
    }
}
