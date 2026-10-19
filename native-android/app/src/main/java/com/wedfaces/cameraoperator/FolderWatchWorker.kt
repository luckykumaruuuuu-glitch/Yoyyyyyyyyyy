package com.wedfaces.cameraoperator

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.wedfaces.cameraoperator.data.TransferQueueRepository
import com.wedfaces.cameraoperator.model.PhotoSource
import com.wedfaces.cameraoperator.model.QueueState
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class FolderWatchWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    private val services = (applicationContext as CameraOperatorApp).services

    override suspend fun doWork(): Result {
        val settings = services.settings.snapshot()
        if (!settings.folderWatchEnabled) return Result.success()
        val treeUri = settings.folderWatchUri?.let(Uri::parse)
        if (treeUri == null) {
            services.settings.setFolderWatchMessage("Choose a phone folder again to resume watching.")
            return Result.success()
        }
        if (settings.selectedAlbumId.isNullOrBlank()) {
            services.settings.setFolderWatchMessage("Sign in and select an album before photos can enter the queue.")
            return Result.success()
        }

        return try {
            val candidates = withContext(Dispatchers.IO) {
                listSupportedImages(treeUri)
                    .filter {
                        FolderWatchPolicy.shouldInclude(
                            it.lastModifiedMillis,
                            settings.folderWatchActivatedAtMillis,
                            settings.includeExistingFolderFiles,
                        )
                    }
                    .sortedWith(compareBy<WatchedDocument> { it.lastModifiedMillis }.thenBy { it.name })
            }
            val batchId = UUID.randomUUID().toString()
            var queuedCount = 0
            for (document in candidates) {
                if (isStopped) break
                val identity = FolderWatchPolicy.sourceIdentity(
                    document.uri.toString(),
                    document.lastModifiedMillis,
                    document.sizeBytes,
                )
                if (services.queue.hasFolderWatchSeen(identity)) continue

                val item = TransferQueueRepository.newTransfer(
                    batchId = batchId,
                    albumId = settings.selectedAlbumId,
                    albumName = settings.selectedAlbumName ?: "Selected album",
                    source = PhotoSource.FOLDER_WATCH,
                    sourceObjectId = identity,
                    fileName = safeFileName(document.name),
                    mimeType = FolderWatchPolicy.mimeTypeFor(document.name),
                    sizeBytes = document.sizeBytes,
                    initialState = QueueState.READY_TO_UPLOAD,
                )
                val localCopy = File(
                    applicationContext.filesDir,
                    "transfers/${item.id}_${item.fileName}",
                )
                try {
                    localCopy.parentFile?.mkdirs()
                    val copiedBytes = withContext(Dispatchers.IO) {
                        val input = applicationContext.contentResolver.openInputStream(document.uri)
                            ?: throw IllegalStateException("Android could not open ${document.name}.")
                        input.use { source -> FileOutputStream(localCopy).use { output -> source.copyTo(output) } }
                    }
                    require(copiedBytes > 0L) { "${document.name} is empty." }
                    val persisted = item.copy(
                        temporaryFilePath = localCopy.absolutePath,
                        progressBytes = copiedBytes,
                    )
                    if (services.queue.insertFolderWatchIfNew(persisted)) {
                        queuedCount++
                    } else {
                        localCopy.delete()
                    }
                } catch (error: Exception) {
                    localCopy.delete()
                    services.settings.setFolderWatchMessage(
                        "Could not copy ${document.name}: ${error.message ?: "file access failed."}",
                    )
                }
            }

            val pendingBatches = services.queue.getAll()
                .filter {
                    it.source == PhotoSource.FOLDER_WATCH &&
                        it.state in setOf(
                            QueueState.READY_TO_UPLOAD,
                            QueueState.WAITING_FOR_INTERNET,
                            QueueState.UPLOADED_WAITING_IMPORT,
                        )
                }
                .map { it.batchId }
                .distinct()
            for (pendingBatch in pendingBatches) {
                services.enqueueBatchIfInactive(pendingBatch)
            }
            if (queuedCount > 0) {
                services.settings.setFolderWatchMessage(null)
            } else if (services.settings.snapshot().folderWatchMessage == null) {
                services.settings.setFolderWatchMessage(
                    if (candidates.isEmpty()) "Folder checked; no new supported photos were found."
                    else "Folder checked; the supported photos were already queued.",
                )
            }
            Result.success()
        } catch (_: SecurityException) {
            services.settings.setFolderWatchEnabled(false)
            services.settings.setFolderWatchMessage("Folder permission expired. Choose the folder again to resume.")
            services.cancelFolderWatch()
            Result.success()
        } catch (error: Exception) {
            services.settings.setFolderWatchMessage("Folder scan failed: ${error.message ?: "unknown error."}")
            Result.retry()
        }
    }

    private fun listSupportedImages(treeUri: Uri): List<WatchedDocument> {
        val resolver = applicationContext.contentResolver
        val rootDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, rootDocumentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val result = mutableListOf<WatchedDocument>()
        resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val idColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val sizeColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            val modifiedColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            require(idColumn >= 0 && nameColumn >= 0) { "The selected folder did not return file names." }
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameColumn) ?: continue
                if (!FolderWatchPolicy.isSupportedFileName(name)) continue
                val documentId = cursor.getString(idColumn) ?: continue
                val size = if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) cursor.getLong(sizeColumn) else null
                val modified = if (modifiedColumn >= 0 && !cursor.isNull(modifiedColumn)) cursor.getLong(modifiedColumn) else 0L
                val documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
                result += WatchedDocument(documentUri, name, size, modified)
            }
        }
        return result
    }

    private fun safeFileName(value: String): String {
        val clean = value.replace(Regex("[^A-Za-z0-9._-]"), "_").take(140)
        return clean.takeIf { FolderWatchPolicy.isSupportedFileName(it) }
            ?: "phone-image-${UUID.randomUUID()}.jpg"
    }

    private data class WatchedDocument(
        val uri: Uri,
        val name: String,
        val sizeBytes: Long?,
        val lastModifiedMillis: Long,
    )

}
