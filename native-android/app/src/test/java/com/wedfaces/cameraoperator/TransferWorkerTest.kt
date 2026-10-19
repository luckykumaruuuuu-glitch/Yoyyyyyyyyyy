package com.wedfaces.cameraoperator

import com.wedfaces.cameraoperator.AppServices
import com.wedfaces.cameraoperator.data.TokenStore
import com.wedfaces.cameraoperator.model.PhotoSource
import com.wedfaces.cameraoperator.model.QueueState
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = CameraOperatorApp::class)
class TransferWorkerTest {
    private val server = MockWebServer()
    private lateinit var app: CameraOperatorApp
    private lateinit var services: AppServices
    private lateinit var databaseName: String
    private val files = mutableListOf<File>()
    private val retryDelays = mutableListOf<Long>()

    @Before
    fun setUp() {
        server.start()
        app = RuntimeEnvironment.getApplication() as CameraOperatorApp
        databaseName = "transfer-worker-${UUID.randomUUID()}.db"
        retryDelays.clear()
        services = AppServices(
            app,
            MemoryTokenStore(),
            databaseName,
            retrySchedulerForTest = { _, delay -> retryDelays += delay },
        ).also {
            it.settings.saveServerUrl(server.url("/").toString())
            it.settings.setAllowLocalHttp(true)
            it.tokens.save("access-token", "refresh-token")
        }
        app.replaceServicesForTest(services)
    }

    @After
    fun tearDown() {
        services.queue.close()
        app.deleteDatabase(databaseName)
        server.shutdown()
        files.forEach(File::delete)
    }

    @Test
    fun onePermanentBadFileDoesNotStopOtherFilesInTheBatch() = runBlocking {
        installCompletedImportDispatcher { request ->
            val body = request.body.readUtf8()
            if (body.contains("bad.jpg")) {
                MockResponse().setResponseCode(400).setBody("""{"error":"invalid image"}""")
            } else {
                MockResponse().setResponseCode(201).setBody("""{"ok":true}""")
            }
        }
        val batch = "bad-file-isolation"
        val bad = addItem(batch, "bad.jpg", QueueState.READY_TO_UPLOAD)
        val good = addItem(batch, "good.jpg", QueueState.READY_TO_UPLOAD)

        val result = runBatch(batch)

        assertEquals(androidx.work.ListenableWorker.Result.success(), result)
        assertEquals(QueueState.FAILED, item(bad.id).state)
        assertTrue(item(bad.id).lastError!!.contains("invalid image"))
        assertEquals(QueueState.IMPORTED, item(good.id).state)
        assertTrue(File(item(bad.id).temporaryFilePath!!).exists())
        assertFalse(File(item(good.id).temporaryFilePath!!).exists())
    }

    @Test
    fun http413AndLocal415OnlyFailTheirOwnFiles() = runBlocking {
        installCompletedImportDispatcher { request ->
            val body = request.body.readUtf8()
            if (body.contains("too-large.jpg")) {
                MockResponse().setResponseCode(413).setBody("""{"error":"too large"}""")
            } else {
                MockResponse().setResponseCode(201).setBody("""{"ok":true}""")
            }
        }
        val batch = "per-file-http-errors"
        val tooLarge = addItem(batch, "too-large.jpg", QueueState.READY_TO_UPLOAD)
        val unsupported = addItem(batch, "capture.arw", QueueState.READY_TO_UPLOAD, "image/x-raw")
        val good = addItem(batch, "good.jpg", QueueState.READY_TO_UPLOAD)

        runBatch(batch)

        assertEquals(QueueState.FAILED, item(tooLarge.id).state)
        assertTrue(item(tooLarge.id).lastError!!.contains("upload size limit"))
        assertEquals(QueueState.FAILED, item(unsupported.id).state)
        assertTrue(item(unsupported.id).lastError!!.contains("JPG/JPEG, PNG, and WEBP"))
        assertEquals(QueueState.IMPORTED, item(good.id).state)
    }

    @Test
    fun http429HonorsRetryAfterAndKeepsTheOriginalQueued() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse()
                    .setResponseCode(429)
                    .setHeader("Retry-After", "60")
                    .setBody("""{"error":"rate limited"}""")
        }
        val batch = "rate-limited"
        val queued = addItem(batch, "later.jpg", QueueState.READY_TO_UPLOAD)

        val result = runBatch(batch)

        assertEquals(androidx.work.ListenableWorker.Result.success(), result)
        assertEquals(QueueState.WAITING_FOR_INTERNET, item(queued.id).state)
        assertEquals(1, item(queued.id).attempts)
        assertTrue(item(queued.id).temporaryFilePath?.let(::File)?.exists() == true)
        assertTrue(retryDelays.single() >= 60_000L)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun refreshFailurePausesBatchAndRetainsEveryFile() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path?.endsWith("/import/upload") == true ->
                    MockResponse().setResponseCode(401).setBody("""{"error":"expired"}""")
                request.path?.endsWith("/api/auth/refresh") == true ->
                    MockResponse().setResponseCode(401).setBody("""{"error":"refresh expired"}""")
                else -> MockResponse().setResponseCode(404)
            }
        }
        val batch = "sign-in-required"
        val queued = addItem(batch, "saved.jpg", QueueState.READY_TO_UPLOAD)

        val result = runBatch(batch)

        assertEquals(androidx.work.ListenableWorker.Result.failure(), result)
        assertEquals(QueueState.AUTH_REQUIRED, item(queued.id).state)
        assertTrue(item(queued.id).lastError!!.contains("Sign in again"))
        assertTrue(item(queued.id).temporaryFilePath?.let(::File)?.exists() == true)
        assertEquals("/api/albums/album-1/import/upload", server.takeRequest().path)
        assertEquals("/api/auth/refresh", server.takeRequest().path)
        assertFalse(item(queued.id).temporaryFilePath.isNullOrBlank())
    }

    private suspend fun runBatch(batchId: String): androidx.work.ListenableWorker.Result {
        val worker = TestListenableWorkerBuilder.from(app, TransferWorker::class.java)
            .setInputData(workDataOf(TransferWorker.KEY_BATCH_ID to batchId))
            .build()
        return worker.doWork()
    }

    private fun installCompletedImportDispatcher(uploadResponse: (RecordedRequest) -> MockResponse) {
        val statusRequests = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path?.endsWith("/import/upload") == true -> uploadResponse(request)
                request.path?.endsWith("/import/status") == true -> {
                    if (statusRequests.incrementAndGet() == 1) {
                        MockResponse().setBody("""{"state":"NONE"}""")
                    } else {
                        MockResponse().setBody("""{"state":"COMPLETED","total":1,"imported":1,"failed":0,"failures":[]}""")
                    }
                }
                request.path?.endsWith("/import") == true ->
                    MockResponse().setBody("""{"started":true}""")
                else -> MockResponse().setResponseCode(404)
            }
        }
    }

    private fun addItem(
        batchId: String,
        fileName: String,
        state: QueueState,
        mimeType: String = "image/jpeg",
    ): com.wedfaces.cameraoperator.model.TransferItem {
        val file = File(app.cacheDir, "${UUID.randomUUID()}-$fileName")
        file.writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x01))
        files += file
        val row = com.wedfaces.cameraoperator.data.TransferQueueRepository.newTransfer(
            batchId = batchId,
            albumId = "album-1",
            albumName = "Wedding",
            source = PhotoSource.TEST_GALLERY,
            sourceObjectId = "device:$fileName",
            fileName = fileName,
            mimeType = mimeType,
            sizeBytes = file.length(),
            initialState = state,
        )
        services.queue.insert(row)
        services.queue.update(row.id, state, temporaryFilePath = file.absolutePath)
        return row
    }

    private fun item(id: String) = services.queue.getAll().single { it.id == id }

    private class MemoryTokenStore : TokenStore {
        private var access: String? = null
        private var refresh: String? = null
        override fun accessToken() = access
        override fun refreshToken() = refresh
        override fun save(accessToken: String, refreshToken: String) {
            access = accessToken
            refresh = refreshToken
        }
        override fun clear() {
            access = null
            refresh = null
        }
    }
}
