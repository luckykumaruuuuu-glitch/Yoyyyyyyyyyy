package com.wedfaces.cameraoperator.data

import android.content.Context

data class SettingsSnapshot(
    val serverUrl: String,
    val email: String,
    val selectedAlbumId: String?,
    val selectedAlbumName: String?,
    val testMode: Boolean,
    val allowLocalHttp: Boolean,
    val keepScreenOn: Boolean,
    val cameraPhotoLimit: String,
    val onlyNewSinceLastSend: Boolean,
    val folderWatchUri: String?,
    val folderWatchEnabled: Boolean,
    val includeExistingFolderFiles: Boolean,
    val folderWatchActivatedAtMillis: Long,
    val folderWatchMessage: String?,
    val autoUploadEnabled: Boolean,
)

class SettingsRepository(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences("app_settings", Context.MODE_PRIVATE)

    fun snapshot() = SettingsSnapshot(
        serverUrl = preferences.getString(KEY_SERVER_URL, "") ?: "",
        email = preferences.getString(KEY_EMAIL, "") ?: "",
        selectedAlbumId = preferences.getString(KEY_ALBUM_ID, null),
        selectedAlbumName = preferences.getString(KEY_ALBUM_NAME, null),
        testMode = preferences.getBoolean(KEY_TEST_MODE, false),
        allowLocalHttp = preferences.getBoolean(KEY_ALLOW_LOCAL_HTTP, false),
        keepScreenOn = preferences.getBoolean(KEY_KEEP_SCREEN_ON, false),
        cameraPhotoLimit = preferences.getString(KEY_CAMERA_PHOTO_LIMIT, "50") ?: "50",
        onlyNewSinceLastSend = preferences.getBoolean(KEY_ONLY_NEW_SINCE_LAST_SEND, false),
        folderWatchUri = preferences.getString(KEY_FOLDER_WATCH_URI, null),
        folderWatchEnabled = preferences.getBoolean(KEY_FOLDER_WATCH_ENABLED, false),
        includeExistingFolderFiles = preferences.getBoolean(KEY_INCLUDE_EXISTING_FOLDER_FILES, false),
        folderWatchActivatedAtMillis = preferences.getLong(KEY_FOLDER_WATCH_ACTIVATED_AT, 0L),
        folderWatchMessage = preferences.getString(KEY_FOLDER_WATCH_MESSAGE, null),
        autoUploadEnabled = preferences.getBoolean(KEY_AUTO_UPLOAD_ENABLED, false),
    )

    fun saveServerUrl(value: String) {
        preferences.edit().putString(KEY_SERVER_URL, value.trim()).apply()
    }

    fun saveEmail(value: String) {
        preferences.edit().putString(KEY_EMAIL, value.trim()).apply()
    }

    fun saveAlbum(albumId: String?, albumName: String?) {
        preferences.edit()
            .putString(KEY_ALBUM_ID, albumId)
            .putString(KEY_ALBUM_NAME, albumName)
            .apply()
    }

    fun setTestMode(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_TEST_MODE, enabled).apply()
    }

    fun setAllowLocalHttp(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_ALLOW_LOCAL_HTTP, enabled).apply()
    }

    fun setKeepScreenOn(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_KEEP_SCREEN_ON, enabled).apply()
    }

    fun setCameraPhotoLimit(value: String) {
        preferences.edit().putString(KEY_CAMERA_PHOTO_LIMIT, value.takeIf { it in setOf("50", "100", "all") } ?: "50").apply()
    }

    fun setOnlyNewSinceLastSend(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_ONLY_NEW_SINCE_LAST_SEND, enabled).apply()
    }

    fun setAutoUploadEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_AUTO_UPLOAD_ENABLED, enabled).apply()
    }

    fun setFolderWatch(uri: String?, enabled: Boolean, includeExisting: Boolean) {
        val now = System.currentTimeMillis()
        preferences.edit()
            .putString(KEY_FOLDER_WATCH_URI, uri)
            .putBoolean(KEY_FOLDER_WATCH_ENABLED, enabled)
            .putBoolean(KEY_INCLUDE_EXISTING_FOLDER_FILES, includeExisting)
            .putLong(KEY_FOLDER_WATCH_ACTIVATED_AT, if (enabled) now else 0L)
            .remove(KEY_FOLDER_WATCH_MESSAGE)
            .apply()
    }

    fun setFolderWatchEnabled(enabled: Boolean) {
        preferences.edit()
            .putBoolean(KEY_FOLDER_WATCH_ENABLED, enabled)
            .putLong(KEY_FOLDER_WATCH_ACTIVATED_AT, if (enabled) System.currentTimeMillis() else 0L)
            .remove(KEY_FOLDER_WATCH_MESSAGE)
            .apply()
    }

    fun setIncludeExistingFolderFiles(enabled: Boolean) {
        preferences.edit()
            .putBoolean(KEY_INCLUDE_EXISTING_FOLDER_FILES, enabled)
            .remove(KEY_FOLDER_WATCH_MESSAGE)
            .apply()
    }

    fun setFolderWatchMessage(message: String?) {
        preferences.edit().putString(KEY_FOLDER_WATCH_MESSAGE, message).apply()
    }

    private companion object {
        const val KEY_SERVER_URL = "server_url"
        const val KEY_EMAIL = "email"
        const val KEY_ALBUM_ID = "album_id"
        const val KEY_ALBUM_NAME = "album_name"
        const val KEY_TEST_MODE = "test_mode"
        const val KEY_ALLOW_LOCAL_HTTP = "allow_local_http"
        const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        const val KEY_CAMERA_PHOTO_LIMIT = "camera_photo_limit"
        const val KEY_ONLY_NEW_SINCE_LAST_SEND = "only_new_since_last_send"
        const val KEY_FOLDER_WATCH_URI = "folder_watch_uri"
        const val KEY_FOLDER_WATCH_ENABLED = "folder_watch_enabled"
        const val KEY_INCLUDE_EXISTING_FOLDER_FILES = "include_existing_folder_files"
        const val KEY_FOLDER_WATCH_ACTIVATED_AT = "folder_watch_activated_at"
        const val KEY_FOLDER_WATCH_MESSAGE = "folder_watch_message"
        const val KEY_AUTO_UPLOAD_ENABLED = "auto_upload_enabled"
    }
}
