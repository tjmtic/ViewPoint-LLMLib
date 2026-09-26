pluginManagement {
    // CBindingKMP generates the JNI glue and fetches llama.cpp. A sibling checkout wins
    // (composite build); without one, or with -PuseComposite=false, the published plugin is
    // resolved from GitHub Packages (needs a token with read:packages: GPR_USER/GPR_KEY or
    // gpr.user/gpr.key in ~/.gradle/gradle.properties).
    val useComposite = providers.gradleProperty("useComposite").map { it.toBoolean() }.getOrElse(true)
    val cbindingCheckout = settingsDir.resolve("../CBindingKMP/plugin")
    if (useComposite && cbindingCheckout.isDirectory) {
        includeBuild(cbindingCheckout)
    } else {
        plugins { id("com.abyxcz.cbinding") version "1.3.1" }
    }
    repositories {
        maven {
            name = "GitHubPackages-CBinding"
            url = uri("https://maven.pkg.github.com/tjmtic/CBindingKMP")
            credentials {
                username = System.getenv("GPR_USER") ?: providers.gradleProperty("gpr.user").orNull ?: ""
                password = System.getenv("GPR_KEY") ?: providers.gradleProperty("gpr.key").orNull ?: ""
            }
            content { includeGroup("com.abyxcz.cbinding") }
        }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "ViewPoint-LLMLib"
include(":llm-lib")
// Optional: models delivered by Google Play asset packs (fast-follow). Android only.
include(":llm-lib-play")
// Reference app: ships a model in an install-time Play Asset Delivery pack and loads it in place.
include(":samples:android", ":samples:modelpack")
