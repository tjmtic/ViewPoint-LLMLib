import java.net.URI
import java.security.MessageDigest

plugins { alias(libs.plugins.androidAssetPack) }

// Install-time: the pack arrives with the app, as a split APK read through AssetManager.
// A single pack may be 1.5 GB (the base module only 500 MB), which is why a 656 MB model
// lives here and not in the app module.
assetPack {
    packName.set("modelpack")
    dynamicDelivery { deliveryType.set("install-time") }
}

// The sample ships stories15M (19 MB) in place of MiniCPM5-1B (656 MB): same file layout,
// same loading path, a size the emulator and CI can hold.
val fetchSampleModel by tasks.registering {
    val dest = layout.projectDirectory.file("src/main/assets/models/stories15M-q4_0.gguf").asFile
    val url = "https://huggingface.co/ggml-org/models/resolve/main/tinyllamas/stories15M-q4_0.gguf"
    val sha256 = "66967fbece6dbe97886593fdbb73589584927e29119ec31f08090732d1861739"
    outputs.file(dest)
    doLast {
        fun sha(f: File) =
            MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") {
                "%02x".format(it)
            }
        if (dest.exists() && sha(dest) == sha256) return@doLast
        dest.parentFile.mkdirs()
        URI(url).toURL().openStream().use { input -> dest.outputStream().use { input.copyTo(it) } }
        check(sha(dest) == sha256) { "sha256 mismatch for $url" }
    }
}

tasks.configureEach {
    if (name != "fetchSampleModel" && (name.contains("Asset") || name.contains("Pack"))) {
        dependsOn(fetchSampleModel)
    }
}
