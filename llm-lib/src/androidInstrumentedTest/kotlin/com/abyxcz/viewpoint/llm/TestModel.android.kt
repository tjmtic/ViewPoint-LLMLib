package com.abyxcz.viewpoint.llm

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

// The model is an asset of the test APK (build.gradle.kts); llama.cpp needs a file path.
actual fun testModelPath(): String {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val file = File(instrumentation.targetContext.cacheDir, "stories260K.gguf")
    if (!file.exists()) {
        instrumentation.context.assets.open("stories260K.gguf").use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
    }
    return file.absolutePath
}
