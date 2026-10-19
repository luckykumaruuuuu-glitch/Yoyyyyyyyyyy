package com.wedfaces.cameraoperator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderWatchPolicyTest {
    @Test
    fun onlySupportedStillImageExtensionsAreAccepted() {
        assertTrue(FolderWatchPolicy.isSupportedFileName("Wedding.JPG"))
        assertTrue(FolderWatchPolicy.isSupportedFileName("image.jpeg"))
        assertTrue(FolderWatchPolicy.isSupportedFileName("image.png"))
        assertTrue(FolderWatchPolicy.isSupportedFileName("image.webp"))
        assertFalse(FolderWatchPolicy.isSupportedFileName("camera.arw"))
        assertFalse(FolderWatchPolicy.isSupportedFileName("video.mp4"))
    }

    @Test
    fun existingFilesAreExcludedUntilExplicitlyIncluded() {
        assertFalse(FolderWatchPolicy.shouldInclude(1_000L, 1_000L, includeExisting = false))
        assertFalse(FolderWatchPolicy.shouldInclude(900L, 1_000L, includeExisting = false))
        assertTrue(FolderWatchPolicy.shouldInclude(1_001L, 1_000L, includeExisting = false))
        assertTrue(FolderWatchPolicy.shouldInclude(900L, 1_000L, includeExisting = true))
    }

    @Test
    fun changedDocumentsGetDistinctQueueIdentities() {
        assertEquals(
            "content://images/1|100|200",
            FolderWatchPolicy.sourceIdentity("content://images/1", 100L, 200L),
        )
        assertFalse(
            FolderWatchPolicy.sourceIdentity("content://images/1", 100L, 200L) ==
                FolderWatchPolicy.sourceIdentity("content://images/1", 101L, 200L),
        )
    }
}
