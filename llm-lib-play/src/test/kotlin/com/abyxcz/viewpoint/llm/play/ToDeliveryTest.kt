package com.abyxcz.viewpoint.llm.play

import com.google.android.play.core.assetpacks.model.AssetPackStatus
import kotlin.test.assertEquals
import org.junit.Test

class ToDeliveryTest {

    private val here = { "/data/assetpacks/modelpack/1/assets/models/m.gguf" }
    private val nothing = { null }

    @Test
    fun completedWithTheFilePresentIsReady() {
        assertEquals(
            ModelDelivery.Ready(here()),
            toDelivery(AssetPackStatus.COMPLETED, 10, 10, 0, here),
        )
    }

    @Test
    fun completedWithoutTheFileIsAFailureNotAHang() {
        assertEquals(
            ModelDelivery.Failed(0),
            toDelivery(AssetPackStatus.COMPLETED, 10, 10, 0, nothing),
        )
    }

    @Test
    fun inFlightStatesReportProgress() {
        listOf(
                AssetPackStatus.PENDING,
                AssetPackStatus.DOWNLOADING,
                AssetPackStatus.TRANSFERRING,
                AssetPackStatus.NOT_INSTALLED,
            )
            .forEach {
                assertEquals(ModelDelivery.Downloading(5, 10), toDelivery(it, 5, 10, 0, nothing))
            }
    }

    @Test
    fun userGatedAndTerminalStates() {
        assertEquals(
            ModelDelivery.WaitingForWifi,
            toDelivery(AssetPackStatus.WAITING_FOR_WIFI, 0, 10, 0, nothing),
        )
        assertEquals(
            ModelDelivery.NeedsConfirmation,
            toDelivery(AssetPackStatus.REQUIRES_USER_CONFIRMATION, 0, 10, 0, nothing),
        )
        assertEquals(
            ModelDelivery.Failed(-6),
            toDelivery(AssetPackStatus.FAILED, 0, 10, -6, nothing),
        )
        assertEquals(
            ModelDelivery.Failed(0),
            toDelivery(AssetPackStatus.CANCELED, 0, 10, 0, nothing),
        )
    }
}
