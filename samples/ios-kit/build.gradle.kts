plugins { alias(libs.plugins.kotlinMultiplatform) }

// The Kotlin side of samples/ios: a static framework the Swift app links. llama.framework
// (dynamic) is linked and embedded by the Xcode project itself.
kotlin {
    listOf(iosArm64(), iosSimulatorArm64()).forEach {
        it.binaries.framework {
            baseName = "LlmSampleKit"
            isStatic = true
        }
    }
    sourceSets {
        iosMain.dependencies {
            implementation(project(":llm-lib"))
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}
