package com.abyxcz.viewpoint.llm.sample

import com.abyxcz.viewpoint.llm.ChatMl
import com.abyxcz.viewpoint.llm.LlmConfig
import com.abyxcz.viewpoint.llm.LlmSession
import com.abyxcz.viewpoint.llm.Sampling
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.TimeSource
import kotlinx.coroutines.runBlocking

/**
 * On-device measurements for the Swift app: load (CPU or Metal), a fact-sheet narration and a
 * grammar-constrained intent — Starpoints' two real uses. Blocking; call off the main thread.
 */
class Bench {
    private var llm: LlmSession? = null

    @Throws(Throwable::class)
    fun load(path: String, gpuLayers: Int): String {
        close()
        val start = TimeSource.Monotonic.markNow()
        val session =
            LlmSession.load(path, LlmConfig(contextTokens = CONTEXT, gpuLayers = gpuLayers))
        llm = session
        return "load gpuLayers=$gpuLayers mapped=${session.isMemoryMapped} ms=${start.elapsedNow().inWholeMilliseconds}"
    }

    @Throws(Throwable::class)
    fun narrate(): String =
        measure(
            "narrate",
            ChatMl.MiniCpm5.prompt(user = FACTS, system = NARRATE),
            Sampling(maxTokens = 128, temperature = 0f),
        )

    @Throws(Throwable::class)
    fun intent(): String =
        measure(
            "intent",
            ChatMl.MiniCpm5.prompt(user = "show me Vega", system = INTENT),
            Sampling(maxTokens = 40, temperature = 0f, grammar = GRAMMAR),
        )

    fun close() {
        llm?.close()
        llm = null
    }

    private fun measure(label: String, prompt: String, sampling: Sampling): String = runBlocking {
        val session = checkNotNull(llm) { "load first" }
        val promptTokens = session.countTokens(prompt)
        val start = TimeSource.Monotonic.markNow()
        var first: Duration? = null
        val pieces = mutableListOf<String>()
        session.generate(prompt, sampling).collect {
            if (first == null) first = start.elapsedNow()
            pieces += it
        }
        val total = start.elapsedNow()
        val ttft = first ?: total
        val genSeconds = (total - ttft).toDouble(DurationUnit.SECONDS)
        val genTps = if (pieces.size > 1 && genSeconds > 0) (pieces.size - 1) / genSeconds else 0.0
        val promptTps = promptTokens / ttft.toDouble(DurationUnit.SECONDS)
        "$label promptTokens=$promptTokens ttftMs=${ttft.inWholeMilliseconds} promptTps=${promptTps.toInt()} " +
            "pieces=${pieces.size} genTps=${genTps.toInt()} text=${pieces.joinToString("").replace("\n", " ")}"
    }

    private companion object {
        const val CONTEXT = 2048
        const val NARRATE =
            "You are the voice of a stargazing app. Use ONLY the facts given. Do not add any number, " +
                "date, distance, size or comparison that is not in the facts. Answer in at most three short sentences."
        const val FACTS =
            "FACTS\nname: Vega\nconstellation: Lyra\napparent magnitude: 0.03\ndistance: 25 light-years\n" +
                "light left the star: 25 years ago\naltitude now: 62 degrees, high in the east\n\n" +
                "Tell me about the star I am pointing at."
        const val INTENT =
            "You label stargazing requests for an app. You never perform the request and never explain. " +
                "Reply with exactly one JSON object: {\"action\": \"find\" | \"advance_time\" | \"toggle\", " +
                "\"target\": one of Vega, Andromeda Galaxy, Orion, Polaris, Pleiades, constellation_lines, labels, " +
                "night_mode, or null, \"minutes_from_now\": integer}."
        const val GRAMMAR =
            "root ::= \"{\\\"action\\\": \" action \", \\\"target\\\": \" target \", " +
                "\\\"minutes_from_now\\\": \" int \"}\"\n" +
                "action ::= \"\\\"find\\\"\" | \"\\\"advance_time\\\"\" | \"\\\"toggle\\\"\"\n" +
                "target ::= \"null\" | \"\\\"Vega\\\"\" | \"\\\"Andromeda Galaxy\\\"\" | \"\\\"Orion\\\"\" | " +
                "\"\\\"Polaris\\\"\" | \"\\\"Pleiades\\\"\" | \"\\\"constellation_lines\\\"\" | " +
                "\"\\\"labels\\\"\" | \"\\\"night_mode\\\"\"\n" +
                "int ::= \"0\" | [1-9] [0-9]? [0-9]? [0-9]?\n"
    }
}
