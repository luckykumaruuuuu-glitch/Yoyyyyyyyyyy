package io.github.gallo.sonycamera.ptp

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class PtpCardStorage(
    val storageId: Long,
    val name: String,
)

data class PtpCardObject(
    val storageId: Long,
    val handle: Long,
    val parentHandle: Long,
    val formatCode: Int,
    val associationType: Int,
    val fileName: String,
    val sizeBytes: Long,
    val captureDate: String?,
    val capturedAtMillis: Long?,
) {
    val isFolder: Boolean get() = formatCode == PTP_FORMAT_ASSOCIATION

    companion object {
        const val PTP_FORMAT_ASSOCIATION = 0x3001
        const val PTP_FORMAT_JPEG = 0x3801
        const val PTP_FORMAT_PNG = 0x380B
        const val PTP_FORMAT_WEBP = 0x3810
    }
}

/**
 * Parsers for the standard PTP card-browsing data sets. These are intentionally
 * independent of Android and are covered by unit tests.
 */
object PtpCardDataParser {
    fun storageIds(data: ByteArray): List<Long> = parseUInt32Array(data)

    fun objectHandles(data: ByteArray): List<Long> = parseUInt32Array(data)

    fun objectInfo(handle: Long, data: ByteArray): PtpCardObject {
        val input = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        fun readUInt16(): Int {
            require(input.remaining() >= 2) { "Truncated PTP object information." }
            return input.short.toInt() and 0xFFFF
        }
        fun readUInt32(): Long {
            require(input.remaining() >= 4) { "Truncated PTP object information." }
            return Integer.toUnsignedLong(input.int)
        }
        fun readPtpString(): String {
            require(input.hasRemaining()) { "Truncated PTP string." }
            val units = input.get().toInt() and 0xFF
            if (units == 0) return ""
            val byteCount = units * 2
            require(input.remaining() >= byteCount) { "Truncated PTP string data." }
            val bytes = ByteArray(byteCount)
            input.get(bytes)
            return String(bytes, StandardCharsets.UTF_16LE).trimEnd('\u0000')
        }

        val storageId = readUInt32()
        val formatCode = readUInt16()
        readUInt16() // protection status
        val size = readUInt32()
        readUInt16() // thumbnail format
        readUInt32() // thumbnail size
        readUInt32() // thumbnail width
        readUInt32() // thumbnail height
        readUInt32() // image width
        readUInt32() // image height
        readUInt32() // image bit depth
        val parentHandle = readUInt32()
        val associationType = readUInt16()
        readUInt32() // association description
        readUInt32() // sequence number
        val filename = readPtpString().ifBlank { "CAM_$handle" }
        val captureDate = readPtpString().ifBlank { null }
        val capturedAtMillis = captureDate?.let(::parseCaptureDate)
        return PtpCardObject(
            storageId = storageId,
            handle = handle,
            parentHandle = parentHandle,
            formatCode = formatCode,
            associationType = associationType,
            fileName = filename,
            sizeBytes = size,
            captureDate = captureDate,
            capturedAtMillis = capturedAtMillis,
        )
    }

    private fun parseUInt32Array(data: ByteArray): List<Long> {
        require(data.size >= 4) { "Truncated PTP array." }
        val input = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val count = Integer.toUnsignedLong(input.int)
        require(count <= MAX_ARRAY_ITEMS && input.remaining().toLong() >= count * 4) {
            "Invalid PTP array length."
        }
        return List(count.toInt()) { Integer.toUnsignedLong(input.int) }
    }

    private fun parseCaptureDate(value: String): Long? = try {
        val format = SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.ROOT)
        val core = value.take(15)
        format.isLenient = false
        format.parse(core)?.time
    } catch (_: Exception) {
        null
    }

    private const val MAX_ARRAY_ITEMS = 250_000L
}

