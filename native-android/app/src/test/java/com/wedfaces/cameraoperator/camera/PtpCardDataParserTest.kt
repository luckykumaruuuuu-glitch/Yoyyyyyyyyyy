package com.wedfaces.cameraoperator.camera

import com.wedfaces.cameraoperator.model.CameraFileSupport
import io.github.gallo.sonycamera.ptp.PtpCardDataParser
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PtpCardDataParserTest {
    @Test
    fun storageAndObjectHandleArraysKeepUnsignedIdentifiers() {
        val storageIds = uint32Array(0x8000_0001L, 0xF000_0002L)
        val handles = uint32Array(1L, 0xFFFF_FFFEL)

        assertEquals(listOf(0x8000_0001L, 0xF000_0002L), PtpCardDataParser.storageIds(storageIds))
        assertEquals(listOf(1L, 0xFFFF_FFFEL), PtpCardDataParser.objectHandles(handles))
    }

    @Test
    fun objectInfoPreservesStorageParentAndFolderVersusImageType() {
        val folder = PtpCardDataParser.objectInfo(
            handle = 0x8000_0004L,
            data = objectInfo(
                storageId = 0xF000_0001L,
                formatCode = 0x3001,
                parentHandle = 0,
                associationType = 1,
                fileName = "DCIM",
            ),
        )
        val image = PtpCardDataParser.objectInfo(
            handle = 0x8000_0005L,
            data = objectInfo(
                storageId = 0xF000_0001L,
                formatCode = 0x3801,
                parentHandle = 0x8000_0004L,
                associationType = 0,
                fileName = "DSC0001.JPG",
                size = 4_096,
                captureDate = "20261020T020100",
            ),
        )

        assertTrue(folder.isFolder)
        assertEquals("DCIM", folder.fileName)
        assertEquals(0xF000_0001L, image.storageId)
        assertEquals(0x8000_0004L, image.parentHandle)
        assertEquals("DSC0001.JPG", image.fileName)
        assertEquals(4_096L, image.sizeBytes)
        assertTrue(image.capturedAtMillis != null)
        assertFalse(image.isFolder)
        assertTrue(CameraFileSupport.isSupported(image.fileName))
        assertFalse(CameraFileSupport.isSupported("DSC0002.ARW"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsTruncatedUnsignedArrays() {
        PtpCardDataParser.storageIds(byteArrayOf(2, 0, 0, 0, 1, 0, 0, 0))
    }

    private fun uint32Array(vararg values: Long): ByteArray =
        ByteBuffer.allocate(4 + values.size * 4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(values.size)
            .also { buffer -> values.forEach { buffer.putInt(it.toInt()) } }
            .array()

    private fun objectInfo(
        storageId: Long,
        formatCode: Int,
        parentHandle: Long,
        associationType: Int,
        fileName: String,
        size: Long = 0,
        captureDate: String = "",
    ): ByteArray {
        val buffer = ByteBuffer.allocate(128).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(storageId.toInt())
        buffer.putShort(formatCode.toShort())
        buffer.putShort(0) // protection status
        buffer.putInt(size.toInt())
        buffer.putShort(0) // thumbnail format
        repeat(3) { buffer.putInt(0) } // thumbnail size/width/height
        repeat(3) { buffer.putInt(0) } // image width/height/bit depth
        buffer.putInt(parentHandle.toInt())
        buffer.putShort(associationType.toShort())
        buffer.putInt(0) // association description
        buffer.putInt(0) // sequence number
        buffer.putPtpString(fileName)
        buffer.putPtpString(captureDate)
        return buffer.array().copyOf(buffer.position())
    }

    private fun ByteBuffer.putPtpString(value: String) {
        put((value.length + 1).toByte())
        value.forEach { putChar(it) }
        putChar('\u0000')
    }
}
