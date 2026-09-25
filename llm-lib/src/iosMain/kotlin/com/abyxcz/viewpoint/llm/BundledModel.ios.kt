package com.abyxcz.viewpoint.llm

import platform.Foundation.NSBundle

/**
 * Loads a model shipped in the app bundle (add the .gguf to the app target's Copy Bundle
 * Resources). A bundle resource is an ordinary file, so the weights are memory-mapped.
 */
fun LlmSession.Companion.loadBundled(
    name: String,
    extension: String = "gguf",
    config: LlmConfig = LlmConfig(),
): LlmSession {
    val path =
        NSBundle.mainBundle.pathForResource(name, extension)
            ?: throw LlmException("no $name.$extension in the app bundle")
    return load(path, config)
}
