package com.wedfaces.cameraoperator

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.wedfaces.cameraoperator.model.ImportStatus
import com.wedfaces.cameraoperator.model.QueueState
import com.wedfaces.cameraoperator.model.TransferItem
import com.wedfaces.cameraoperator.network.WedFacesApiException
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

class TransferWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    private val services = (applicationContext as CameraOperatorApp).services

    override suspend fun doWork(): Result {
        val batchId = inputData.getString(KEY_BATCH_ID) ?: return Result.failure()
        val initialItems = services.queue.getBatch(batchId)
        if (initialItems.isEmpty()) return Result.success()

        val alreadyComplete = initialItems.count {
            it.state == QueueState.UPLOADED_WAITING_IMPORT ||
                it.state == QueueState.IMPORTED ||
                it.state == QueueState.FAILED
        }
        setForeground(TransferNotifications.workerForegroundInfo(applicationContext, batchId, alreadyComplete, initialItems.size))
        val semaphore = Semaphore(MAX_PARALLEL_UPLOADS)
        val completedCount = AtomicInteger(alreadyComplete)
        val notificationLock = Mutex()
        val outcomes = try {
            coroutineScope {
                initialItems
                    .filter { it.state == QueueState.READY_TO_UPLOAD || it.state == QueueState.WAITING_FOR_INTERNET }
                    .map { item ->
                        async {
                            semaphore.withPermit {
                                val outcome = if (isStopped) UploadOutcome() else uploadOne(item)
                                val completed = completedCount.incrementAndGet()
                                notificationLock.withLock {
                                    setForeground(
                                        TransferNotifications.workerForegroundInfo(
                                            applicationContext,
                                            batchId,
                                            completed.coerceAtMost(initialItems.size),
                                            initialItems.size,
                                        ),
                                    )
                                }
                                outcome
                            }
                        }
                    }
                    .awaitAll()
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            services.queue.getBatch(batchId)
                .filter { it.state == QueueState.UPLOADING }
                .forEach {
                    services.queue.update(
                        it.id,
                        QueueState.FAILED,
                        lastError = error.message ?: "Transfer failed unexpectedly.",
                    )
                }
            return Result.failure()
        }

        if (outcomes.any { it.authenticationRequired }) {
            services.queue.pauseBatchForAuthentication(
                batchId,
                "Sign in again to WED Faces. Files remain saved on this device.",
            )
            return Result.failure()
        }

        val retryDelay = outcomes.maxOfOrNull { it.retryDelayMillis } ?: 0L
        if (retryDelay > 0L) {
            services.scheduleBatchRetry(batchId, retryDelay)
            return Result.success()
        }

        var uploaded = services.queue.getBatch(batchId)
            .filter { it.state == QueueState.UPLOADED_WAITING_IMPORT }
        if (uploaded.isEmpty()) return Result.success()

        try {
            val albumId = uploaded.first().albumId
            return AlbumImportCoordinator.withAlbumLock(albumId) {
                var status = services.api.getAlbumImportStatus(albumId)
                services.queue.saveImportStatus(batchId, status)

                val importAlreadyStarted = uploaded.any { it.importStarted }
                if (!importAlreadyStarted) {
                    val downloadComplete = services.queue.isBatchDownloadComplete(batchId)
                    val idleMillis = System.currentTimeMillis() -
                        (uploaded.maxOfOrNull { it.updatedAtMillis } ?: System.currentTimeMillis())
                    val importDue = downloadComplete ||
                        uploaded.size >= IMPORT_FILE_THRESHOLD ||
                        idleMillis >= IMPORT_IDLE_MILLIS
                    if (!importDue && status.state != "RUNNING") {
                        scheduleImportRetry(batchId, (IMPORT_IDLE_MILLIS - idleMillis).coerceAtLeast(1_000L))
                        return@withAlbumLock Result.success()
                    }
                    if (status.state == "RUNNING") {
                        status = waitForImportStatus(batchId, albumId, status)
                        if (status.state == "RUNNING") {
                            scheduleImportRetry(batchId)
                            return@withAlbumLock Result.success()
                        }
                    }
                    if (status.state != "RUNNING") {
                        services.api.startAlbumImport(albumId)
                        uploaded.forEach {
                            services.queue.update(
                                it.id,
                                QueueState.UPLOADED_WAITING_IMPORT,
                                importStarted = true,
                            )
                        }
                        // Let the server publish this run's status rather than reusing an older COMPLETED response.
                        delay(1_500)
                        status = services.api.getAlbumImportStatus(albumId)
                        services.queue.saveImportStatus(batchId, status)
                    }
                }

                if (status.state == "COMPLETED" || status.state == "FAILED") {
                    finishImport(batchId, status)
                    return@withAlbumLock Result.success()
                }
                if (status.state == "NONE") {
                    delay(2_000)
                    status = services.api.getAlbumImportStatus(albumId)
                    services.queue.saveImportStatus(batchId, status)
                    if (status.state == "NONE") {
                        scheduleImportRetry(batchId)
                        return@withAlbumLock Result.success()
                    }
                    if (status.state == "COMPLETED" || status.state == "FAILED") {
                        finishImport(batchId, status)
                        return@withAlbumLock Result.success()
                    }
                }

                status = waitForImportStatus(batchId, albumId, status)
                if (status.state == "COMPLETED" || status.state == "FAILED") {
                    finishImport(batchId, status)
                    return@withAlbumLock Result.success()
                }
                scheduleImportRetry(batchId)
                Result.success()
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: WedFacesApiException) {
            if (error.statusCode == 401) {
                services.queue.pauseBatchForAuthentication(
                    batchId,
                    "Sign in again to WED Faces. Files remain saved on this device.",
                )
                return Result.failure()
            }
            if (error.retryable) {
                services.scheduleBatchRetry(
                    batchId,
                    maxOf(error.retryAfterMillis ?: 0L, exponentialBackoffMillis(runAttemptCount + 1)),
                )
                return Result.success()
            }
            markImportReview(batchId, error.message)
            return Result.failure()
        } catch (error: IOException) {
            services.scheduleBatchRetry(batchId, exponentialBackoffMillis(runAttemptCount + 1))
            return Result.success()
        } catch (error: Exception) {
            markImportReview(batchId, error.message ?: "Could not confirm the server import result.")
            return Result.failure()
        }
    }

    private suspend fun uploadOne(item: TransferItem): UploadOutcome {
        val current = services.queue.getBatch(item.batchId).firstOrNull { it.id == item.id } ?: return UploadOutcome()
        if (current.state != QueueState.READY_TO_UPLOAD && current.state != QueueState.WAITING_FOR_INTERNET) {
            return UploadOutcome()
        }
        val localFile = current.temporaryFilePath?.let(::File)
        if (localFile == null || !localFile.isFile || localFile.length() == 0L) {
            services.queue.update(
                current.id,
                QueueState.FAILED,
                lastError = "The local original is missing. Reconnect the camera or reselect this image.",
            )
            return UploadOutcome()
        }

        val attempt = current.attempts + 1
        if (attempt > MAX_ATTEMPTS_PER_ITEM) {
            services.queue.update(
                current.id,
                QueueState.FAILED,
                lastError = current.lastError ?: "Upload stopped after $MAX_ATTEMPTS_PER_ITEM attempts.",
            )
            return UploadOutcome()
        }
        services.queue.update(
            current.id,
            QueueState.UPLOADING,
            progressBytes = 0,
            attempts = attempt,
        )

        return try {
            services.api.uploadSelectedPhoto(
                albumId = current.albumId,
                uploadId = current.uploadId,
                file = localFile,
                mimeType = current.mimeType,
            ) { sent, _ ->
                services.queue.update(current.id, QueueState.UPLOADING, progressBytes = sent)
            }
            services.queue.update(
                current.id,
                QueueState.UPLOADED_WAITING_IMPORT,
                progressBytes = localFile.length(),
            )
            UploadOutcome()
        } catch (error: CancellationException) {
            throw error
        } catch (error: WedFacesApiException) {
            if (error.statusCode == 401) return UploadOutcome(authenticationRequired = true)
            if (error.retryable && attempt < MAX_ATTEMPTS_PER_ITEM) {
                services.queue.update(
                    current.id,
                    QueueState.WAITING_FOR_INTERNET,
                    lastError = error.message,
                )
                UploadOutcome(
                    retryDelayMillis = maxOf(
                        exponentialBackoffMillis(attempt),
                        error.retryAfterMillis ?: 0L,
                    ),
                )
            } else {
                services.queue.update(
                    current.id,
                    QueueState.FAILED,
                    lastError = if (error.retryable) {
                        "Upload failed after $MAX_ATTEMPTS_PER_ITEM attempts: ${error.message}"
                    } else {
                        error.message
                    },
                )
                UploadOutcome()
            }
        } catch (error: IOException) {
            if (attempt < MAX_ATTEMPTS_PER_ITEM) {
                val message = error.message ?: "Network request failed."
                services.queue.update(current.id, QueueState.WAITING_FOR_INTERNET, lastError = message)
                UploadOutcome(retryDelayMillis = exponentialBackoffMillis(attempt))
            } else {
                services.queue.update(
                    current.id,
                    QueueState.FAILED,
                    lastError = "Upload failed after $MAX_ATTEMPTS_PER_ITEM attempts: ${error.message ?: "Network request failed."}",
                )
                UploadOutcome()
            }
        } catch (error: Exception) {
            services.queue.update(
                current.id,
                QueueState.FAILED,
                lastError = error.message ?: "Transfer failed.",
            )
            UploadOutcome()
        }
    }

    private suspend fun waitForImportStatus(
        batchId: String,
        albumId: String,
        initial: ImportStatus,
    ): ImportStatus {
        var status = initial
        repeat(24) {
            if (isStopped || status.state != "RUNNING") return status
            delay(5_000)
            status = services.api.getAlbumImportStatus(albumId)
            services.queue.saveImportStatus(batchId, status)
        }
        return status
    }

    private fun finishImport(batchId: String, status: ImportStatus) {
        services.queue.saveImportStatus(batchId, status)
        ImportStatusReconciler.reconcile(services.queue.getBatch(batchId), status).forEach { result ->
            services.queue.update(
                result.item.id,
                result.state,
                lastError = result.error,
                importStarted = result.state == QueueState.IMPORTED,
            )
            if (result.state == QueueState.IMPORTED) {
                result.item.temporaryFilePath?.let(::File)?.delete()
                if (result.item.source == com.wedfaces.cameraoperator.model.PhotoSource.CAMERA) {
                    services.queue.markCameraObjectSent(
                        result.item.fileName,
                        result.item.sizeBytes,
                        result.item.sourceCapturedAtMillis,
                    )
                }
            }
        }
    }

    private fun markImportReview(batchId: String, message: String) {
        services.queue.getBatch(batchId)
            .filter { it.state == QueueState.UPLOADED_WAITING_IMPORT }
            .forEach { services.queue.update(it.id, QueueState.IMPORT_REVIEW, lastError = message) }
    }

    private fun scheduleImportRetry(batchId: String, delayMillis: Long = exponentialBackoffMillis(runAttemptCount + 1)) {
        services.scheduleBatchRetry(batchId, delayMillis)
    }

    private fun exponentialBackoffMillis(attempt: Int): Long {
        val multiplier = 1L shl (attempt.coerceIn(1, 10) - 1)
        return TimeUnit.SECONDS.toMillis(30L * multiplier).coerceAtMost(MAX_BACKOFF_MILLIS)
    }

    private data class UploadOutcome(
        val authenticationRequired: Boolean = false,
        val retryDelayMillis: Long = 0L,
    )

    companion object {
        const val KEY_BATCH_ID = "batch_id"
        const val MAX_PARALLEL_UPLOADS = 3
        const val MAX_ATTEMPTS_PER_ITEM = 5
        const val IMPORT_FILE_THRESHOLD = 25
        const val IMPORT_IDLE_MILLIS = 20_000L
        private const val MAX_BACKOFF_MILLIS = 6 * 60 * 60 * 1_000L
    }
}

private object AlbumImportCoordinator {
    private val albumLocks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()

    suspend fun <T> withAlbumLock(albumId: String, action: suspend () -> T): T =
        albumLocks.computeIfAbsent(albumId) { Mutex() }.withLock { action() }
}
