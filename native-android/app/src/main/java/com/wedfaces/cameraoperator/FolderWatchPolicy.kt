package com.wedfaces.cameraoperator

import java.util.Locale

internal object FolderWatchPolicy {
    private val supportedExtensions = setOf("jpg", "jpeg", "png", "webp")

    fun isSupportedFileName(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase(Locale.ROOT) in supportedExtensions

    fun shouldInclude(
        lastModifiedMillis: Long,
        activatedAtMillis: Long,
        includeExisting: Boolean,
    ): Boolean = includeExisting || lastModifiedMillis > activatedAtMillis

    fun sourceIdentity(uri: String, lastModifiedMillis: Long, sizeBytes: Long?): String =
        "$uri|$lastModifiedMillis|${sizeBytes ?: -1L}"

    fun mimeTypeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        else -> "image/webp"
    }
}
