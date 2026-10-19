package com.wedfaces.cameraoperator

import android.app.Application
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import com.wedfaces.cameraoperator.camera.CameraNetworkController
import com.wedfaces.cameraoperator.camera.SonyUsbCameraAdapter
import com.wedfaces.cameraoperator.camera.SonyPtpIpCameraAdapter
import com.wedfaces.cameraoperator.data.AndroidTokenStore
import com.wedfaces.cameraoperator.data.SettingsRepository
import com.wedfaces.cameraoperator.data.TokenStore
import com.wedfaces.cameraoperator.data.TransferQueueRepository
import com.wedfaces.cameraoperator.network.WedFacesApi
import com.wedfaces.cameraoperator.model.CameraTransportType
import com.wedfaces.cameraoperator.model.TransferItem
import io.github.gallo.sonycamera.service.CameraConnectionClient
import java.io.File
import java.util.concurrent.TimeUnit

class CameraOperatorApp : Application() {
    lateinit var services: AppServices
        private set

    override fun onCreate() {
        super.onCreate()
        services = AppServices(this)
        TransferNotifications.createChannel(this)
        if (services.settings.snapshot().folderWatchEnabled) services.scheduleFolderWatch()
    }

    internal fun replaceServicesForTest(testServices: AppServices) {
        services.queue.close()
        services = testServices
    }
}

class AppServices(
    application: Application,
    tokenStore: TokenStore = AndroidTokenStore(application),
    queueDatabaseName: String = "camera_operator_queue.db",
    private val retrySchedulerForTest: ((batchId: String, delayMillis: Long) -> Unit)? = null,
) {
    private val appContext = application.applicationContext
    val settings = SettingsRepository(application)
    val tokens = tokenStore
    val queue = TransferQueueRepository(application, queueDatabaseName)
    val cameraNetwork = CameraNetworkController(application)
    val camera = SonyPtpIpCameraAdapter(application)
    val usbCameraClient = CameraConnectionClient(application)
    val usbCamera = SonyUsbCameraAdapter(application, usbCameraClient)
    val api = WedFacesApi(
        baseUrl = { settings.snapshot().serverUrl },
        allowLocalHttp = { settings.snapshot().allowLocalHttp },
        tokenStore = tokenStore,
    )

    private val workManager by lazy { WorkManager.getInstance(application) }

    suspend fun downloadCameraOriginal(
        item: TransferItem,
        destination: File,
        onProgress: (sent: Long, total: Long) -> Unit,
    ) {
        when (item.transportType) {
            CameraTransportType.USB -> usbCamera.downloadOriginal(item.sourceObjectId, destination, onProgress)
            CameraTransportType.WIFI -> camera.downloadSelectedOriginal(item.sourceObjectId, destination, onProgress)
            CameraTransportType.NONE -> throw IllegalStateException("This queue item has no camera transport.")
        }
    }

    fun enqueueBatch(batchId: String) {
        workManager.enqueueUniqueWork(
            workName(batchId),
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            transferRequest(batchId).build(),
        )
    }

    fun enqueueBatchIfInactive(batchId: String) {
        val activeStates = setOf(WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED)
        val infos = workManager.getWorkInfosForUniqueWork(workName(batchId)).get()
        if (infos.none { it.state in activeStates }) enqueueBatch(batchId)
    }

    fun startCameraDownload(batchId: String) {
        val intent = android.content.Intent(appContext, CameraDownloadService::class.java)
            .putExtra(CameraDownloadService.KEY_BATCH_ID, batchId)
        androidx.core.content.ContextCompat.startForegroundService(appContext, intent)
    }

    fun pauseBatch(batchId: String) {
        workManager.cancelUniqueWork(workName(batchId))
        queue.pauseBatch(batchId)
    }

    fun scheduleBatchRetry(batchId: String, delayMillis: Long) {
        retrySchedulerForTest?.let {
            it(batchId, delayMillis.coerceAtLeast(0L))
            return
        }
        val request = transferRequest(batchId)
            .setInitialDelay(delayMillis.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
            .build()
        workManager.enqueueUniqueWork(
            workName(batchId),
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request,
        )
    }

    private fun transferRequest(batchId: String) =
        OneTimeWorkRequestBuilder<TransferWorker>()
            .setInputData(workDataOf(TransferWorker.KEY_BATCH_ID to batchId))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(
                androidx.work.BackoffPolicy.EXPONENTIAL,
                30,
                TimeUnit.SECONDS,
            )

    private fun workName(batchId: String) = "wedfaces-transfer-$batchId"

    fun cancelBatch(batchId: String) {
        workManager.cancelUniqueWork(workName(batchId))
        queue.cancelBatchItems(batchId)
        appContext.stopService(android.content.Intent(appContext, CameraDownloadService::class.java))
    }

    fun scheduleFolderWatch() {
        val periodic = PeriodicWorkRequestBuilder<FolderWatchWorker>(15, TimeUnit.MINUTES).build()
        workManager.enqueueUniquePeriodicWork(
            FOLDER_WATCH_PERIODIC_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            periodic,
        )
        scheduleFolderWatchScan()
    }

    fun scheduleFolderWatchScan() {
        workManager.enqueueUniqueWork(
            FOLDER_WATCH_SCAN_NAME,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<FolderWatchWorker>().build(),
        )
    }

    fun cancelFolderWatch() {
        workManager.cancelUniqueWork(FOLDER_WATCH_SCAN_NAME)
        workManager.cancelUniquePeriodicWork(FOLDER_WATCH_PERIODIC_NAME)
    }

    companion object {
        private const val FOLDER_WATCH_PERIODIC_NAME = "wedfaces-phone-folder-watch"
        private const val FOLDER_WATCH_SCAN_NAME = "wedfaces-phone-folder-watch-scan"
    }
}
