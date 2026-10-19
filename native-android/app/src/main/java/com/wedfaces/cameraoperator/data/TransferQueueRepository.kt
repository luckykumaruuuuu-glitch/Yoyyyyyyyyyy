package com.wedfaces.cameraoperator.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.wedfaces.cameraoperator.model.CameraTransportType
import com.wedfaces.cameraoperator.model.ImportStatus
import com.wedfaces.cameraoperator.model.PhotoSource
import com.wedfaces.cameraoperator.model.QueueState
import com.wedfaces.cameraoperator.model.TransferItem
import java.util.UUID
import org.json.JSONArray

class TransferQueueRepository(
    context: Context,
    databaseName: String = DATABASE_NAME,
) : SQLiteOpenHelper(context.applicationContext, databaseName, null, DATABASE_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE transfers (
              id TEXT PRIMARY KEY NOT NULL,
              upload_id TEXT NOT NULL UNIQUE,
              batch_id TEXT NOT NULL,
              album_id TEXT NOT NULL,
              album_name TEXT NOT NULL,
              source TEXT NOT NULL,
              source_object_id TEXT NOT NULL,
              file_name TEXT NOT NULL,
              mime_type TEXT NOT NULL,
              size_bytes INTEGER,
              source_captured_at INTEGER,
              temporary_file_path TEXT,
              state TEXT NOT NULL,
              progress_bytes INTEGER NOT NULL,
              attempts INTEGER NOT NULL,
              created_at INTEGER NOT NULL,
              updated_at INTEGER NOT NULL,
              last_error TEXT,
              import_started INTEGER NOT NULL DEFAULT 0,
              transport_type TEXT NOT NULL DEFAULT 'NONE'
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX transfers_batch_idx ON transfers(batch_id)")
        db.execSQL("CREATE INDEX transfers_updated_idx ON transfers(updated_at DESC)")
        createImportStatusTable(db)
        createSentCameraObjectsTable(db)
        createTransferBatchesTable(db)
        createFolderWatchSeenTable(db)
        createAutoUploadTables(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            createImportStatusTable(db, includeResults = false)
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE $STATUS_TABLE ADD COLUMN results_json TEXT")
        }
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE $TABLE ADD COLUMN source_captured_at INTEGER")
            createSentCameraObjectsTable(db)
        }
        if (oldVersion < 5) {
            createTransferBatchesTable(db)
        }
        if (oldVersion < 6) {
            createFolderWatchSeenTable(db)
        }
        if (oldVersion < 7) {
            db.execSQL("ALTER TABLE $TABLE ADD COLUMN transport_type TEXT NOT NULL DEFAULT 'NONE'")
            db.execSQL("UPDATE $TABLE SET transport_type = 'WIFI' WHERE source = 'CAMERA'")
        }
        if (oldVersion < 8) {
            createAutoUploadTables(db)
        }
    }

    @Synchronized
    fun saveImportStatus(batchId: String, status: ImportStatus) {
        val values = ContentValues().apply {
            put("batch_id", batchId)
            put("state", status.state)
            putNullable("total", status.total)
            putNullable("imported", status.imported)
            putNullable("duplicates", status.duplicates)
            putNullable("failed", status.failed)
            put("failures_json", JSONArray(status.failures).toString())
            put("results_json", status.results?.let { results ->
                JSONArray().apply {
                    results.forEach { result ->
                        put(
                            org.json.JSONObject()
                                .put("uploadId", result.uploadId)
                                .put("fileName", result.fileName)
                                .put("success", result.success)
                                .put("error", result.error),
                        )
                    }
                }.toString()
            })
            put("error", status.error)
            put("updated_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict(
            STATUS_TABLE,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    @Synchronized
    fun getImportStatus(batchId: String): ImportStatus? =
        readableDatabase.query(
            STATUS_TABLE,
            null,
            "batch_id = ?",
            arrayOf(batchId),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val rawFailures = cursor.getString(cursor.getColumnIndexOrThrow("failures_json"))
            val failures = try {
                val json = JSONArray(rawFailures)
                (0 until json.length()).mapNotNull { json.optString(it).takeIf(String::isNotBlank) }
            } catch (_: Exception) {
                emptyList()
            }
            val resultsIndex = cursor.getColumnIndex("results_json")
            val results = if (resultsIndex < 0 || cursor.isNull(resultsIndex)) {
                null
            } else {
                try {
                    val json = JSONArray(cursor.getString(resultsIndex))
                    (0 until json.length()).mapNotNull { index ->
                        json.optJSONObject(index)?.let { item ->
                            com.wedfaces.cameraoperator.model.ImportFileResult(
                                uploadId = item.optString("uploadId").takeIf(String::isNotBlank),
                                fileName = item.optString("fileName").takeIf(String::isNotBlank),
                                success = if (item.has("success") && !item.isNull("success")) item.optBoolean("success") else null,
                                error = item.optString("error").takeIf(String::isNotBlank),
                            )
                        }
                    }
                } catch (_: Exception) {
                    null
                }
            }
            ImportStatus(
                state = cursor.getString(cursor.getColumnIndexOrThrow("state")),
                total = cursor.nullableInt("total"),
                imported = cursor.nullableInt("imported"),
                duplicates = cursor.nullableInt("duplicates"),
                failed = cursor.nullableInt("failed"),
                failures = failures,
                error = cursor.getString(cursor.getColumnIndexOrThrow("error")),
                results = results,
            )
        }

    @Synchronized
    fun deleteImportStatus(batchId: String) {
        writableDatabase.delete(STATUS_TABLE, "batch_id = ?", arrayOf(batchId))
    }

    @Synchronized
    fun isCameraObjectSent(objectName: String, sizeBytes: Long?, capturedAtMillis: Long?): Boolean =
        readableDatabase.query(
            SENT_TABLE,
            arrayOf("identity_key"),
            "identity_key = ?",
            arrayOf(cameraIdentityKey(objectName, sizeBytes, capturedAtMillis)),
            null,
            null,
            null,
            "1",
        ).use { it.moveToFirst() }

    @Synchronized
    fun markCameraObjectSent(objectName: String, sizeBytes: Long?, capturedAtMillis: Long?) {
        val values = ContentValues().apply {
            put("identity_key", cameraIdentityKey(objectName, sizeBytes, capturedAtMillis))
            put("object_name", objectName)
            if (sizeBytes == null) putNull("size_bytes") else put("size_bytes", sizeBytes)
            if (capturedAtMillis == null) putNull("captured_at") else put("captured_at", capturedAtMillis)
            put("sent_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict(SENT_TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized
    fun setAutoUploadBaseline(cameraIdentity: String, objectIdentities: Collection<String>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.insertWithOnConflict(
                AUTO_SESSION_TABLE,
                null,
                ContentValues().apply {
                    put("camera_identity", cameraIdentity)
                    put("baseline_at", System.currentTimeMillis())
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
            db.delete(AUTO_BASELINE_TABLE, "camera_identity = ?", arrayOf(cameraIdentity))
            objectIdentities.forEach { identity ->
                db.insertWithOnConflict(
                    AUTO_BASELINE_TABLE,
                    null,
                    ContentValues().apply {
                        put("camera_identity", cameraIdentity)
                        put("object_identity", identity)
                    },
                    SQLiteDatabase.CONFLICT_IGNORE,
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun hasAutoUploadBaseline(cameraIdentity: String): Boolean =
        readableDatabase.query(
            AUTO_SESSION_TABLE,
            arrayOf("camera_identity"),
            "camera_identity = ?",
            arrayOf(cameraIdentity),
            null,
            null,
            null,
            "1",
        ).use { it.moveToFirst() }

    @Synchronized
    fun isInAutoUploadBaseline(cameraIdentity: String, objectIdentity: String): Boolean =
        readableDatabase.query(
            AUTO_BASELINE_TABLE,
            arrayOf("object_identity"),
            "camera_identity = ? AND object_identity = ?",
            arrayOf(cameraIdentity, objectIdentity),
            null,
            null,
            null,
            "1",
        ).use { it.moveToFirst() }

    @Synchronized
    fun insertAutoUploadIfNew(
        item: TransferItem,
        cameraIdentity: String,
        objectIdentity: String,
    ): Boolean {
        require(item.source == PhotoSource.CAMERA && item.transportType == CameraTransportType.USB)
        val db = writableDatabase
        db.beginTransaction()
        try {
            val inserted = db.insertWithOnConflict(
                AUTO_SEEN_TABLE,
                null,
                ContentValues().apply {
                    put("identity_key", "$cameraIdentity\u0000$objectIdentity")
                    put("camera_identity", cameraIdentity)
                    put("object_identity", objectIdentity)
                    put("created_at", System.currentTimeMillis())
                },
                SQLiteDatabase.CONFLICT_IGNORE,
            )
            if (inserted == -1L) return false
            createBatchIfMissing(item.batchId, item.albumId, downloadComplete = false)
            db.insertOrThrow(TABLE, null, item.toContentValues())
            db.setTransactionSuccessful()
            return true
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun insert(item: TransferItem) {
        createBatchIfMissing(item.batchId, item.albumId, item.source != PhotoSource.CAMERA)
        writableDatabase.insertOrThrow(TABLE, null, item.toContentValues())
    }

    @Synchronized
    fun hasFolderWatchSeen(sourceUri: String): Boolean =
        readableDatabase.query(
            FOLDER_WATCH_SEEN_TABLE,
            arrayOf("source_uri"),
            "source_uri = ?",
            arrayOf(sourceUri),
            null,
            null,
            null,
            "1",
        ).use { it.moveToFirst() }

    @Synchronized
    fun insertFolderWatchIfNew(item: TransferItem): Boolean {
        require(item.source == PhotoSource.FOLDER_WATCH)
        val db = writableDatabase
        db.beginTransaction()
        try {
            val seenRow = db.insertWithOnConflict(
                FOLDER_WATCH_SEEN_TABLE,
                null,
                ContentValues().apply {
                    put("source_uri", item.sourceObjectId)
                    put("transfer_id", item.id)
                    put("first_seen_at", System.currentTimeMillis())
                },
                SQLiteDatabase.CONFLICT_IGNORE,
            )
            if (seenRow == -1L) return false
            createBatchIfMissing(item.batchId, item.albumId, downloadComplete = true)
            db.insertOrThrow(TABLE, null, item.toContentValues())
            db.setTransactionSuccessful()
            return true
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun createBatch(batchId: String, albumId: String, downloadComplete: Boolean) {
        createBatchIfMissing(batchId, albumId, downloadComplete)
    }

    @Synchronized
    fun setBatchDownloadComplete(batchId: String, complete: Boolean) {
        writableDatabase.update(
            BATCH_TABLE,
            ContentValues().apply {
                put("download_complete", if (complete) 1 else 0)
                put("updated_at", System.currentTimeMillis())
            },
            "batch_id = ?",
            arrayOf(batchId),
        )
    }

    @Synchronized
    fun isBatchDownloadComplete(batchId: String): Boolean =
        readableDatabase.query(
            BATCH_TABLE,
            arrayOf("download_complete"),
            "batch_id = ?",
            arrayOf(batchId),
            null,
            null,
            null,
            "1",
        ).use { it.moveToFirst() && it.getInt(0) == 1 }

    @Synchronized
    fun getAll(): List<TransferItem> =
        readableDatabase.query(TABLE, null, null, null, null, null, "created_at DESC")
            .use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toTransferItem()) } }

    @Synchronized
    fun getBatch(batchId: String): List<TransferItem> =
        readableDatabase.query(
            TABLE,
            null,
            "batch_id = ?",
            arrayOf(batchId),
            null,
            null,
            "created_at ASC",
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toTransferItem()) } }

    @Synchronized
    fun update(
        id: String,
        state: QueueState,
        progressBytes: Long? = null,
        attempts: Int? = null,
        temporaryFilePath: String? = null,
        lastError: String? = null,
        importStarted: Boolean? = null,
    ) {
        val values = ContentValues().apply {
            put("state", state.name)
            put("updated_at", System.currentTimeMillis())
            if (progressBytes != null) put("progress_bytes", progressBytes)
            if (attempts != null) put("attempts", attempts)
            if (temporaryFilePath != null) put("temporary_file_path", temporaryFilePath)
            put("last_error", lastError)
            if (importStarted != null) put("import_started", if (importStarted) 1 else 0)
        }
        writableDatabase.update(TABLE, values, "id = ?", arrayOf(id))
    }

    @Synchronized
    fun updateBatch(batchId: String, state: QueueState, error: String? = null, importStarted: Boolean? = null) {
        val values = ContentValues().apply {
            put("state", state.name)
            put("updated_at", System.currentTimeMillis())
            put("last_error", error)
            if (importStarted != null) put("import_started", if (importStarted) 1 else 0)
        }
        writableDatabase.update(
            TABLE,
            values,
            "batch_id = ? AND state NOT IN (?, ?)",
            arrayOf(batchId, QueueState.IMPORTED.name, QueueState.CANCELLED.name),
        )
    }

    @Synchronized
    fun pauseBatchForAuthentication(batchId: String, error: String) {
        val values = ContentValues().apply {
            put("state", QueueState.AUTH_REQUIRED.name)
            put("last_error", error)
            put("updated_at", System.currentTimeMillis())
        }
        writableDatabase.update(
            TABLE,
            values,
            "batch_id = ? AND state NOT IN (?, ?, ?)",
            arrayOf(batchId, QueueState.IMPORTED.name, QueueState.CANCELLED.name, QueueState.FAILED.name),
        )
    }

    @Synchronized
    fun pauseBatch(batchId: String) {
        writableDatabase.execSQL(
            "UPDATE transfers SET state = ?, last_error = ?, updated_at = ? WHERE batch_id = ? AND state NOT IN (?, ?, ?)",
            arrayOf(
                QueueState.PAUSED.name,
                "Paused by operator.",
                System.currentTimeMillis(),
                batchId,
                QueueState.IMPORTED.name,
                QueueState.CANCELLED.name,
                QueueState.FAILED.name,
            ),
        )
    }

    @Synchronized
    fun cancelBatchItems(batchId: String) {
        writableDatabase.execSQL(
            "UPDATE transfers SET state = ?, last_error = ?, updated_at = ? WHERE batch_id = ? AND state NOT IN (?, ?)",
            arrayOf(
                QueueState.CANCELLED.name,
                "Cancelled locally; server-side staged files, if any, are unchanged.",
                System.currentTimeMillis(),
                batchId,
                QueueState.IMPORTED.name,
                QueueState.CANCELLED.name,
            ),
        )
    }

    @Synchronized
    fun incrementAttempt(id: String) {
        writableDatabase.execSQL(
            "UPDATE transfers SET attempts = attempts + 1, updated_at = ? WHERE id = ?",
            arrayOf(System.currentTimeMillis(), id),
        )
    }

    @Synchronized
    fun deleteImported(id: String) {
        writableDatabase.delete(TABLE, "id = ? AND state = ?", arrayOf(id, QueueState.IMPORTED.name))
    }

    private fun TransferItem.toContentValues() = ContentValues().apply {
        put("id", id)
        put("upload_id", uploadId)
        put("batch_id", batchId)
        put("album_id", albumId)
        put("album_name", albumName)
        put("source", source.name)
        put("transport_type", transportType.name)
        put("source_object_id", sourceObjectId)
        put("file_name", fileName)
        put("mime_type", mimeType)
        if (sizeBytes == null) putNull("size_bytes") else put("size_bytes", sizeBytes)
        if (sourceCapturedAtMillis == null) putNull("source_captured_at") else put("source_captured_at", sourceCapturedAtMillis)
        put("temporary_file_path", temporaryFilePath)
        put("state", state.name)
        put("progress_bytes", progressBytes)
        put("attempts", attempts)
        put("created_at", createdAtMillis)
        put("updated_at", updatedAtMillis)
        put("last_error", lastError)
        put("import_started", if (importStarted) 1 else 0)
    }

    private fun ContentValues.putNullable(key: String, value: Int?) {
        if (value == null) putNull(key) else put(key, value)
    }

    private fun createImportStatusTable(db: SQLiteDatabase, includeResults: Boolean = true) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS import_status (
              batch_id TEXT PRIMARY KEY NOT NULL,
              state TEXT NOT NULL,
              total INTEGER,
              imported INTEGER,
              duplicates INTEGER,
              failed INTEGER,
              failures_json TEXT NOT NULL,
              error TEXT,
              ${if (includeResults) "results_json TEXT," else ""}
              updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
    }

    private fun createSentCameraObjectsTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS sent_camera_objects (
              identity_key TEXT PRIMARY KEY NOT NULL,
              object_name TEXT NOT NULL,
              size_bytes INTEGER,
              captured_at INTEGER,
              sent_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
    }

    private fun createTransferBatchesTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS transfer_batches (
              batch_id TEXT PRIMARY KEY NOT NULL,
              album_id TEXT NOT NULL,
              download_complete INTEGER NOT NULL,
              created_at INTEGER NOT NULL,
              updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
    }

    private fun createFolderWatchSeenTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS folder_watch_seen (
              source_uri TEXT PRIMARY KEY NOT NULL,
              transfer_id TEXT NOT NULL,
              first_seen_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
    }

    private fun createAutoUploadTables(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS camera_auto_sessions (
              camera_identity TEXT PRIMARY KEY NOT NULL,
              baseline_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS camera_auto_baseline_objects (
              camera_identity TEXT NOT NULL,
              object_identity TEXT NOT NULL,
              PRIMARY KEY(camera_identity, object_identity)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS camera_auto_seen (
              identity_key TEXT PRIMARY KEY NOT NULL,
              camera_identity TEXT NOT NULL,
              object_identity TEXT NOT NULL,
              created_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
    }

    private fun createBatchIfMissing(batchId: String, albumId: String, downloadComplete: Boolean) {
        val values = ContentValues().apply {
            put("batch_id", batchId)
            put("album_id", albumId)
            put("download_complete", if (downloadComplete) 1 else 0)
            put("created_at", System.currentTimeMillis())
            put("updated_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict(BATCH_TABLE, null, values, SQLiteDatabase.CONFLICT_IGNORE)
    }

    private fun cameraIdentityKey(objectName: String, sizeBytes: Long?, capturedAtMillis: Long?) =
        "${objectName.trim().lowercase(java.util.Locale.ROOT)}\u0000${sizeBytes ?: -1L}\u0000${capturedAtMillis ?: -1L}"

    private fun android.database.Cursor.toTransferItem(): TransferItem {
        fun string(column: String) = getString(getColumnIndexOrThrow(column))
        fun nullableString(column: String): String? {
            val index = getColumnIndexOrThrow(column)
            return if (isNull(index)) null else getString(index)
        }
        fun nullableLong(column: String): Long? {
            val index = getColumnIndexOrThrow(column)
            return if (isNull(index)) null else getLong(index)
        }
        return TransferItem(
            id = string("id"),
            uploadId = string("upload_id"),
            batchId = string("batch_id"),
            albumId = string("album_id"),
            albumName = string("album_name"),
            source = PhotoSource.valueOf(string("source")),
            sourceObjectId = string("source_object_id"),
            fileName = string("file_name"),
            mimeType = string("mime_type"),
            sizeBytes = nullableLong("size_bytes"),
            sourceCapturedAtMillis = nullableLong("source_captured_at"),
            temporaryFilePath = nullableString("temporary_file_path"),
            state = QueueState.valueOf(string("state")),
            progressBytes = getLong(getColumnIndexOrThrow("progress_bytes")),
            attempts = getInt(getColumnIndexOrThrow("attempts")),
            createdAtMillis = getLong(getColumnIndexOrThrow("created_at")),
            updatedAtMillis = getLong(getColumnIndexOrThrow("updated_at")),
            lastError = nullableString("last_error"),
            importStarted = getInt(getColumnIndexOrThrow("import_started")) == 1,
            transportType = runCatching {
                CameraTransportType.valueOf(string("transport_type"))
            }.getOrDefault(CameraTransportType.NONE),
        )
    }

    private fun android.database.Cursor.nullableInt(column: String): Int? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getInt(index)
    }

    companion object {
        private const val DATABASE_NAME = "camera_operator_queue.db"
        private const val DATABASE_VERSION = 8
        private const val TABLE = "transfers"
        private const val STATUS_TABLE = "import_status"
        private const val SENT_TABLE = "sent_camera_objects"
        private const val BATCH_TABLE = "transfer_batches"
        private const val FOLDER_WATCH_SEEN_TABLE = "folder_watch_seen"
        private const val AUTO_SESSION_TABLE = "camera_auto_sessions"
        private const val AUTO_BASELINE_TABLE = "camera_auto_baseline_objects"
        private const val AUTO_SEEN_TABLE = "camera_auto_seen"

        fun newTransfer(
            batchId: String,
            albumId: String,
            albumName: String,
            source: PhotoSource,
            sourceObjectId: String,
            fileName: String,
            mimeType: String,
            sizeBytes: Long?,
            sourceCapturedAtMillis: Long? = null,
            initialState: QueueState,
            transportType: CameraTransportType = if (source == PhotoSource.CAMERA) {
                CameraTransportType.WIFI
            } else {
                CameraTransportType.NONE
            },
        ) = TransferItem(
            id = UUID.randomUUID().toString(),
            uploadId = UUID.randomUUID().toString(),
            batchId = batchId,
            albumId = albumId,
            albumName = albumName,
            source = source,
            sourceObjectId = sourceObjectId,
            fileName = fileName,
            mimeType = mimeType,
            sizeBytes = sizeBytes,
            sourceCapturedAtMillis = sourceCapturedAtMillis,
            temporaryFilePath = null,
            state = initialState,
            progressBytes = 0,
            attempts = 0,
            createdAtMillis = System.currentTimeMillis(),
            updatedAtMillis = System.currentTimeMillis(),
            lastError = null,
            importStarted = false,
            transportType = transportType,
        )
    }
}
