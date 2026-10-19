package com.wedfaces.cameraoperator

import com.wedfaces.cameraoperator.data.TransferQueueRepository
import com.wedfaces.cameraoperator.model.CameraTransportType
import com.wedfaces.cameraoperator.model.PhotoSource
import com.wedfaces.cameraoperator.model.QueueState
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TransferDownloadRouterTest {
    @Test
    fun queuedUsbOriginalNeverUsesWifiAdapter() = runBlocking {
        var routedTo = ""
        val item = cameraItem(CameraTransportType.USB)

        routeCameraOriginal(
            item = item,
            destination = File("usb.jpg"),
            onUsb = { queued, file, _ ->
                routedTo = "USB:${queued.sourceObjectId}:${file.name}"
            },
            onWifi = { _, _, _ -> routedTo = "Wi-Fi" },
            onProgress = { _, _ -> },
        )

        assertEquals("USB:7:31:usb.jpg", routedTo)
    }

    @Test
    fun queuedWifiOriginalNeverUsesUsbAdapter() = runBlocking {
        var routedTo = ""
        val item = cameraItem(CameraTransportType.WIFI)

        routeCameraOriginal(
            item = item,
            destination = File("wifi.jpg"),
            onUsb = { _, _, _ -> routedTo = "USB" },
            onWifi = { queued, file, _ ->
                routedTo = "Wi-Fi:${queued.sourceObjectId}:${file.name}"
            },
            onProgress = { _, _ -> },
        )

        assertEquals("Wi-Fi:7:31:wifi.jpg", routedTo)
    }

    @Test
    fun nonCameraQueueItemCannotBeRoutedToACamera() {
        val error = assertThrows(IllegalStateException::class.java) {
            runBlocking {
                routeCameraOriginal(
                    item = cameraItem(CameraTransportType.NONE),
                    destination = File("none.jpg"),
                    onUsb = { _, _, _ -> error("Unexpected USB call") },
                    onWifi = { _, _, _ -> error("Unexpected Wi-Fi call") },
                    onProgress = { _, _ -> },
                )
            }
        }
        assertEquals("This queue item has no camera transport.", error.message)
    }

    private fun cameraItem(transport: CameraTransportType) =
        TransferQueueRepository.newTransfer(
            batchId = "batch",
            albumId = "album",
            albumName = "Wedding",
            source = PhotoSource.CAMERA,
            sourceObjectId = "7:31",
            fileName = "camera.jpg",
            mimeType = "image/jpeg",
            sizeBytes = 100,
            initialState = QueueState.DOWNLOADING,
            transportType = transport,
        )
}
