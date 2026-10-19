package com.wedfaces.cameraoperator.camera

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import com.fimagena.libptp.PtpConnection
import com.fimagena.libptp.PtpDataType
import com.fimagena.libptp.PtpSession
import com.fimagena.libptp.ptpip.PtpIpConnection
import com.wedfaces.cameraoperator.model.CameraPhoto
import com.wedfaces.cameraoperator.model.CameraStorage
import com.wedfaces.cameraoperator.model.PhotoSource
import java.io.File
import java.io.FileOutputStream
import java.net.InetAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

/**
 * Sony Wi-Fi PTP/IP adapter. Browsing performs only GetStorageIDs,
 * GetObjectHandles, and GetObjectInfo. GetObject is invoked only by the
 * explicit selected-original transfer method.
 */
class SonyPtpIpCameraAdapter(context: Context) : CameraGateway {
    private val connectivityManager = context.applicationContext
        .getSystemService(ConnectivityManager::class.java)
    private val operationMutex = Mutex()
    private val downloadsWaiting = AtomicInteger(0)
    private var connection: PtpConnection? = null
    private var session: PtpSession? = null

    override suspend fun connect(network: Network, endpoint: String?): String =
        operationMutex.withLock {
            withContext(Dispatchers.IO) {
                closeCurrentConnection()
                val address = endpoint?.trim()?.takeIf(String::isNotBlank)
                    ?: defaultGatewayAddress(network)
                    ?: throw IllegalStateException(
                        "Android could not identify the camera Wi-Fi gateway. Connect to the camera Wi-Fi or enter its IP address.",
                    )

                try {
                    val transport = PtpIpConnection(null, network.socketFactory)
                    val ptp = PtpConnection(transport)
                    val host = PtpIpConnection.PtpIpHostId(
                        SONY_PTP_GUID,
                        "WED Faces Camera Operator",
                    )
                    ptp.connect(PtpIpConnection.PtpIpAddress(InetAddress.getByName(address), PTP_IP_PORT), host)
                    val openedSession = ptp.openSession()
                    connection = ptp
                    session = openedSession
                    ptp.deviceInfo?.mModel?.mString?.takeIf(String::isNotBlank) ?: "Sony camera"
                } catch (error: Exception) {
                    closeCurrentConnection()
                    throw IllegalStateException(
                        "Could not open a Sony PTP/IP session at $address:$PTP_IP_PORT. Confirm the camera is in Send to Smartphone mode and awake.",
                        error,
                    )
                }
            }
        }

    override suspend fun listObjects(): List<CameraPhoto> =
        operationMutex.withLock {
            withContext(Dispatchers.IO) {
                val activeSession = session
                    ?: throw IllegalStateException("No camera session is open.")
                activeSession.storageIDs.flatMap { storage ->
                    listCameraFolder(activeSession, storage.mValue.toString(), null)
                }
            }
        }

    override suspend fun listStorages(): List<CameraStorage> =
        operationMutex.withLock {
            withContext(Dispatchers.IO) {
                val activeSession = session ?: throw IllegalStateException("No camera session is open.")
                activeSession.storageIDs.mapIndexed { index, storage ->
                    CameraStorage(storage.mValue.toString(), "Card ${index + 1}")
                }
            }
        }

    override suspend fun listFolder(storageId: String, parentObjectId: String?): List<CameraPhoto> =
        operationMutex.withLock {
            withContext(Dispatchers.IO) {
                val activeSession = session ?: throw IllegalStateException("No camera session is open.")
                listCameraFolder(activeSession, storageId, parentObjectId)
            }
        }

    private suspend fun listCameraFolder(
        activeSession: PtpSession,
        storageId: String,
        parentObjectId: String?,
    ): List<CameraPhoto> {
        val storageValue = storageId.toLongOrNull()
            ?: throw IllegalArgumentException("The selected camera storage is invalid.")
        val parentHandle = parentObjectId?.let(::parseObjectId)?.second ?: 0L
        val handles = activeSession.getObjectHandles(
            PtpDataType.StorageID(storageValue),
            PtpDataType.ObjectFormatCode(),
            PtpDataType.ObjectHandle(parentHandle),
        )
        val entries = mutableListOf<CameraPhoto>()
        for (handle in handles) {
            currentCoroutineContext().ensureActive()
            try {
                val info = activeSession.getObjectInfo(handle)
                val format = info.mObjectFormatCode.mValue.toInt()
                val filename = info.mFilename.mString?.takeIf(String::isNotBlank)
                    ?: "CAM_${handle.mValue}"
                val objectId = "${storageValue}:${handle.mValue}"
                val parent = info.mParentObject.mValue.takeIf { it > 0L }
                    ?.let { "${storageValue}:$it" }
                entries += CameraPhoto(
                    objectId = objectId,
                    fileName = filename,
                    sizeBytes = info.mObjectCompressedSize.mValue.takeIf { it > 0 },
                    capturedAtMillis = info.mCaptureDate.mDate?.time,
                    formatCode = format,
                    mimeType = mimeTypeFor(filename),
                    source = PhotoSource.CAMERA,
                    storageId = storageValue.toString(),
                    storageName = "Card",
                    parentObjectId = parent,
                    isFolder = format == FMT_ASSOCIATION,
                )
            } catch (error: Exception) {
                // One unreadable/stale item must not hide other card entries.
                if (error is kotlinx.coroutines.CancellationException) throw error
            }
        }
        return entries.sortedWith(compareByDescending<CameraPhoto> { it.isFolder }
            .thenByDescending { it.capturedAtMillis ?: 0L })
    }

    override suspend fun getThumbnail(objectId: String): ByteArray? =
        if (downloadsWaiting.get() > 0) {
            null
        } else operationMutex.withLock {
            withContext(Dispatchers.IO) {
                if (downloadsWaiting.get() > 0) return@withContext null
                val activeSession = session ?: return@withContext null
                val handle = runCatching { parseObjectId(objectId).second }.getOrNull()
                    ?: return@withContext null
                try {
                    activeSession.getThumb(PtpDataType.ObjectHandle(handle))
                        ?.takeIf { it.isNotEmpty() && it.size <= MAX_THUMBNAIL_BYTES }
                } catch (_: Exception) {
                    null
                }
            }
        }

    override suspend fun downloadSelectedOriginal(
        objectId: String,
        destination: File,
        onProgress: (sent: Long, total: Long) -> Unit,
    ) {
        downloadsWaiting.incrementAndGet()
        try {
            operationMutex.withLock {
                withContext(Dispatchers.IO) {
                    val activeSession = session ?: throw IllegalStateException("Camera disconnected before the original was retrieved.")
                    val handle = parseObjectId(objectId).second
                    destination.parentFile?.mkdirs()
                    try {
                        FileOutputStream(destination).use { output ->
                            activeSession.getObjectToStream(
                                PtpDataType.ObjectHandle(handle),
                                output,
                                object : PtpSession.DataLoadListener {
                                    override fun onDataLoaded(loaded: Long, expected: Long) {
                                        onProgress(loaded, expected)
                                    }
                                },
                            )
                        }
                        if (!destination.isFile || destination.length() == 0L) {
                            throw IllegalStateException("The camera returned an empty original file.")
                        }
                    } catch (error: Exception) {
                        destination.delete()
                        throw error
                    }
                }
            }
        } finally {
            downloadsWaiting.decrementAndGet()
        }
    }

    override suspend fun disconnect() {
        operationMutex.withLock {
            withContext(Dispatchers.IO) { closeCurrentConnection() }
        }
    }

    private fun closeCurrentConnection() {
        try {
            session?.close()
        } catch (_: Exception) {
        }
        try {
            connection?.close()
        } catch (_: Exception) {
        }
        session = null
        connection = null
    }

    private fun defaultGatewayAddress(network: Network): String? =
        connectivityManager.getLinkProperties(network)?.routes
            ?.firstOrNull { it.isDefaultRoute && it.gateway != null }
            ?.gateway
            ?.hostAddress

    private fun parseObjectId(objectId: String): Pair<Long, Long> {
        val parts = objectId.split(':', limit = 2)
        return when (parts.size) {
            1 -> 0L to (parts[0].toLongOrNull()
                ?: throw IllegalArgumentException("Camera object handle is invalid."))
            2 -> (parts[0].toLongOrNull()
                ?: throw IllegalArgumentException("Camera storage identity is invalid.")) to
                (parts[1].toLongOrNull()
                    ?: throw IllegalArgumentException("Camera object handle is invalid."))
            else -> throw IllegalArgumentException("Camera object identity is invalid.")
        }
    }

    private fun mimeTypeFor(name: String): String? = when (name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "tif", "tiff" -> "image/tiff"
        "arw", "cr2", "cr3", "nef", "dng" -> "image/x-raw"
        "mp4", "mov", "mts", "m2ts", "avi" -> "video/*"
        else -> null
    }

    private companion object {
        const val PTP_IP_PORT = 15740
        const val FMT_ASSOCIATION = 0x3001
        const val MAX_THUMBNAIL_BYTES = 512 * 1024
        val SONY_PTP_GUID = shortArrayOf(
            0, 0, 0, 0, 0, 0, 0, 0,
            0xff.toShort(), 0xff.toShort(), 0, 0, 0, 0, 0, 0,
        )
    }
}
