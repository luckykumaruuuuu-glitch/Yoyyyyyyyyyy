package com.wedfaces.cameraoperator.data

import android.app.Application
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.wedfaces.cameraoperator.model.ImportStatus
import com.wedfaces.cameraoperator.model.ImportFileResult
import com.wedfaces.cameraoperator.model.PhotoSource
import com.wedfaces.cameraoperator.model.QueueState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class TransferQueueRepositoryTest {
    @Test
    fun serverCountersAndFailedFileNamesSurviveDatabaseReopen() {
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase(DATABASE_NAME)
        var repository = TransferQueueRepository(context)
        val batchId = "batch-persisted-report"
        val item = TransferQueueRepository.newTransfer(
            batchId = batchId,
            albumId = "server-album-id",
            albumName = "Wedding",
            source = PhotoSource.TEST_GALLERY,
            sourceObjectId = "content://image/one",
            fileName = "photo-one.jpg",
            mimeType = "image/jpeg",
            sizeBytes = 1024L,
            initialState = QueueState.UPLOADED_WAITING_IMPORT,
        )
        val expectedStatus = ImportStatus(
            state = "COMPLETED",
            total = 5,
            imported = 1,
            duplicates = 0,
            failed = 4,
            failures = listOf(
                "photo-two.jpg — invalid image",
                "photo-three.jpg — corrupted data",
                "photo-four.jpg — unsupported encoding",
                "photo-five.jpg — import timeout",
            ),
            error = null,
        )

        try {
            repository.insert(item)
            repository.saveImportStatus(batchId, expectedStatus)
            repository.close()

            repository = TransferQueueRepository(context)

            assertEquals(expectedStatus, repository.getImportStatus(batchId))
            assertEquals(item.id, repository.getBatch(batchId).single().id)
            assertEquals(item.fileName, repository.getBatch(batchId).single().fileName)
        } finally {
            repository.close()
            context.deleteDatabase(DATABASE_NAME)
        }
    }

    @Test
    fun folderWatchSourceIsQueuedOnlyOnceAndSeenMarkerSurvivesReopen() {
        val context = RuntimeEnvironment.getApplication()
        val databaseName = "camera_operator_folder_watch_test.db"
        context.deleteDatabase(databaseName)
        var repository = TransferQueueRepository(context, databaseName)
        val identity = "content://phone-folder/photo-1|1720000000000|2048"
        val first = TransferQueueRepository.newTransfer(
            batchId = "folder-batch-one",
            albumId = "album-id",
            albumName = "Wedding",
            source = PhotoSource.FOLDER_WATCH,
            sourceObjectId = identity,
            fileName = "photo.jpg",
            mimeType = "image/jpeg",
            sizeBytes = 2048L,
            initialState = QueueState.READY_TO_UPLOAD,
        )
        try {
            assertTrue(repository.insertFolderWatchIfNew(first))
            repository.close()

            repository = TransferQueueRepository(context, databaseName)
            assertTrue(repository.hasFolderWatchSeen(identity))
            val duplicate = first.copy(
                id = "duplicate-transfer",
                uploadId = "duplicate-upload",
                batchId = "folder-batch-two",
            )
            assertFalse(repository.insertFolderWatchIfNew(duplicate))
            assertEquals(1, repository.getAll().size)
        } finally {
            repository.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun versionOneQueueDatabaseMigratesWithoutLosingRowsAndAddsResultStorage() {
        val context = RuntimeEnvironment.getApplication()
        val databaseName = "camera_operator_queue_legacy_test.db"
        context.deleteDatabase(databaseName)
        val legacy = object : SQLiteOpenHelper(context, databaseName, null, 1) {
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
                      temporary_file_path TEXT,
                      state TEXT NOT NULL,
                      progress_bytes INTEGER NOT NULL,
                      attempts INTEGER NOT NULL,
                      created_at INTEGER NOT NULL,
                      updated_at INTEGER NOT NULL,
                      last_error TEXT,
                      import_started INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent(),
                )
                db.insertOrThrow(
                    "transfers",
                    null,
                    ContentValues().apply {
                        put("id", "legacy-row")
                        put("upload_id", "legacy-upload")
                        put("batch_id", "legacy-batch")
                        put("album_id", "album")
                        put("album_name", "Wedding")
                        put("source", PhotoSource.TEST_GALLERY.name)
                        put("source_object_id", "device:legacy.jpg")
                        put("file_name", "legacy.jpg")
                        put("mime_type", "image/jpeg")
                        put("size_bytes", 100L)
                        put("state", QueueState.UPLOADED_WAITING_IMPORT.name)
                        put("progress_bytes", 100L)
                        put("attempts", 1)
                        put("created_at", 1L)
                        put("updated_at", 1L)
                        put("import_started", 1)
                    },
                )
            }

            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }

        try {
            legacy.writableDatabase
            legacy.close()
            var repository = TransferQueueRepository(context, databaseName)
            try {
                val migratedItem = repository.getBatch("legacy-batch").single()
                assertEquals("legacy-row", migratedItem.id)
                assertEquals("legacy.jpg", migratedItem.fileName)
                repository.saveImportStatus(
                    "legacy-batch",
                    ImportStatus(
                        state = "COMPLETED",
                        total = 1,
                        imported = 1,
                        duplicates = 0,
                        failed = 0,
                        failures = emptyList(),
                        error = null,
                        results = listOf(ImportFileResult("legacy-upload", "legacy.jpg", true, null)),
                    ),
                )
                repository.close()

                repository = TransferQueueRepository(context, databaseName)
                assertEquals(true, repository.getImportStatus("legacy-batch")?.results?.single()?.success)
                assertEquals("legacy-row", repository.getBatch("legacy-batch").single().id)
            } finally {
                repository.close()
            }
        } finally {
            legacy.close()
            context.deleteDatabase(databaseName)
        }
    }

    private companion object {
        const val DATABASE_NAME = "camera_operator_queue.db"
    }
}
