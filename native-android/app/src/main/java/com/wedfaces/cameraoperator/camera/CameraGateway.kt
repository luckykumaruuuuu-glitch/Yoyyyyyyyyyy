package com.wedfaces.cameraoperator.camera

import android.net.Network
import com.wedfaces.cameraoperator.model.CameraPhoto
import com.wedfaces.cameraoperator.model.CameraStorage
import java.io.File

interface CameraGateway {
    suspend fun connect(network: Network, endpoint: String? = null): String
    suspend fun listObjects(): List<CameraPhoto>
    suspend fun listStorages(): List<CameraStorage> = listOf(CameraStorage("0", "Camera card"))
    suspend fun listFolder(storageId: String, parentObjectId: String? = null): List<CameraPhoto> =
        listObjects()
    suspend fun getThumbnail(objectId: String): ByteArray?
    suspend fun downloadSelectedOriginal(
        objectId: String,
        destination: File,
        onProgress: (sent: Long, total: Long) -> Unit,
    )
    suspend fun disconnect()
}
