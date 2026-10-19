package com.wedfaces.cameraoperator.model

/**
 * The existing WED Faces import endpoint accepts only these image formats.
 * Keep the camera browser, preview, gallery save, and queue checks aligned.
 */
object CameraFileSupport {
    private val mimeTypes = mapOf(
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "png" to "image/png",
        "webp" to "image/webp",
    )

    fun mimeType(fileName: String): String? =
        mimeTypes[fileName.substringAfterLast('.', "").lowercase()]

    fun isSupported(fileName: String): Boolean = mimeType(fileName) != null
}
