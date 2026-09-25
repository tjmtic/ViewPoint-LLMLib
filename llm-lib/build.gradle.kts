import com.abyxcz.buildlogic.PrebuiltArchives
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSetTree
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    id("com.abyxcz.cbinding")
    `maven-publish`
}

group = "com.abyxcz.viewpoint.llm"

// Tag-driven: CI passes -PlibVersion from the vX.Y.Z tag; default is the current release.
version = (project.findProperty("libVersion") as String?) ?: "0.1.0"

// ---- llama.cpp, pinned to one tag on every platform -------------------------------------

val llamaTag = "b11165"

val llama =
    cbinding.prebuilt("llama") {
        ios {
            // Built from the same tag by scripts/build-llama-xcframework.sh — ggml-org's release
            // asset has no simulator slice — and hosted as this repo's release asset.
            // Dynamic framework. -Pllama.xcframework.url overrides (e.g. a fresh local build:
            // file:///…/third_party/llama-b11165-xcframework-ios.zip, with its own sha256).
            xcframework(
                url =
                    providers
                        .gradleProperty("llama.xcframework.url")
                        .getOrElse(
                            "https://github.com/tjmtic/ViewPoint-LLMLib/releases/download/" +
                                "llama-$llamaTag-ios/llama-$llamaTag-xcframework-ios.zip"
                        ),
                sha256 = "1bdb727edea331a818ad67a0a0893aed55891dd545418a6db0e6fb4363b91504",
                name = "llama",
            )
        }
        android {
            // Source, compiled statically into liblmshim.so by native/CMakeLists.txt
            // (arrives there as CBINDING_LLAMA_DIR).
            archive(
                url = "https://github.com/ggml-org/llama.cpp/archive/refs/tags/$llamaTag.tar.gz",
                sha256 = "01d0270e83f3f3d8460a879172d31347b4bd91a4530aecb83fa09b73d5c02c27",
            )
        }
    }

cbinding {
    headersDir.set(file("native/include"))
    includeHeaders.set(listOf("lm_shim.h"))
    jniPackage.set("com.abyxcz.viewpoint.llm.generated")
    kotlinFileName.set("LmNative")
}

// ---- Test model: stories260K (1.1 MB, MIT, tinyllamas) — real inference in seconds ------

abstract class FetchTestModel : DefaultTask() {
    @get:Input abstract val url: Property<String>

    @get:Input abstract val sha256: Property<String>

    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun fetch() {
        val dest = outputDir.file("stories260K.gguf").get().asFile
        if (dest.exists() && PrebuiltArchives.sha256(dest) == sha256.get()) return
        PrebuiltArchives.download(url.get(), dest)
        PrebuiltArchives.verify(dest, sha256.get(), url.get())
    }
}

val fetchTestModel by
    tasks.registering(FetchTestModel::class) {
        url.set("https://huggingface.co/ggml-org/models/resolve/main/tinyllamas/stories260K.gguf")
        sha256.set("270cba1bd5109f42d03350f60406024560464db173c0e387d91f0426d3bd256d")
        outputDir.set(layout.buildDirectory.dir("test-model"))
    }

// ---- Kotlin ------------------------------------------------------------------------------

kotlin {
    // expect/actual classes (NativeLlm) are Beta; opt in explicitly so builds stay warning-free.
    compilerOptions { freeCompilerArgs.add("-Xexpect-actual-classes") }

    androidTarget {
        publishLibraryVariants("release")
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
        // commonTest needs the native library, so it runs as instrumented tests (device or
        // emulator), not as JVM unit tests.
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        instrumentedTestVariant.sourceSetTree.set(KotlinSourceSetTree.test)
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        unitTestVariant.sourceSetTree.set(KotlinSourceSetTree.unitTest)
    }

    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        val device = target.name == "iosArm64"
        val sdk = if (device) "iphoneos" else "iphonesimulator"
        // llama.xcframework's minimum iOS version.
        val triple = if (device) "arm64-apple-ios16.4" else "arm64-apple-ios16.4-simulator"
        val libDir = layout.buildDirectory.dir("native/${target.name}")
        val llamaHeaders = llama.ios.headersDir(device = device).get().asFile

        // The shim as a per-target static archive (CBindingKMP pattern 1).
        val compileShim =
            tasks.register<Exec>(
                "compileLmShim${target.name.replaceFirstChar { it.uppercase() }}"
            ) {
                dependsOn(llama.iosFetchTaskName)
                val src = file("native/src")
                val include = file("native/include")
                inputs.dir(src)
                inputs.dir(include)
                outputs.dir(libDir)
                val out = libDir.get().asFile
                val cc =
                    "xcrun --sdk $sdk clang -target $triple -O2 -std=c11 -I\"$include\" -I\"$src\" -I\"$llamaHeaders\""
                commandLine(
                    "bash",
                    "-c",
                    "mkdir -p \"$out\" && " +
                        "$cc -c \"$src/lm_shim.c\" -o \"$out/lm_shim.o\" && " +
                        "$cc -c \"$src/lm_utf8.c\" -o \"$out/lm_utf8.o\" && " +
                        "ar rcs \"$out/liblmshim.a\" \"$out/lm_shim.o\" \"$out/lm_utf8.o\"",
                )
            }

        target.compilations.getByName("main") {
            cinterops.create("lmshim") {
                defFile(project.file("src/nativeInterop/cinterop/lmshim.def"))
                packageName("com.abyxcz.viewpoint.llm.cinterop")
                includeDirs(project.file("native/include"))
                extraOpts("-libraryPath", libDir.get().asFile.absolutePath)
            }
        }
        tasks.named("cinteropLmshim${target.name.replaceFirstChar { it.uppercase() }}") {
            dependsOn(compileShim)
        }
    }

    sourceSets {
        commonMain.dependencies { implementation(libs.kotlinx.coroutines.core) }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        androidInstrumentedTest.dependencies {
            implementation(libs.junit)
            implementation(libs.androidx.test.runner)
            implementation(libs.androidx.test.ext.junit)
        }
    }
}

// The simulator sees the host file system; the model path reaches the test through the
// environment (simctl forwards only SIMCTL_CHILD_-prefixed variables).
tasks.withType<KotlinNativeSimulatorTest>().configureEach {
    dependsOn(fetchTestModel)
    val model =
        fetchTestModel.flatMap { it.outputDir.file("stories260K.gguf") }.get().asFile.absolutePath
    environment("SIMCTL_CHILD_LM_TEST_MODEL", model)
    environment("LM_TEST_MODEL", model)
}

android {
    namespace = "com.abyxcz.viewpoint.llm"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }
    externalNativeBuild { cmake { path = file("native/CMakeLists.txt") } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// The test model ships inside the instrumented-test APK as an asset.
androidComponents {
    onVariants { variant ->
        variant.androidTest
            ?.sources
            ?.assets
            ?.addGeneratedSourceDirectory(fetchTestModel, FetchTestModel::outputDir)
    }
}

publishing {
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/tjmtic/ViewPoint-LLMLib")
            credentials {
                username =
                    System.getenv("GITHUB_ACTOR")
                        ?: System.getenv("GPR_USER")
                        ?: findProperty("gpr.user") as String?
                        ?: ""
                password =
                    System.getenv("GITHUB_TOKEN")
                        ?: System.getenv("GPR_KEY")
                        ?: findProperty("gpr.key") as String?
                        ?: ""
            }
        }
    }
}
