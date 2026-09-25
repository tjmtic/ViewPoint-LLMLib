plugins {
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidAssetPack) apply false
    alias(libs.plugins.kotlinAndroid) apply false
    alias(libs.plugins.ktfmt) apply false
    alias(libs.plugins.detekt) apply false
}

// Fleet style standard: ktfmt (kotlinlang) owns layout, detekt at zero issues owns lint.
// See kmp-agentic-sdlc/.claude/docs/kotlin-style.md. No baseline: this repo started clean.
subprojects {
    apply(plugin = "com.ncorti.ktfmt.gradle")
    extensions.configure<com.ncorti.ktfmt.gradle.KtfmtExtension> { kotlinLangStyle() }
    // CBindingKMP's generated JNI bindings are added to androidMain; they are not ours to format.
    tasks.withType<com.ncorti.ktfmt.gradle.tasks.KtfmtBaseTask>().configureEach {
        exclude { it.file.invariantSeparatorsPath.contains("/build/") }
    }

    apply(plugin = "io.gitlab.arturbosch.detekt")
    extensions.configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
        source.setFrom(files("src")) // KMP: every source set, not src/main
        config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
        buildUponDefaultConfig = true
        parallel = true
        basePath = rootDir.absolutePath
    }
}
