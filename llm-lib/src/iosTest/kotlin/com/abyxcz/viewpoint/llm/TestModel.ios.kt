package com.abyxcz.viewpoint.llm

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv

// Set by the iosSimulatorArm64Test task (build.gradle.kts): the simulator reads host paths.
@OptIn(ExperimentalForeignApi::class)
actual fun testModelPath(): String =
    checkNotNull(getenv("LM_TEST_MODEL")?.toKString()) { "LM_TEST_MODEL is not set; run through Gradle" }
