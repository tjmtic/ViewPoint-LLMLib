plugins {
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.kotlinAndroid)
    `maven-publish`
}

group = "com.abyxcz.viewpoint.llm"

version = (project.findProperty("libVersion") as String?) ?: "0.1.0"

// Kept out of :llm-lib so apps not distributed through Google Play carry no Play dependency.
android {
    namespace = "com.abyxcz.viewpoint.llm.play"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig { minSdk = libs.versions.android.minSdk.get().toInt() }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    api(project(":llm-lib"))
    api(libs.play.asset.delivery)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
}
