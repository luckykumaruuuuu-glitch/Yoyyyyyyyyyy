package com.wedfaces.cameraoperator.camera

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.content.pm.PackageManager
import io.github.gallo.sonycamera.CameraConnectionClient
import io.github.gallo.sonycamera.CameraConnectionState
import io.github.gallo.sonycamera.ptp.PtpCardObject
import com.wedfaces.cameraoperator.model.CameraPhoto
import com.wedfaces.cameraoperator.model.CameraStorage
import com.wedfaces.cameraoperator.model.PhotoSource
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * WED Faces adapter over the Sony USB library's single service-owned PTP session.
 * The library's transport lock serializes browsing, thumbnails, events and reads.
 */
class SonyUsbCameraAdapter(
    context: Context,
    val client: CameraConnectionClient,
) {
    private val appContext = context.applicationContext
    private val usbManager = appContext.getSystemService(UsbManager::class.java)
    val connectionState get() = client.connectionState
    val cameraName get() = client.cameraName
    val cameraSerialNumber get() = client.cameraSerialNumber
    val events get() = client.events

    fun attachedDevice(): UsbDevice? =
        usbManager?.deviceList?.values?.firstOrNull { device ->
            device.vendorId == SONY_VENDOR_ID && (0 until device.interfaceCount).any { index ->
                val usbInterface = device.getInterface(index)
                UsbPtpInterfaceMatcher.isCompatible(
                    usbInterface.interfaceClass,
                    (0 until usbInterface.endpointCount).map { endpointIndex ->
                        usbInterface.getEndpoint(endpointIndex).let { endpoint ->
                            UsbPtpInterfaceMatcher.EndpointDescriptor(endpoint.type, endpoint.direction)
                        }
                    },
                )
            }
        }

    fun hasUsbHostSupport(): Boolean =
        appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)

    fun connect() = client.connectToCamera()
    fun disconnect() = client.disconnect()
    suspend fun disconnectAndWait() = client.disconnectAndWait()
    fun isReady() = client.isReady()

    fun cameraIdentity(): String {
        val serial = cameraSerialNumber.value?.takeIf(String::isNotBlank)
        val model = cameraName.value?.takeIf(String::isNotBlank) ?: "Sony USB camera"
        return if (serial != null) "$model#$serial" else model
    }

    suspend fun listStorages(): List<CameraStorage> =
        client.getCardStorages().map { CameraStorage(it.storageId.toString(), it.name) }

    suspend fun listFolder(storageId: String, parentObjectId: String? = null): List<CameraPhoto> {
        val storage = storageId.toLongOrNull()
            ?: throw IllegalArgumentException("The selected camera storage is invalid.")
        val parent = parentObjectId?.let(::parseObjectId)?.second ?: 0L
        val handles = client.getCardObjectHandles(storage, parent)
        val entries = ArrayList<CameraPhoto>(handles.size)
        for (handle in handles) {
            try {
                val info = client.getCardObjectInfo(handle)
                if (info.storageId != storage) continue
                entries += info.toCameraPhoto()
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                // Continue listing when one handle is stale or unreadable.
            }
        }
        return entries.sortedWith(
            compareByDescending<CameraPhoto> { it.isFolder }
                .thenByDescending { it.capturedAtMillis ?: 0L },
        )
    }

    suspend fun listAllPhotos(): List<CameraPhoto> {
        val results = mutableListOf<CameraPhoto>()
        val pending = ArrayDeque<Pair<String, String?>>()
        listStorages().forEach { pending.add(it.id to null) }
        val visited = mutableSetOf<String>()
        while (pending.isNotEmpty()) {
            val (storageId, parentId) = pending.removeFirst()
            val parentKey = "$storageId:${parentId ?: "root"}"
            if (!visited.add(parentKey)) continue
            for (entry in listFolder(storageId, parentId)) {
                if (entry.isFolder) pending.add(storageId to entry.objectId)
                else results += entry
            }
        }
        return results
    }

    suspend fun getPhotoInfo(handle: Long): CameraPhoto =
        client.getCardObjectInfo(handle).toCameraPhoto()

    suspend fun getThumbnail(objectId: String): ByteArray? {
        val (storageId, handle) = parseObjectId(objectId)
        val info = client.getCardObjectInfo(handle)
        if (info.storageId != storageId || info.isFolder) return null
        return client.getCardThumbnail(handle)?.takeIf { it.isNotEmpty() && it.size <= MAX_THUMBNAIL_BYTES }
    }

    suspend fun downloadOriginal(
        objectId: String,
        destination: File,
        onProgress: (sent: Long, total: Long) -> Unit,
    ) {
        val (storageId, handle) = parseObjectId(objectId)
        withContext(Dispatchers.IO) {
            destination.parentFile?.mkdirs()
            try {
                val info = client.getCardObjectInfo(handle)
                require(info.storageId == storageId && !info.isFolder) {
                    "This camera object no longer matches the selected card entry. Refresh and retry."
                }
                FileOutputStream(destination).use { output ->
                    client.downloadCardObject(handle, output, onProgress)
                }
                require(destination.isFile && destination.length() > 0L) {
                    "The camera returned an incomplete or empty original."
                }
                if (info.sizeBytes > 0L) {
                    require(destination.length() == info.sizeBytes) {
                        "The camera transfer was incomplete (${destination.length()} of ${info.sizeBytes} bytes)."
                    }
                }
            } catch (error: Exception) {
                destination.delete()
                throw error
            }
        }
    }

    private fun PtpCardObject.toCameraPhoto() = CameraPhoto(
        objectId = "$storageId:$handle",
        fileName = fileName,
        sizeBytes = sizeBytes.takeIf { it > 0L },
        capturedAtMillis = capturedAtMillis,
        formatCode = formatCode,
        mimeType = mimeTypeFor(fileName),
        source = PhotoSource.CAMERA,
        storageId = storageId.toString(),
        storageName = "Card",
        parentObjectId = parentHandle.takeIf { it > 0L }?.let { "$storageId:$it" },
        isFolder = isFolder,
    )

    private fun parseObjectId(objectId: String): Pair<Long, Long> {
        val parts = objectId.split(':', limit = 2)
        require(parts.size == 2) { "Camera object identity is invalid." }
        val storageId = parts[0].toLongOrNull()
            ?: throw IllegalArgumentException("Camera storage identity is invalid.")
        val handle = parts[1].toLongOrNull()
            ?: throw IllegalArgumentException("Camera object handle is invalid.")
        return storageId to handle
    }

    private fun mimeTypeFor(name: String): String? = when (name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "arw" -> "image/x-sony-arw"
        "mp4", "mov", "mts", "m2ts", "avi" -> "video/*"
        else -> null
    }

    companion object {
        private const val MAX_THUMBNAIL_BYTES = 512 * 1024
        private const val SONY_VENDOR_ID = 0x054C
    }
}

