package com.wedfaces.cameraoperator

import com.wedfaces.cameraoperator.model.CameraTransportType
import com.wedfaces.cameraoperator.model.TransferItem
import java.io.File

/**
 * Routes a queued camera original through the transport recorded when it was
 * selected. Keeping this independent of Android services makes accidental
 * USB-to-Wi-Fi cross-routing straightforward to test.
 */
internal suspend fun routeCameraOriginal(
    item: TransferItem,
    destination: File,
    onUsb: suspend (TransferItem, File, (Long, Long) -> Unit) -> Unit,
    onWifi: suspend (TransferItem, File, (Long, Long) -> Unit) -> Unit,
    onProgress: (Long, Long) -> Unit,
) {
    when (item.transportType) {
        CameraTransportType.USB -> onUsb(item, destination, onProgress)
        CameraTransportType.WIFI -> onWifi(item, destination, onProgress)
        CameraTransportType.NONE -> throw IllegalStateException(
            "This queue item has no camera transport.",
        )
    }
}
