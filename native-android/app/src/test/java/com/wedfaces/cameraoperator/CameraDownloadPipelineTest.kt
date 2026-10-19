package com.wedfaces.cameraoperator

import android.app.Application
import android.net.Network
import com.wedfaces.cameraoperator.camera.CameraGateway
import com.wedfaces.cameraoperator.data.TransferQueueRepository
import com.wedfaces.cameraoperator.model.CameraPhoto
import com.wedfaces.cameraoperator.model.PhotoSource
import com.wedfaces.cameraoperator.model.QueueState
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class CameraDownloadPipelineTest {
    private lateinit var queue: TransferQueueRepository
    private lateinit var databaseName: String
    private lateinit var downloadDirectory: File

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        databaseName = "camera-download-${UUID.randomUUID()}.db"
        queue = TransferQueueRepository(context, databaseName)
        downloadDirectory = File(context.cacheDir, "camera-download-${UUID.randomUUID()}")
    }

    @After
    fun tearDown() {
        queue.close()
        RuntimeEnvironment.getApplication<Application>().deleteDatabase(databaseName)
        downloadDirectory.deleteRecursively()
    }

    @Test
    fun firstOriginalEntersUploadQueueBeforeNextCameraDownloadStarts() = runBlocking {
        val batchId = "download-pipeline"
        addCameraItem(batchId, "first.jpg")
        addCameraItem(batchId, "second.jpg")
        val orderedItems = queue.getBatch(batchId)
        val first = orderedItems.first()
        val second = orderedItems.last()
        val enqueuedBatches = mutableListOf<String>()
        val camera = camera { objectId, destination ->
            if (objectId == second.sourceObjectId) {
                assertEquals(QueueState.READY_TO_UPLOAD, item(first.id).state)
                assertEquals(listOf(batchId), enqueuedBatches)
            }
            destination.writeBytes(byteArrayOf(1, 2, 3))
        }

        downloadQueuedOriginals(
            batchId = batchId,
            downloadsDirectory = downloadDirectory,
            queue = queue,
            camera = camera,
            enqueueBatch = enqueuedBatches::add,
        )

        assertEquals(QueueState.READY_TO_UPLOAD, item(first.id).state)
        assertEquals(QueueState.READY_TO_UPLOAD, item(second.id).state)
        assertTrue(queue.isBatchDownloadComplete(batchId))
        assertEquals(listOf(batchId, batchId, batchId), enqueuedBatches)
    }

    @Test
    fun oneCameraReadFailureDoesNotBlockLaterOriginals() = runBlocking {
        val batchId = "download-one-failure"
        val bad = addCameraItem(batchId, "bad.jpg")
        val good = addCameraItem(batchId, "good.jpg")
        val enqueuedBatches = mutableListOf<String>()
        val camera = camera { objectId, destination ->
            if (objectId == bad.sourceObjectId) throw IOException("camera read failed")
            destination.writeBytes(byteArrayOf(1, 2, 3))
        }

        downloadQueuedOriginals(
            batchId = batchId,
            downloadsDirectory = downloadDirectory,
            queue = queue,
            camera = camera,
            enqueueBatch = enqueuedBatches::add,
        )

        assertEquals(QueueState.FAILED, item(bad.id).state)
        assertEquals(QueueState.READY_TO_UPLOAD, item(good.id).state)
        assertTrue(queue.isBatchDownloadComplete(batchId))
        assertEquals(2, enqueuedBatches.size)
    }

    @Test
    fun cancelledCameraReadDoesNotMarkBatchCompleteOrEnqueueImport() = runBlocking {
        val batchId = "download-cancelled"
        val interrupted = addCameraItem(batchId, "interrupted.jpg")
        val notStarted = addCameraItem(batchId, "not-started.jpg")
        val enqueuedBatches = mutableListOf<String>()
        val camera = camera { _, _ -> throw CancellationException("service stopped") }

        try {
            downloadQueuedOriginals(
                batchId = batchId,
                downloadsDirectory = downloadDirectory,
                queue = queue,
                camera = camera,
                enqueueBatch = enqueuedBatches::add,
            )
            throw AssertionError("Expected the camera read to be cancelled.")
        } catch (_: CancellationException) {
            // Expected: the queue preserves the interrupted work for an explicit retry.
        }

        assertEquals(QueueState.FAILED, item(interrupted.id).state)
        assertEquals(QueueState.FAILED, item(notStarted.id).state)
        assertFalse(queue.isBatchDownloadComplete(batchId))
        assertTrue(enqueuedBatches.isEmpty())
    }

    private fun addCameraItem(batchId: String, fileName: String) =
        TransferQueueRepository.newTransfer(
            batchId = batchId,
            albumId = "album",
            albumName = "Wedding",
            source = PhotoSource.CAMERA,
            sourceObjectId = "camera:${fileName}",
            fileName = fileName,
            mimeType = "image/jpeg",
            sizeBytes = 3L,
            initialState = QueueState.DOWNLOADING,
        ).also(queue::insert)

    private fun item(id: String) = queue.getAll().single { it.id == id }

    private fun camera(
        download: (objectId: String, destination: File) -> Unit,
    ) = object : CameraGateway {
        override suspend fun connect(network: Network, endpoint: String?) = "Test camera"
        override suspend fun listObjects() = emptyList<CameraPhoto>()
        override suspend fun getThumbnail(objectId: String): ByteArray? = null
        override suspend fun downloadSelectedOriginal(
            objectId: String,
            destination: File,
            onProgress: (sent: Long, total: Long) -> Unit,
        ) {
            download(objectId, destination)
            onProgress(destination.length(), destination.length())
        }
        override suspend fun disconnect() = Unit
    }
}
