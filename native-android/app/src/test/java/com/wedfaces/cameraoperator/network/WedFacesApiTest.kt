package com.wedfaces.cameraoperator.network

import android.app.Application
import com.wedfaces.cameraoperator.data.TokenStore
import java.io.File
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.json.JSONObject

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class WedFacesApiTest {
    private val server = MockWebServer()
    private lateinit var tokens: MemoryTokenStore
    private lateinit var api: WedFacesApi

    @Before
    fun setUp() {
        server.start()
        tokens = MemoryTokenStore()
        api = newApi()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun loginUsesDocumentedRouteAndStoresBothTokens() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"accessToken":"access-1","refreshToken":"refresh-1","expiresIn":3600,"user":{"id":"user-1","email":"crew@example.com"}}"""),
        )

        val result = api.login(" crew@example.com ", "operator-password")

        val request = server.takeRequest()
        assertEquals("/api/auth/login", request.path)
        assertTrue(request.body.readUtf8().contains("operator-password"))
        assertEquals("access-1", tokens.access)
        assertEquals("refresh-1", tokens.refresh)
        assertEquals("crew@example.com", result.email)
        assertEquals("user-1", result.userId)
    }

    @Test
    fun albumsUseServerIdAndIgnorePublicIdForRouting() = runBlocking {
        tokens.save("access-token", "refresh-token")
        server.enqueue(
            MockResponse().setBody(
                """{"albums":[{"id":"internal-album-id","publicId":"public-share-id","name":"Wedding"}]}""",
            ),
        )

        val albums = api.listAlbums()

        assertEquals("/api/albums", server.takeRequest().path)
        assertEquals("internal-album-id", albums.single().id)
        assertEquals("Wedding", albums.single().name)
    }

    @Test
    fun uploadSendsMultipartFileAndPersistentUploadId() = runBlocking {
        tokens.save("access-token", "refresh-token")
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"ok":true}"""))
        val image = File.createTempFile("wedfaces-contract-", ".jpg").apply {
            writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x01))
        }
        try {
            api.uploadSelectedPhoto("server-album-id", "stable-upload-uuid", image, "image/jpeg")
        } finally {
            image.delete()
        }

        val request = server.takeRequest()
        assertEquals("/api/albums/server-album-id/import/upload", request.path)
        assertEquals("Bearer access-token", request.getHeader("Authorization"))
        val body = request.body.readUtf8()
        assertTrue(body.contains("name=\"uploadId\""))
        assertTrue(body.contains("stable-upload-uuid"))
        assertTrue(body.contains("name=\"file\"; filename=\"wedfaces-contract-"))
        assertTrue(body.contains("Content-Type: image/jpeg"))
    }

    @Test
    fun importUsesAlbumUploadDirectory() = runBlocking {
        tokens.save("access-token", "refresh-token")
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"started":true}"""))

        api.startAlbumImport("server-album-id")

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/albums/server-album-id/import", request.path)
        val body = request.body.readUtf8()
        assertEquals(".device-uploads/server-album-id", JSONObject(body).optString("path"))
    }

    @Test
    fun importStatusParsesCountersAndFailureMessages() = runBlocking {
        tokens.save("access-token", "refresh-token")
        server.enqueue(
            MockResponse().setBody(
                """{"state":"COMPLETED","total":5,"imported":3,"duplicates":1,"failed":1,"failures":[{"fileName":"bad.jpg","error":"invalid image"}]}""",
            ),
        )

        val status = api.getAlbumImportStatus("server-album-id")

        assertEquals("/api/albums/server-album-id/import/status", server.takeRequest().path)
        assertEquals("COMPLETED", status.state)
        assertEquals(5, status.total)
        assertEquals(3, status.imported)
        assertEquals(1, status.duplicates)
        assertEquals(1, status.failed)
        assertEquals(listOf("bad.jpg — invalid image"), status.failures)
    }

    @Test
    fun importStatusParsesPerFileResultsForNewServers() = runBlocking {
        tokens.save("access-token", "refresh-token")
        server.enqueue(
            MockResponse().setBody(
                """{"state":"COMPLETED","results":[{"uploadId":"upload-1","fileName":"one.jpg","status":"imported"},{"fileName":"two.jpg","status":"failed","error":"invalid image"}]}""",
            ),
        )

        val status = api.getAlbumImportStatus("server-album-id")

        assertEquals(true, status.results?.get(0)?.success)
        assertEquals("upload-1", status.results?.get(0)?.uploadId)
        assertEquals(false, status.results?.get(1)?.success)
        assertEquals("invalid image", status.results?.get(1)?.error)
    }

    @Test
    fun retryAfterSecondsAreExposedFor429Responses() = runBlocking {
        tokens.save("access-token", "refresh-token")
        server.enqueue(
            MockResponse()
                .setResponseCode(429)
                .setHeader("Retry-After", "2")
                .setBody("""{"error":"rate limited"}"""),
        )
        val image = File.createTempFile("wedfaces-retry-after-", ".jpg").apply {
            writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x01))
        }

        try {
            val error = runCatching {
                api.uploadSelectedPhoto("album-1", "upload-id", image, "image/jpeg")
            }.exceptionOrNull() as WedFacesApiException

            assertEquals(429, error.statusCode)
            assertEquals(2_000L, error.retryAfterMillis)
            assertTrue(error.retryable)
        } finally {
            image.delete()
        }
    }

    @Test
    fun unauthorizedRequestRefreshesThenRetriesWithNewAccessToken() = runBlocking {
        tokens.save("expired-access", "refresh-token")
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"expired"}"""))
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"accessToken":"renewed-access","refreshToken":"renewed-refresh"}"""),
        )
        server.enqueue(MockResponse().setBody("""{"albums":[{"id":"album-1","name":"Wedding"}]}"""))

        assertEquals("album-1", api.listAlbums().single().id)

        val first = server.takeRequest()
        val refresh = server.takeRequest()
        val retry = server.takeRequest()
        assertEquals("Bearer expired-access", first.getHeader("Authorization"))
        assertEquals("/api/auth/refresh", refresh.path)
        assertTrue(refresh.body.readUtf8().contains("refresh-token"))
        assertEquals("Bearer renewed-access", retry.getHeader("Authorization"))
        assertEquals("renewed-refresh", tokens.refresh)
    }

    @Test
    fun rawFileIsRejectedBeforeAnyRequestIsSent() = runBlocking {
        tokens.save("access-token", "refresh-token")
        val raw = File.createTempFile("wedfaces-contract-", ".arw").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        try {
            val failure = runCatching {
                api.uploadSelectedPhoto("album-1", "upload-id", raw, "image/x-raw")
            }.exceptionOrNull()
            assertTrue(failure is WedFacesApiException)
            assertEquals(415, (failure as WedFacesApiException).statusCode)
            assertFalse(server.requestCount > 0)
        } finally {
            raw.delete()
        }
    }

    @Test
    fun uploadLimitErrorIsShownAsServerFileLimit() = runBlocking {
        tokens.save("access-token", "refresh-token")
        server.enqueue(MockResponse().setResponseCode(413).setBody("""{"error":"too large"}"""))
        val image = File.createTempFile("wedfaces-contract-", ".png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        try {
            val failure = runCatching {
                api.uploadSelectedPhoto("album-1", "upload-id", image, "image/png")
            }.exceptionOrNull()
            assertTrue(failure is WedFacesApiException)
            assertTrue(failure!!.message!!.contains("exceeds the configured upload size limit"))
        } finally {
            image.delete()
        }
    }

    private fun newApi() = WedFacesApi(
        baseUrl = { server.url("/").toString() },
        allowLocalHttp = { true },
        tokenStore = tokens,
    )

    private class MemoryTokenStore : TokenStore {
        var access: String? = null
        var refresh: String? = null
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
