package com.wedfaces.cameraoperator.camera

import com.wedfaces.cameraoperator.model.CameraFileSupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraFileSupportTest {
    @Test
    fun matchesExactlyTheServerSupportedImageFormatsCaseInsensitively() {
        listOf("photo.jpg", "photo.JPEG", "photo.png", "photo.WebP").forEach {
            assertTrue("$it should be uploadable", CameraFileSupport.isSupported(it))
        }
        listOf("photo.arw", "movie.mp4", "photo.gif", "no-extension").forEach {
            assertFalse("$it should not be uploadable", CameraFileSupport.isSupported(it))
        }
        assertEquals("image/jpeg", CameraFileSupport.mimeType("photo.JPEG"))
        assertEquals("image/webp", CameraFileSupport.mimeType("photo.webp"))
    }
}
