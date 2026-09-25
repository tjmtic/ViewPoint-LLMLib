package com.abyxcz.viewpoint.llm.sample

import android.app.Activity
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.widget.TextView
import com.abyxcz.viewpoint.llm.LlmConfig
import com.abyxcz.viewpoint.llm.LlmSession
import com.abyxcz.viewpoint.llm.Sampling
import com.abyxcz.viewpoint.llm.loadAsset
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

/**
 * Loads the model shipped in the install-time asset pack, in place, generates a few tokens and
 * reports how it went: on screen, and in logcat (tag LlmSample) for scripted runs.
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
        val start = SystemClock.elapsedRealtime()
        return LlmSession.loadAsset(this, MODEL, LlmConfig(contextTokens = 512)).use { llm ->
            val loaded = SystemClock.elapsedRealtime()
            val text = runBlocking {
                llm.generate(PROMPT, Sampling(maxTokens = 32, temperature = 0f))
                    .toList()
                    .joinToString("")
            }
            val done = SystemClock.elapsedRealtime()
            "mapped=${llm.isMemoryMapped} loadMs=${loaded - start} genMs=${done - loaded} " +
                "text=${text.replace('\n', ' ')}"
        }
    }

    private companion object {
        const val TAG = "LlmSample"
        const val MODEL = "models/stories15M-q4_0.gguf"
        const val PROMPT = "Once upon a time"
    }
}
