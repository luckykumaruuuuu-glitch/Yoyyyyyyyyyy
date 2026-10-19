package com.wedfaces.cameraoperator.model

import android.net.Uri

data class Album(
    val id: String,
    val name: String,
)

data class CameraPhoto(
    val objectId: String,
    val fileName: String,
    val sizeBytes: Long?,
    val capturedAtMillis: Long?,
    val formatCode: Int?,
    val mimeType: String?,
    val source: PhotoSource,
    val testUri: Uri? = null,
    val alreadySent: Boolean = false,
    val storageId: String? = null,
    val storageName: String? = null,
    val parentObjectId: String? = null,
    val isFolder: Boolean = false,
)

data class CameraStorage(
    val id: String,
    val name: String,
)

enum class CameraTransportType {
    WIFI,
    USB,
    NONE,
}

enum class PhotoSource {
    CAMERA,
    TEST_GALLERY,
    FOLDER_WATCH,
}

enum class QueueState(val title: String) {
    SELECTED("Selected"),
    WAITING_FOR_CAMERA("Waiting for camera"),
    DOWNLOADING("Downloading original"),
    WAITING_FOR_INTERNET("Waiting for internet"),
    READY_TO_UPLOAD("Ready to upload"),
    UPLOADING("Uploading"),
    UPLOADED_WAITING_IMPORT("Uploaded · waiting for import"),
    IMPORTED("Imported"),
    FAILED("Failed · retry available"),
    IMPORT_REVIEW("Import result needs review"),
    AUTH_REQUIRED("Sign in again"),
    PAUSED("Paused"),
    UNSUPPORTED("Unsupported format"),
    CANCELLED("Cancelled"),
}

data class TransferItem(
    val id: String,
    val uploadId: String,
    val batchId: String,
    val albumId: String,
    val albumName: String,
    val source: PhotoSource,
    val sourceObjectId: String,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long?,
    val sourceCapturedAtMillis: Long?,
    val temporaryFilePath: String?,
    val state: QueueState,
    val progressBytes: Long,
    val attempts: Int,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val lastError: String?,
    val importStarted: Boolean,
    val transportType: CameraTransportType = if (source == PhotoSource.CAMERA) {
        CameraTransportType.WIFI
    } else {
        CameraTransportType.NONE
    },
)

data class ImportStatus(
    val state: String,
    val total: Int?,
    val imported: Int?,
    val duplicates: Int?,
    val failed: Int?,
    val failures: List<String>,
    val error: String?,
    val results: List<ImportFileResult>? = null,
)

data class ImportFileResult(
    val uploadId: String?,
    val fileName: String?,
    val success: Boolean?,
    val error: String?,
)

data class CameraConnection(
    val model: String,
    val address: String,
)
