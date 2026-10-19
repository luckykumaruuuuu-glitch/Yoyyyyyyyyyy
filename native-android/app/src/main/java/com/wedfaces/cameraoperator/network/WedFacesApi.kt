package com.wedfaces.cameraoperator.network

import com.wedfaces.cameraoperator.BuildConfig
import com.wedfaces.cameraoperator.data.TokenStore
import com.wedfaces.cameraoperator.model.Album
import com.wedfaces.cameraoperator.model.ImportStatus
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okio.BufferedSink
import okio.buffer
import okio.source
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class LoginResult(
    val email: String?,
    val userId: String?,
)

class WedFacesApiException(
    val statusCode: Int,
    override val message: String,
    val retryable: Boolean = statusCode == 0 || statusCode >= 500 || statusCode == 408 || statusCode == 429,
    val retryAfterMillis: Long? = null,
) : Exception(message)

class WedFacesApi(
    private val baseUrl: () -> String,
    private val allowLocalHttp: () -> Boolean,
    private val tokenStore: TokenStore,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.MINUTES)
        .retryOnConnectionFailure(true)
        .build(),
) {
    suspend fun login(email: String, password: String): LoginResult = withContext(Dispatchers.IO) {
        val url = checkedBaseUrl()
        val request = jsonRequest(
            url,
            listOf("api", "auth", "login"),
            JSONObject().put("email", email.trim()).put("password", password),
        )
        client.newCall(request).execute().use { response ->
            val body = readBody(response)
            ensureSuccess(response, body)
            val json = body.toJsonValue().asJsonObject()
            val access = json.optString("accessToken").takeIf(String::isNotBlank)
                ?: throw WedFacesApiException(response.code, "The server did not return an access token.", false)
            val refresh = json.optString("refreshToken").takeIf(String::isNotBlank)
                ?: throw WedFacesApiException(response.code, "The server did not return a refresh token.", false)
            tokenStore.save(access, refresh)
            val user = json.optJSONObject("user")
            LoginResult(
                email = user?.optString("email")?.takeIf(String::isNotBlank) ?: email.trim(),
                userId = user?.optString("id")?.takeIf(String::isNotBlank),
            )
        }
    }

    suspend fun listAlbums(): List<Album> = withContext(Dispatchers.IO) {
        val response = authenticatedRequest { url ->
            Request.Builder().url(url.path("api", "albums")).get()
        }
        response.use {
            val body = readBody(it)
            ensureSuccess(it, body)
            parseAlbums(body)
        }
    }

    suspend fun uploadSelectedPhoto(
        albumId: String,
        uploadId: String,
        file: File,
        mimeType: String,
        onProgress: (sent: Long, total: Long) -> Unit = { _, _ -> },
    ) = withContext(Dispatchers.IO) {
        require(albumId.isNotBlank()) { "A WED Faces album must be selected before uploading." }
        require(uploadId.isNotBlank()) { "The upload operation ID is missing." }
        if (!file.isFile || file.length() <= 0) {
            throw WedFacesApiException(0, "The selected original is unavailable or empty.", false)
        }
        validateFormat(file.name, mimeType)
        val type = mimeType.toMediaTypeOrNull() ?: "application/octet-stream".toMediaTypeOrNull()
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("uploadId", uploadId)
            .addFormDataPart("file", file.name, ProgressFileBody(file, type, onProgress))
            .build()
        val response = authenticatedRequest { url ->
            Request.Builder().url(url.path("api", "albums", albumId, "import", "upload")).post(body)
        }
        response.use {
            val responseBody = readBody(it)
            ensureSuccess(
                it,
                responseBody,
                if (it.code == 413) {
                    "The server rejected this file because it exceeds the configured upload size limit."
                } else {
                    null
                },
            )
        }
    }

    suspend fun startAlbumImport(albumId: String) = withContext(Dispatchers.IO) {
        require(albumId.isNotBlank()) { "A WED Faces album must be selected before importing." }
        val response = authenticatedRequest { url ->
            Request.Builder()
                .url(url.path("api", "albums", albumId, "import"))
                .post(
                    JSONObject()
                        .put("path", ".device-uploads/$albumId")
                        .toString()
                        .toRequestBody(JSON_MEDIA_TYPE),
                )
        }
        response.use {
            val body = readBody(it)
            ensureSuccess(it, body)
        }
    }

    suspend fun getAlbumImportStatus(albumId: String): ImportStatus = withContext(Dispatchers.IO) {
        require(albumId.isNotBlank()) { "Album ID is required to read import status." }
        val response = authenticatedRequest { url ->
            Request.Builder().url(url.path("api", "albums", albumId, "import", "status")).get()
        }
        response.use {
            val body = readBody(it)
            ensureSuccess(it, body)
            parseImportStatus(body)
        }
    }

    suspend fun clearSession() = withContext(Dispatchers.IO) {
        tokenStore.clear()
    }

    private fun authenticatedRequest(
        requestFactory: (HttpUrl) -> Request.Builder,
    ): Response {
        val accessToken = tokenStore.accessToken()
            ?: throw WedFacesApiException(401, "Please sign in to WED Faces again.", false)
        val url = checkedBaseUrl()
        val first = client.newCall(
            requestFactory(url).header("Accept", "application/json")
                .header("Authorization", "Bearer $accessToken")
                .build(),
        ).execute()
        if (first.code != 401) return first
        first.close()

        val refreshed = refreshSession(url) ?: run {
            tokenStore.clear()
            throw WedFacesApiException(401, "Your WED Faces session expired. Please sign in again.", false)
        }
        return client.newCall(
            requestFactory(url).header("Accept", "application/json")
                .header("Authorization", "Bearer $refreshed")
                .build(),
        ).execute().also { response ->
            if (response.code == 401) {
                response.close()
                tokenStore.clear()
                throw signInRequired()
            }
        }
    }

    private fun refreshSession(url: HttpUrl): String? {
        val refreshToken = tokenStore.refreshToken() ?: return null
        val request = jsonRequest(
            url,
            listOf("api", "auth", "refresh"),
            JSONObject().put("refreshToken", refreshToken),
        )
        return try {
            client.newCall(request).execute().use { response ->
                val body = readBody(response)
                if (!response.isSuccessful) return null
                val json = body.toJsonValue().asJsonObject()
                val newAccess = json.optString("accessToken").takeIf(String::isNotBlank) ?: return null
                val newRefresh = json.optString("refreshToken").takeIf(String::isNotBlank) ?: refreshToken
                tokenStore.save(newAccess, newRefresh)
                newAccess
            }
        } catch (_: IOException) {
            null
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun jsonRequest(url: HttpUrl, segments: List<String>, json: JSONObject): Request =
        Request.Builder()
            .url(url.path(*segments.toTypedArray()))
            .header("Accept", "application/json")
            .post(json.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

    private fun checkedBaseUrl(): HttpUrl {
        val raw = baseUrl().trim().trimEnd('/')
        if (raw.isBlank()) throw WedFacesApiException(0, "Enter the WED Faces server URL in Settings.", false)
        val url = raw.toHttpUrlOrNull()
            ?: throw WedFacesApiException(0, "The WED Faces server URL is not a valid HTTP(S) address.", false)
        if (url.scheme == "https") return url
        if (url.scheme != "http" || !BuildConfig.DEBUG || !allowLocalHttp() || !isLocalDevelopmentHost(url.host)) {
            throw WedFacesApiException(
                0,
                "Use HTTPS for the WED Faces server. HTTP is allowed only for an explicitly enabled local development host in a debug build.",
                false,
            )
        }
        return url
    }

    private fun isLocalDevelopmentHost(host: String): Boolean {
        val normalized = host.lowercase(Locale.ROOT).removePrefix("[").removeSuffix("]")
        if (normalized == "localhost" || normalized == "::1" || normalized.endsWith(".local")) return true
        val octets = normalized.split('.').mapNotNull(String::toIntOrNull)
        if (octets.size != 4 || octets.any { it !in 0..255 }) return false
        return octets[0] == 10 ||
            (octets[0] == 192 && octets[1] == 168) ||
            (octets[0] == 172 && octets[1] in 16..31) ||
            (octets[0] == 127)
    }

    private fun readBody(response: Response): String =
        try {
            response.body?.string().orEmpty()
        } catch (_: IOException) {
            ""
        }

    private fun ensureSuccess(response: Response, body: String, overrideMessage: String? = null) {
        if (response.isSuccessful) return
        val message = when (response.code) {
            401 -> "Your WED Faces session expired. Please sign in again."
            else -> overrideMessage ?: safeMessage(body) ?: when (response.code) {
            403 -> "Your account does not have permission for this WED Faces action."
            413 -> "The server rejected this file because it exceeds the configured upload size limit."
            else -> "WED Faces request failed (HTTP ${response.code})."
            }
        }
        throw WedFacesApiException(
            statusCode = response.code,
            message = message,
            retryAfterMillis = response.header("Retry-After")?.let(::parseRetryAfterMillis),
        )
    }

    private fun parseRetryAfterMillis(value: String): Long? {
        value.trim().toLongOrNull()?.let { seconds ->
            return seconds.coerceAtLeast(0).coerceAtMost(MAX_RETRY_AFTER_SECONDS) * 1_000L
        }
        return try {
            val retryAt = java.time.ZonedDateTime.parse(
                value,
                java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME,
            ).toInstant().toEpochMilli()
            (retryAt - System.currentTimeMillis()).coerceIn(0L, MAX_RETRY_AFTER_MILLIS)
        } catch (_: Exception) {
            null
        }
    }

    private fun signInRequired() = WedFacesApiException(
        statusCode = 401,
        message = "Your WED Faces session expired. Please sign in again.",
        retryable = false,
    )

    private fun safeMessage(body: String): String? {
        if (body.isBlank()) return null
        return try {
            val json = JSONObject(body)
            sequenceOf("message", "error", "detail")
                .mapNotNull { json.optString(it).takeIf(String::isNotBlank) }
                .firstOrNull()
                ?.take(280)
        } catch (_: Exception) {
            null
        }
    }

    private fun parseAlbums(body: String): List<Album> {
        val root = body.toJsonValue()
        val array = findAlbumArray(root)
            ?: throw WedFacesApiException(200, "The WED Faces server returned an unrecognized album list.", false)
        val albums = buildList {
            for (index in 0 until array.length()) {
                val json = array.optJSONObject(index) ?: continue
                // The upload routes require the actual ID. publicId is intentionally never used here.
                val id = json.optString("id").takeIf(String::isNotBlank) ?: continue
                val name = sequenceOf("name", "title", "displayName")
                    .mapNotNull { json.optString(it).takeIf(String::isNotBlank) }
                    .firstOrNull()
                    ?: "Album ${id.take(8)}"
                add(Album(id, name))
            }
        }
        if (albums.isEmpty()) {
            throw WedFacesApiException(200, "No albums with server IDs were found in the response.", false)
        }
        return albums
    }

    private fun findAlbumArray(value: Any?): JSONArray? = when (value) {
        is JSONArray -> value
        is JSONObject -> {
            sequenceOf("albums", "data", "results")
                .mapNotNull { key -> value.opt(key) }
                .firstNotNullOfOrNull(::findAlbumArray)
        }
        else -> null
    }

    private fun parseImportStatus(body: String): ImportStatus {
        val root = body.toJsonValue() as? JSONObject
            ?: throw WedFacesApiException(200, "The WED Faces import status response was not an object.", false)
        val json = root.optJSONObject("data") ?: root.optJSONObject("status") ?: root
        val state = json.optString("state").uppercase(Locale.ROOT)
        if (state.isBlank()) {
            throw WedFacesApiException(200, "The WED Faces import status response did not include a state.", false)
        }
        val failures = when (val raw = json.opt("failures")) {
            is JSONArray -> (0 until raw.length()).mapNotNull { index ->
                raw.optJSONObject(index)?.let { item ->
                        val fileName = sequenceOf("fileName", "filename", "name")
                            .mapNotNull { item.optString(it).takeIf(String::isNotBlank) }
                            .firstOrNull()
                        val message = sequenceOf("error", "message")
                            .mapNotNull { item.optString(it).takeIf(String::isNotBlank) }
                            .firstOrNull()
                        when {
                            fileName != null && message != null -> "$fileName — $message"
                            fileName != null -> fileName
                            else -> message
                        }
                } ?: raw.optString(index).takeIf(String::isNotBlank)
            }
            else -> emptyList()
        }
        val results = json.optJSONArray("results")?.let { array ->
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val uploadId = sequenceOf("uploadId", "upload_id")
                    .mapNotNull { item.optString(it).takeIf(String::isNotBlank) }
                    .firstOrNull()
                val fileName = sequenceOf("fileName", "filename", "name")
                    .mapNotNull { item.optString(it).takeIf(String::isNotBlank) }
                    .firstOrNull()
                val rawState = sequenceOf("state", "status", "result")
                    .mapNotNull { item.optString(it).takeIf(String::isNotBlank) }
                    .firstOrNull()
                    ?.uppercase(Locale.ROOT)
                val error = sequenceOf("error", "message", "detail")
                    .mapNotNull { item.optString(it).takeIf(String::isNotBlank) }
                    .firstOrNull()
                val success = when {
                    item.has("success") && !item.isNull("success") -> item.optBoolean("success")
                    item.has("imported") && !item.isNull("imported") -> item.optBoolean("imported")
                    error != null -> false
                    rawState in SUCCESS_IMPORT_RESULTS -> true
                    rawState in FAILURE_IMPORT_RESULTS -> false
                    else -> null
                }
                com.wedfaces.cameraoperator.model.ImportFileResult(uploadId, fileName, success, error)
            }
        }
        return ImportStatus(
            state = state,
            total = json.optionalInt("total"),
            imported = json.optionalInt("imported"),
            duplicates = json.optionalInt("duplicates"),
            failed = json.optionalInt("failed"),
            failures = failures,
            error = json.optString("error").takeIf(String::isNotBlank),
            results = results,
        )
    }

    private fun String.toJsonValue(): Any =
        try {
            val trimmed = trim()
            if (trimmed.startsWith("[")) JSONArray(trimmed) else JSONObject(trimmed)
        } catch (_: Exception) {
            throw WedFacesApiException(200, "The WED Faces server returned invalid JSON.", false)
        }

    private fun Any?.asJsonObject(): JSONObject = this as? JSONObject
        ?: throw WedFacesApiException(200, "The WED Faces server returned an unexpected response.", false)

    private fun JSONObject.optionalInt(key: String): Int? =
        if (has(key) && !isNull(key)) optInt(key) else null

    private fun HttpUrl.path(vararg segments: String): HttpUrl =
        newBuilder().apply { segments.forEach(::addPathSegment) }.build()

    private fun validateFormat(fileName: String, mimeType: String) {
        val extension = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val supportedMime = SUPPORTED_MIME_TYPES[extension]
        val supported = supportedMime != null &&
            (mimeType.isBlank() || mimeType.lowercase(Locale.ROOT) == supportedMime)
        if (!supported) {
            throw WedFacesApiException(
                415,
                "WED Faces imports JPG/JPEG, PNG, and WEBP images only. This file format is not supported.",
                false,
            )
        }
    }

    private class ProgressFileBody(
        private val file: File,
        private val mediaType: MediaType?,
        private val onProgress: (Long, Long) -> Unit,
    ) : RequestBody() {
        override fun contentType(): MediaType? = mediaType

        override fun contentLength(): Long = file.length()

        override fun writeTo(sink: BufferedSink) {
            val total = contentLength()
            var written = 0L
            file.source().buffer().use { source ->
                while (true) {
                    val count = source.read(sink.buffer, 64 * 1024L)
                    if (count == -1L) break
                    written += count
                    sink.emit()
                    onProgress(written, total)
                }
            }
        }
    }

    private companion object {
        const val MAX_RETRY_AFTER_SECONDS = 6 * 60 * 60L
        const val MAX_RETRY_AFTER_MILLIS = MAX_RETRY_AFTER_SECONDS * 1_000L
        val SUCCESS_IMPORT_RESULTS = setOf("IMPORTED", "SUCCESS", "SUCCEEDED", "COMPLETED", "DONE", "DUPLICATE")
        val FAILURE_IMPORT_RESULTS = setOf("FAILED", "ERROR", "REJECTED", "INVALID")
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaTypeOrNull()
        val SUPPORTED_MIME_TYPES = mapOf(
            "jpg" to "image/jpeg",
            "jpeg" to "image/jpeg",
            "png" to "image/png",
            "webp" to "image/webp",
        )
    }
}

private fun String.toRequestBody(mediaType: MediaType?) =
    RequestBody.create(mediaType, this)
