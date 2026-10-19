package com.wedfaces.cameraoperator

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.wedfaces.cameraoperator.camera.CameraGateway
import com.wedfaces.cameraoperator.data.TransferQueueRepository
import com.wedfaces.cameraoperator.model.QueueState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.ArrayDeque

class CameraDownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val services by lazy { (application as CameraOperatorApp).services }
    private val batchLock = Any()
    private val pendingBatches = ArrayDeque<String>()
    private val knownBatches = mutableSetOf<String>()
    private var activeBatchId: String? = null
    private var batchProcessor: Job? = null
    private var latestStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val batchId = intent?.getStringExtra(KEY_BATCH_ID) ?: run {
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        synchronized(batchLock) { latestStartId = startId }
        val items = services.queue.getBatch(batchId).filter { it.state == QueueState.DOWNLOADING }
        if (items.isEmpty()) {
            val idle = synchronized(batchLock) {
                activeBatchId == null && pendingBatches.isEmpty()
            }
            if (idle) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelfResult(startId)
            }
            return START_NOT_STICKY
        }
        startForeground(
            TransferNotifications.CAMERA_NOTIFICATION_ID,
            TransferNotifications.cameraNotification(this, batchId, 0, items.size),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            } else {
                0
            },
        )
        synchronized(batchLock) {
            if (knownBatches.add(batchId)) pendingBatches.addLast(batchId)
            if (batchProcessor?.isActive != true) {
                batchProcessor = scope.launch { processPendingBatches() }
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun processPendingBatches() {
        while (true) {
            val batchId = synchronized(batchLock) {
                pendingBatches.pollFirst()?.also { activeBatchId = it }
            }
            if (batchId == null) {
                val stopped = withContext(Dispatchers.Main.immediate) {
                    synchronized(batchLock) {
                        if (activeBatchId != null || pendingBatches.isNotEmpty()) {
                            false
                        } else {
                            batchProcessor = null
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            stopSelfResult(latestStartId)
                            true
                        }
                    }
                }
                if (stopped) return
                continue
            }

            try {
                downloadQueuedOriginals(
                    batchId = batchId,
                    downloadsDirectory = File(filesDir, "transfers"),
                    queue = services.queue,
                    camera = services.camera,
                    downloadOriginal = services::downloadCameraOriginal,
                    enqueueBatch = services::enqueueBatch,
                    onProgress = { completed, total ->
                        val notification = TransferNotifications.cameraNotification(
                            this,
                            batchId,
                            completed,
                            total,
                        )
                        getSystemService(android.app.NotificationManager::class.java)
                            .notify(TransferNotifications.CAMERA_NOTIFICATION_ID, notification)
                    },
                )
            } catch (error: CancellationException) {
                services.queue.getBatch(batchId)
                    .filter { it.state == QueueState.DOWNLOADING }
                    .forEach { interrupted ->
                        interrupted.temporaryFilePath?.let(::File)?.delete()
                        services.queue.update(
                            interrupted.id,
                            QueueState.FAILED,
                            lastError = "Camera download stopped. Reconnect the camera and retry this file.",
                        )
                    }
                throw error
            } finally {
                synchronized(batchLock) {
                    activeBatchId = null
                    knownBatches.remove(batchId)
                }
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        synchronized(batchLock) {
            (pendingBatches.toList() + listOfNotNull(activeBatchId)).distinct().forEach { batchId ->
                services.queue.getBatch(batchId)
                    .filter { it.state == QueueState.DOWNLOADING }
                    .forEach { item ->
                        item.temporaryFilePath?.let(::File)?.delete()
                        services.queue.update(
                            item.id,
                            QueueState.FAILED,
                            lastError = "Camera download stopped. Reconnect the camera and retry this file.",
                        )
                    }
            }
            pendingBatches.clear()
            knownBatches.clear()
            activeBatchId = null
        }
        super.onDestroy()
    }

    companion object {
        const val KEY_BATCH_ID = "batch_id"
    }
}

/**
 * Downloads selected camera originals one at a time and publishes every completed
 * file to WorkManager immediately. The final enqueue flushes the batch-complete
 * state so the importer can start without waiting for its idle timer.
 */
internal suspend fun downloadQueuedOriginals(
    batchId: String,
    downloadsDirectory: File,
    queue: TransferQueueRepository,
    camera: CameraGateway,
    enqueueBatch: (String) -> Unit,
    downloadOriginal: suspend (
        item: com.wedfaces.cameraoperator.model.TransferItem,
        destination: File,
        onProgress: (sent: Long, total: Long) -> Unit,
    ) -> Unit = { item, destination, onProgress ->
        camera.downloadSelectedOriginal(item.sourceObjectId, destination, onProgress)
    },
    onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> },
) {
    val items = queue.getBatch(batchId).filter { it.state == QueueState.DOWNLOADING }
    var completedNormally = false
    try {
        items.forEachIndexed { index, item ->
            if (queue.getBatch(batchId).none { it.id == item.id && it.state == QueueState.DOWNLOADING }) {
                return@forEachIndexed
            }
            val file = File(downloadsDirectory, "${item.id}_${item.fileName}")
            try {
                file.parentFile?.mkdirs()
                queue.update(
                    item.id,
                    QueueState.DOWNLOADING,
                    progressBytes = 0,
                    temporaryFilePath = file.absolutePath,
                )
                downloadOriginal(item, file) { sent, _ ->
                    queue.update(item.id, QueueState.DOWNLOADING, progressBytes = sent)
                }
                queue.update(
                    item.id,
                    QueueState.READY_TO_UPLOAD,
                    progressBytes = file.length(),
                    temporaryFilePath = file.absolutePath,
                )
                enqueueBatch(batchId)
            } catch (error: CancellationException) {
                file.delete()
                queue.getBatch(batchId)
                    .filter { it.state == QueueState.DOWNLOADING }
                    .forEach { interrupted ->
                        interrupted.temporaryFilePath?.let(::File)?.delete()
                        queue.update(
                            interrupted.id,
                            QueueState.FAILED,
                            lastError = "Camera download stopped. Reconnect the camera and retry this file.",
                        )
                    }
                throw error
            } catch (error: Exception) {
                file.delete()
                queue.update(
                    item.id,
                    QueueState.FAILED,
                    lastError = error.message ?: "Could not retrieve this original from the camera.",
                )
            }
            onProgress(index + 1, items.size)
        }
        completedNormally = true
    } finally {
        if (completedNormally) {
            queue.setBatchDownloadComplete(batchId, true)
            enqueueBatch(batchId)
        }
    }
}
