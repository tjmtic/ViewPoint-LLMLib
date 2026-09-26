package com.abyxcz.viewpoint.llm.play

import android.app.Activity
import android.content.Context
import com.abyxcz.viewpoint.llm.LlmConfig
import com.abyxcz.viewpoint.llm.LlmException
import com.abyxcz.viewpoint.llm.LlmSession
import com.google.android.play.core.assetpacks.AssetPackManager
import com.google.android.play.core.assetpacks.AssetPackManagerFactory
import com.google.android.play.core.assetpacks.AssetPackState
import com.google.android.play.core.assetpacks.AssetPackStateUpdateListener
import com.google.android.play.core.assetpacks.model.AssetPackStatus
import java.io.File
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first

/** Where a Play-delivered model is on its way to the device. */
sealed interface ModelDelivery {
    /** Queued or transferring; [totalBytes] is 0 until Play knows it. */
    data class Downloading(val bytes: Long, val totalBytes: Long) : ModelDelivery

    /**
     * Play holds large packs for Wi-Fi; the user can allow cellular via [ModelPack.askToContinue].
     */
    data object WaitingForWifi : ModelDelivery

    /** Play needs the user's go-ahead: call [ModelPack.askToContinue]. */
    data object NeedsConfirmation : ModelDelivery

    /** On the device, as an ordinary file: [path] loads memory-mapped. */
    data class Ready(val path: String) : ModelDelivery

    /** Delivery stopped; [errorCode] is an AssetPackErrorCode. */
    data class Failed(val errorCode: Int) : ModelDelivery
}

/**
 * A model shipped in a **fast-follow** Play Asset Delivery pack: Play downloads it right after the
 * app installs, as part of the install, and stores it unpacked in the app's internal storage — so
 * it is an ordinary file and loads memory-mapped, with no copy. It is usually there before the app
 * first opens, but not guaranteed to be: gate the model's features on [delivery] or [awaitPath],
 * never the rest of the app.
 *
 * [modelPath] is the model's path inside the pack's `src/main/assets`, e.g. `models/x.gguf`.
 */
class ModelPack(context: Context, val packName: String, val modelPath: String) {

    private val manager: AssetPackManager =
        AssetPackManagerFactory.getInstance(context.applicationContext)

    /** The model's file path when the pack is on the device now; null while it is not. */
    fun localPath(): String? =
        manager
            .getPackLocation(packName)
            ?.assetsPath()
            ?.let { File(it, modelPath) }
            ?.takeIf { it.isFile }
            ?.path

    /**
     * Delivery states until the model is on the device, ending with [ModelDelivery.Ready] or
     * [ModelDelivery.Failed]. Asks Play to fetch the pack (a fast-follow pack is normally already
     * downloading; asking makes it a priority).
     */
    fun delivery(): Flow<ModelDelivery> = callbackFlow {
        val ready = localPath()
        if (ready != null) {
            trySend(ModelDelivery.Ready(ready))
            close()
        } else {
            fun emit(state: AssetPackState) {
                val update =
                    toDelivery(
                        state.status(),
                        state.bytesDownloaded(),
                        state.totalBytesToDownload(),
                        state.errorCode(),
                        ::localPath,
                    )
                trySend(update)
                if (update is ModelDelivery.Ready || update is ModelDelivery.Failed) close()
            }
            val listener = AssetPackStateUpdateListener { state ->
                if (state.name() == packName) emit(state)
            }
            manager.registerListener(listener)
            manager
                .fetch(listOf(packName))
                .addOnSuccessListener { states -> states.packStates()[packName]?.let(::emit) }
                .addOnFailureListener {
                    close(LlmException("Play could not fetch pack '$packName'", it))
                }
            awaitClose { manager.unregisterListener(listener) }
            return@callbackFlow
        }
        awaitClose {}
    }

    /** Suspends until the model is on the device and returns its path; throws if delivery fails. */
    suspend fun awaitPath(): String =
        when (
            val last = delivery().first { it is ModelDelivery.Ready || it is ModelDelivery.Failed }
        ) {
            is ModelDelivery.Ready -> last.path
            is ModelDelivery.Failed ->
                throw LlmException("pack '$packName' failed to deliver (error ${last.errorCode})")
            else -> error("unreachable")
        }

    /**
     * Shows Play's dialog for [ModelDelivery.NeedsConfirmation] / [ModelDelivery.WaitingForWifi].
     */
    fun askToContinue(activity: Activity) {
        manager.showConfirmationDialog(activity)
    }

    /** Waits for the model, then loads it memory-mapped. */
    suspend fun load(config: LlmConfig = LlmConfig()): LlmSession =
        LlmSession.load(awaitPath(), config)
}

/** Maps a pack status (AssetPackStatus) to a delivery state; pure, for tests. */
internal fun toDelivery(
    status: Int,
    bytes: Long,
    total: Long,
    errorCode: Int,
    localPath: () -> String?,
): ModelDelivery =
    when (status) {
        AssetPackStatus.COMPLETED ->
            localPath()?.let { ModelDelivery.Ready(it) } ?: ModelDelivery.Failed(errorCode)
        AssetPackStatus.WAITING_FOR_WIFI -> ModelDelivery.WaitingForWifi
        AssetPackStatus.REQUIRES_USER_CONFIRMATION -> ModelDelivery.NeedsConfirmation
        AssetPackStatus.FAILED,
        AssetPackStatus.CANCELED -> ModelDelivery.Failed(errorCode)
        else ->
            ModelDelivery.Downloading(
                bytes,
                total,
            ) // UNKNOWN, NOT_INSTALLED, PENDING, DOWNLOADING, TRANSFERRING
    }
