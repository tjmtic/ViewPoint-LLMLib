package com.abyxcz.viewpoint.llm

import android.content.Context
import java.io.FileNotFoundException

/**
 * Loads a model shipped inside the app as an asset — for a 656 MB model, an install-time Play
 * Asset Delivery pack (the base module is capped at 500 MB, a pack at 1.5 GB) — straight from
 * the APK, with no copy on disk. The asset must be stored uncompressed:
 * `android { androidResources { noCompress += "gguf" } }` in the module that holds it.
 *
 * The weights are memory-mapped when their data lands 32-byte aligned in the APK and copied
 * into memory otherwise; AGP does not control that alignment, so check [LlmSession.isMemoryMapped]
 * rather than assume it.
 */
fun LlmSession.Companion.loadAsset(
    context: Context,
    assetPath: String,
    config: LlmConfig = LlmConfig(),
): LlmSession {
    val afd =
        try {
            context.assets.openFd(assetPath)
        } catch (e: FileNotFoundException) {
            throw LlmException(
                "asset '$assetPath' is missing or compressed; models must be stored uncompressed " +
                    "(androidResources.noCompress += \"gguf\"): ${e.message}",
                e,
            )
        }
    return afd.use { fromNative(NativeLlm.loadFd(it.parcelFileDescriptor.fd, it.startOffset, config)) }
}
