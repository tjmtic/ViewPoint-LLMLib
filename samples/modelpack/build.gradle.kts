import java.net.URI
import java.security.MessageDigest

plugins { alias(libs.plugins.androidAssetPack) }

// Fast-follow: Play downloads the pack right after the app installs and stores it unpacked in
// the app's internal storage, so the model is an ordinary file that loads memory-mapped.
// (An install-time pack stays inside a split APK, where the model's offset moves with every
// versionCode and cannot be mapped — see the README.) A pack may be 1.5 GB; the base module
// only 500 MB, which is why a 656 MB model lives here.
assetPack {
    packName.set("modelpack")
    dynamicDelivery { deliveryType.set("fast-follow") }
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
