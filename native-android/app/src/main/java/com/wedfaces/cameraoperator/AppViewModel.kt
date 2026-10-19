package com.wedfaces.cameraoperator

import android.app.Application
import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.net.Network
import android.net.Uri
import android.util.LruCache
import android.provider.OpenableColumns
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wedfaces.cameraoperator.data.TransferQueueRepository
import com.wedfaces.cameraoperator.model.Album
import com.wedfaces.cameraoperator.model.CameraStorage
import com.wedfaces.cameraoperator.model.CameraPhoto
import com.wedfaces.cameraoperator.model.CameraTransportType
import com.wedfaces.cameraoperator.model.ImportStatus
import com.wedfaces.cameraoperator.model.PhotoSource
import com.wedfaces.cameraoperator.model.QueueState
import com.wedfaces.cameraoperator.model.TransferItem
import com.wedfaces.cameraoperator.network.WedFacesApiException
import io.github.gallo.sonycamera.CameraConnectionState
import io.github.gallo.sonycamera.CameraEvent
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class AppDestination(val title: String) {
    CAMERA("Camera"),
    QUEUE("Queue"),
    SETTINGS("Settings"),
}

data class AppUiState(
    val destination: AppDestination = AppDestination.CAMERA,
    val serverUrl: String = "",
    val email: String = "",
    val selectedAlbumId: String? = null,
    val selectedAlbumName: String? = null,
    val albums: List<Album> = emptyList(),
    val testMode: Boolean = false,
    val allowLocalHttp: Boolean = false,
    val signedIn: Boolean = false,
    val cameraConnected: Boolean = false,
    val cameraTransport: CameraTransportType = CameraTransportType.WIFI,
    val cameraModel: String? = null,
    val cameraNetworkMessage: String? = null,
    val cameraBusy: Boolean = false,
    val usbDetected: Boolean = false,
    val usbHostSupported: Boolean = true,
    val cameraStorages: List<CameraStorage> = emptyList(),
    val selectedStorageId: String? = null,
    val folderPath: List<CameraPhoto> = emptyList(),
    val autoUploadEnabled: Boolean = false,
    val previewPhoto: CameraPhoto? = null,
    val keepScreenOn: Boolean = false,
    val folderWatchUri: String? = null,
    val folderWatchEnabled: Boolean = false,
    val includeExistingFolderFiles: Boolean = false,
    val folderWatchMessage: String? = null,
    val cameraPhotoLimit: String = "50",
    val onlyNewSinceLastSend: Boolean = false,
    val unsupportedCameraCount: Int = 0,
    val wifiSsidInput: String = "",
    val wifiPasswordInput: String = "",
    val photos: List<CameraPhoto> = emptyList(),
    val selectedPhotoIds: Set<String> = emptySet(),
    val thumbnails: Map<String, ByteArray> = emptyMap(),
    val queue: List<TransferItem> = emptyList(),
    val importStatuses: Map<String, ImportStatus> = emptyMap(),
    val busy: Boolean = false,
    val message: String? = null,
    val error: String? = null,
) {
    fun visibleCameraPhotos(): List<CameraPhoto> {
        val filtered = photos.filter { photo ->
            !photo.isFolder &&
                (!onlyNewSinceLastSend || !photo.alreadySent)
        }
        return when (cameraPhotoLimit) {
            "50" -> filtered.take(50)
            "100" -> filtered.take(100)
            else -> filtered
        }
    }
}

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val services = (application as CameraOperatorApp).services
    private val _state = MutableStateFlow(AppUiState())
    val state: StateFlow<AppUiState> = _state.asStateFlow()
    private var cameraNetwork: Network? = null
    private val thumbnailCache = LruCache<String, ByteArray>(THUMBNAIL_CACHE_SIZE)
    private val pendingThumbnails = mutableSetOf<String>()
    private val cameraPhotoCache = linkedMapOf<String, CameraPhoto>()
    private var usbFallbackPollAt = 0L

    init {
        val settings = services.settings.snapshot()
        _state.value = _state.value.copy(
            serverUrl = settings.serverUrl,
            email = settings.email,
            selectedAlbumId = settings.selectedAlbumId,
            selectedAlbumName = settings.selectedAlbumName,
            testMode = settings.testMode,
            allowLocalHttp = settings.allowLocalHttp,
            keepScreenOn = settings.keepScreenOn,
            folderWatchUri = settings.folderWatchUri,
            folderWatchEnabled = settings.folderWatchEnabled,
            includeExistingFolderFiles = settings.includeExistingFolderFiles,
            folderWatchMessage = settings.folderWatchMessage,
            autoUploadEnabled = settings.autoUploadEnabled,
            usbDetected = services.usbCamera.attachedDevice() != null,
            usbHostSupported = services.usbCamera.hasUsbHostSupport(),
            cameraPhotoLimit = settings.cameraPhotoLimit,
            onlyNewSinceLastSend = settings.onlyNewSinceLastSend,
            signedIn = services.tokens.accessToken() != null,
            queue = services.queue.getAll(),
        )
        _state.value = _state.value.copy(importStatuses = loadImportStatuses(_state.value.queue))
        if (_state.value.signedIn) refreshAlbums()
        viewModelScope.launch {
            services.usbCamera.connectionState.collect { connection ->
                when (connection) {
                    is CameraConnectionState.Ready -> {
                        val current = _state.value
                        _state.value = current.copy(
                            usbDetected = true,
                            cameraConnected = current.cameraTransport == CameraTransportType.USB,
                            cameraModel = services.usbCamera.cameraName.value ?: "Sony USB camera",
                            cameraNetworkMessage = "USB — ${services.usbCamera.cameraName.value ?: "Sony camera"} connected.",
                            cameraBusy = false,
                        )
                        if (_state.value.cameraTransport == CameraTransportType.USB) {
                            initializeUsbStorageAndRefresh()
                            ensureAutoUploadBaseline()
                        }
                    }
                    is CameraConnectionState.WaitingForPermission -> _state.value = _state.value.copy(
                        usbDetected = true,
                        cameraBusy = true,
                        cameraNetworkMessage = "Waiting for Android USB permission. Approve the system prompt to continue.",
                    )
                    is CameraConnectionState.Connecting -> _state.value = _state.value.copy(
                        cameraBusy = true,
                        cameraNetworkMessage = "Connecting to the Sony camera over USB…",
                    )
                    is CameraConnectionState.Initializing -> _state.value = _state.value.copy(
                        cameraBusy = true,
                        cameraNetworkMessage = "Initializing Sony PTP and reading camera identity…",
                    )
                    is CameraConnectionState.Error -> _state.value = _state.value.copy(
                        cameraBusy = false,
                        cameraConnected = false,
                        cameraModel = null,
                        usbDetected = services.usbCamera.attachedDevice() != null,
                        cameraNetworkMessage = connection.message,
                    )
                    else -> {
                        val isUsbMode = _state.value.cameraTransport == CameraTransportType.USB
                        _state.value = _state.value.copy(
                            usbDetected = services.usbCamera.attachedDevice() != null,
                            cameraConnected = if (isUsbMode) false else _state.value.cameraConnected,
                            cameraModel = if (isUsbMode) null else _state.value.cameraModel,
                            cameraNetworkMessage = if (isUsbMode) "No USB camera connected." else _state.value.cameraNetworkMessage,
                        )
                    }
                }
            }
        }
        viewModelScope.launch {
            services.usbCamera.events.collect { event ->
                if (event is CameraEvent.ObjectAdded) {
                    val snapshot = _state.value
                    if (snapshot.cameraTransport == CameraTransportType.USB && services.usbCamera.isReady()) {
                        if (snapshot.autoUploadEnabled) handleAutoUploadObject(event.handle)
                        refreshCamera()
                    }
                }
            }
        }
        viewModelScope.launch {
            while (true) {
                delay(45_000)
                if (_state.value.cameraTransport == CameraTransportType.USB && services.usbCamera.isReady()) {
                    _state.value = _state.value.copy(usbDetected = true)
                    if (_state.value.autoUploadEnabled) {
                        val now = System.currentTimeMillis()
                        if (now - usbFallbackPollAt >= AUTO_UPLOAD_FALLBACK_INTERVAL_MS) {
                            usbFallbackPollAt = now
                            pollForUsbPhotos()
                        }
                    } else {
                        refreshCamera()
                    }
                }
            }
        }
        viewModelScope.launch {
            while (true) {
                delay(1_500)
                val queue = services.queue.getAll()
                val latestSettings = services.settings.snapshot()
                _state.value = _state.value.copy(
                    queue = queue,
                    importStatuses = loadImportStatuses(queue),
                    folderWatchUri = latestSettings.folderWatchUri,
                    folderWatchEnabled = latestSettings.folderWatchEnabled,
                    includeExistingFolderFiles = latestSettings.includeExistingFolderFiles,
                    folderWatchMessage = latestSettings.folderWatchMessage,
                )
            }
        }
    }

    fun navigate(destination: AppDestination) {
        _state.value = _state.value.copy(destination = destination, message = null, error = null)
    }

    fun updateServerUrl(value: String) {
        _state.value = _state.value.copy(serverUrl = value)
    }

    fun updateEmail(value: String) {
        _state.value = _state.value.copy(email = value)
    }

    fun updateCameraWifiSsid(value: String) {
        _state.value = _state.value.copy(wifiSsidInput = value)
    }

    fun updateCameraWifiPassword(value: String) {
        _state.value = _state.value.copy(wifiPasswordInput = value)
    }

    fun onCameraPermissionDenied() {
        _state.value = _state.value.copy(
            cameraNetworkMessage = "Nearby Wi-Fi permission is needed only to connect to the camera. You can grant it in Android app settings.",
        )
    }

    fun saveServerSettings() {
        services.settings.saveServerUrl(_state.value.serverUrl)
        services.settings.saveEmail(_state.value.email)
        _state.value = _state.value.copy(message = "Server settings saved.", error = null)
    }

    fun setLocalHttpAllowed(enabled: Boolean) {
        services.settings.setAllowLocalHttp(enabled)
        _state.value = _state.value.copy(allowLocalHttp = enabled)
    }

    fun setKeepScreenOn(enabled: Boolean) {
        services.settings.setKeepScreenOn(enabled)
        _state.value = _state.value.copy(keepScreenOn = enabled)
    }

    fun setFolderWatchFolder(uri: Uri) {
        try {
            getApplication<Application>().contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
            val current = services.settings.snapshot()
            services.settings.setFolderWatch(uri.toString(), false, current.includeExistingFolderFiles)
            services.cancelFolderWatch()
            _state.value = _state.value.copy(
                folderWatchUri = uri.toString(),
                folderWatchEnabled = false,
                folderWatchMessage = null,
                message = "Phone folder saved. Enable watching to queue new photos.",
                error = null,
            )
        } catch (error: Exception) {
            _state.value = _state.value.copy(
                error = "Android could not save access to that folder: ${error.message ?: "permission denied."}",
            )
        }
    }

    fun setFolderWatchEnabled(enabled: Boolean) {
        val current = _state.value
        if (enabled && current.folderWatchUri.isNullOrBlank()) {
            _state.value = current.copy(error = "Choose a phone folder before enabling folder watch.")
            return
        }
        if (enabled && (!current.signedIn || current.selectedAlbumId.isNullOrBlank())) {
            _state.value = current.copy(error = "Sign in and select an album before enabling folder watch.")
            return
        }
        services.settings.setFolderWatchEnabled(enabled)
        if (enabled) services.scheduleFolderWatch() else services.cancelFolderWatch()
        val latest = services.settings.snapshot()
        _state.value = current.copy(
            folderWatchEnabled = enabled,
            folderWatchMessage = latest.folderWatchMessage,
            message = if (enabled) {
                "Phone folder watch is on. New supported photos are checked about every 15 minutes."
            } else {
                "Phone folder watch is paused."
            },
            error = null,
        )
    }

    fun setIncludeExistingFolderFiles(enabled: Boolean) {
        services.settings.setIncludeExistingFolderFiles(enabled)
        _state.value = _state.value.copy(includeExistingFolderFiles = enabled, error = null)
        if (_state.value.folderWatchEnabled) services.scheduleFolderWatchScan()
    }

    fun setCameraPhotoLimit(value: String) {
        services.settings.setCameraPhotoLimit(value)
        _state.value = _state.value.copy(cameraPhotoLimit = services.settings.snapshot().cameraPhotoLimit)
    }

    fun setOnlyNewSinceLastSend(enabled: Boolean) {
        services.settings.setOnlyNewSinceLastSend(enabled)
        _state.value = _state.value.copy(onlyNewSinceLastSend = enabled)
    }

    fun setTestMode(enabled: Boolean) {
        services.settings.setTestMode(enabled)
        _state.value = _state.value.copy(testMode = enabled, photos = emptyList(), selectedPhotoIds = emptySet())
        if (enabled) {
            _state.value = _state.value.copy(
                cameraConnected = false,
                cameraModel = null,
                cameraNetworkMessage = "TEST MODE — no camera connected. Choose actual images from this device.",
            )
            viewModelScope.launch {
                services.camera.disconnect()
                services.cameraNetwork.release()
                cameraNetwork = null
            }
        } else {
            _state.value = _state.value.copy(cameraNetworkMessage = "Test Mode is off. Connect to a supported Sony camera.")
        }
    }

    fun signIn(password: String) = viewModelScope.launch {
        val current = _state.value
        services.settings.saveServerUrl(current.serverUrl)
        services.settings.saveEmail(current.email)
        _state.value = current.copy(busy = true, error = null, message = null)
        try {
            val result = services.api.login(current.email, password)
            services.settings.saveEmail(result.email ?: current.email)
            _state.value = _state.value.copy(
                busy = false,
                signedIn = true,
                email = result.email ?: current.email,
                message = "Signed in to WED Faces.",
            )
            loadAlbums()
        } catch (error: Exception) {
            _state.value = _state.value.copy(busy = false, error = error.message ?: "Sign-in failed.")
        }
    }

    fun signOut() = viewModelScope.launch {
        services.api.clearSession()
        services.settings.saveAlbum(null, null)
        _state.value = _state.value.copy(
            signedIn = false,
            albums = emptyList(),
            selectedAlbumId = null,
            selectedAlbumName = null,
            message = "Signed out.",
            error = null,
        )
    }

    fun refreshAlbums() = viewModelScope.launch {
        if (!services.tokens.accessToken().isNullOrBlank()) loadAlbums()
    }

    private suspend fun loadAlbums() {
        _state.value = _state.value.copy(busy = true, error = null)
        try {
            val albums = services.api.listAlbums()
            val savedId = _state.value.selectedAlbumId
            val selected = albums.firstOrNull { it.id == savedId } ?: albums.firstOrNull()
            services.settings.saveAlbum(selected?.id, selected?.name)
            _state.value = _state.value.copy(
                busy = false,
                signedIn = true,
                albums = albums,
                selectedAlbumId = selected?.id,
                selectedAlbumName = selected?.name,
                message = "${albums.size} album${if (albums.size == 1) "" else "s"} loaded.",
            )
        } catch (error: Exception) {
            if (error is WedFacesApiException && error.statusCode == 401) {
                _state.value = _state.value.copy(signedIn = false)
            }
            _state.value = _state.value.copy(busy = false, error = error.message ?: "Could not load albums.")
        }
    }

    fun selectAlbum(album: Album) {
        services.settings.saveAlbum(album.id, album.name)
        _state.value = _state.value.copy(
            selectedAlbumId = album.id,
            selectedAlbumName = album.name,
            message = "Album selected: ${album.name}",
            error = null,
        )
    }

    fun connectCamera(network: Network, endpoint: String? = null) = viewModelScope.launch {
        if (!switchCameraTransport(CameraTransportType.WIFI)) return@launch
        _state.value = _state.value.copy(cameraBusy = true, cameraNetworkMessage = "Connecting to Sony camera…", error = null)
        try {
            val model = services.camera.connect(network, endpoint)
            cameraNetwork = network
            _state.value = _state.value.copy(
                cameraTransport = CameraTransportType.WIFI,
                cameraBusy = false,
                cameraConnected = true,
                cameraModel = model,
                cameraNetworkMessage = "Wi-Fi — $model connected.",
                cameraStorages = emptyList(),
                selectedStorageId = null,
                folderPath = emptyList(),
            )
            initializeWifiStorageAndRefresh()
        } catch (error: Exception) {
            cameraNetwork = null
            _state.value = _state.value.copy(
                cameraBusy = false,
                cameraConnected = false,
                cameraModel = null,
                cameraNetworkMessage = error.message ?: "Camera connection failed.",
            )
        }
    }

    fun useCurrentWifi() {
        viewModelScope.launch {
            if (!switchCameraTransport(CameraTransportType.WIFI)) return@launch
            services.cameraNetwork.useCurrentWifi(
                onConnected = { network -> connectCamera(network) },
                onFailure = { message -> _state.value = _state.value.copy(cameraNetworkMessage = message) },
            )
        }
    }

    fun requestCameraWifi(ssid: String, passphrase: String) {
        viewModelScope.launch {
            if (!switchCameraTransport(CameraTransportType.WIFI)) return@launch
            services.cameraNetwork.connectToCameraWifi(
                ssid,
                passphrase,
                onConnected = { network -> connectCamera(network) },
                onNetworkLost = {
                    viewModelScope.launch {
                        services.camera.disconnect()
                        cameraNetwork = null
                        _state.value = _state.value.copy(
                            cameraConnected = false,
                            cameraModel = null,
                            photos = emptyList(),
                            cameraNetworkMessage = "Camera Wi-Fi disconnected.",
                        )
                    }
                },
                onFailure = { message -> _state.value = _state.value.copy(cameraNetworkMessage = message) },
            )
        }
    }

    fun selectCameraTransport(transport: CameraTransportType) = viewModelScope.launch {
        switchCameraTransport(transport)
    }

    fun rescanUsb() = viewModelScope.launch {
        val supported = services.usbCamera.hasUsbHostSupport()
        val device = services.usbCamera.attachedDevice()
        _state.value = _state.value.copy(
            usbHostSupported = supported,
            usbDetected = device != null,
            cameraNetworkMessage = when {
                !supported -> "This phone does not report USB Host/OTG support. Use Camera Wi-Fi instead."
                device == null -> "No compatible Sony PTP device detected. Check camera power, a data-capable cable and OTG adapter, then set USB Connection to PC Remote or Auto."
                else -> "USB device detected. Tap Connect USB Camera to request Android permission and initialize PTP."
            },
        )
    }

    fun connectUsbCamera() = viewModelScope.launch {
        if (!switchCameraTransport(CameraTransportType.USB)) return@launch
        val supported = services.usbCamera.hasUsbHostSupport()
        val device = services.usbCamera.attachedDevice()
        _state.value = _state.value.copy(usbHostSupported = supported, usbDetected = device != null)
        if (!supported) {
            _state.value = _state.value.copy(
                cameraNetworkMessage = "This phone does not support USB Host/OTG. Connect using Camera Wi-Fi.",
            )
            return@launch
        }
        if (device == null) {
            _state.value = _state.value.copy(
                cameraNetworkMessage = "No compatible Sony PTP device detected. Check the data cable/OTG adapter and set the camera to PC Remote or Auto.",
            )
            return@launch
        }
        if (services.usbCamera.isReady()) {
            _state.value = _state.value.copy(cameraConnected = true)
            initializeUsbStorageAndRefresh()
            return@launch
        }
        _state.value = _state.value.copy(
            cameraBusy = true,
            cameraNetworkMessage = "Requesting Android USB permission…",
        )
        services.usbCamera.connect()
    }

    private suspend fun switchCameraTransport(target: CameraTransportType): Boolean {
        val current = _state.value
        if (current.cameraTransport == target) return true
        if (services.queue.getAll().any { it.state == QueueState.DOWNLOADING }) {
            _state.value = current.copy(
                error = "Wait for the current camera original transfer to finish before switching connection modes.",
            )
            return false
        }
        if (current.cameraTransport == CameraTransportType.USB) {
            services.usbCamera.disconnect()
            delay(500)
        }
        if (current.cameraConnected && current.cameraTransport == CameraTransportType.WIFI) {
            services.camera.disconnect()
            services.cameraNetwork.release()
            cameraNetwork = null
        }
        cameraPhotoCache.clear()
        thumbnailCache.evictAll()
        _state.value = _state.value.copy(
            cameraTransport = target,
            cameraConnected = false,
            cameraModel = null,
            cameraBusy = false,
            cameraStorages = emptyList(),
            selectedStorageId = null,
            folderPath = emptyList(),
            photos = emptyList(),
            selectedPhotoIds = emptySet(),
            thumbnails = emptyMap(),
            previewPhoto = null,
            autoUploadEnabled = if (target == CameraTransportType.USB) {
                current.autoUploadEnabled
            } else {
                if (current.autoUploadEnabled) services.settings.setAutoUploadEnabled(false)
                false
            },
            cameraNetworkMessage = if (target == CameraTransportType.USB) {
                if (services.usbCamera.hasUsbHostSupport()) "USB mode selected. Connect a powered Sony camera with a data-capable OTG connection."
                else "This phone does not report USB Host/OTG support. Use Camera Wi-Fi instead."
            } else {
                "Camera Wi-Fi mode selected."
            },
        )
        return true
    }

    private suspend fun initializeUsbStorageAndRefresh() {
        try {
            val storages = services.usbCamera.listStorages()
            if (storages.isEmpty()) {
                _state.value = _state.value.copy(
                    cameraStorages = emptyList(),
                    cameraBusy = false,
                    cameraNetworkMessage = "Sony PTP is connected, but the camera did not expose a memory card. Insert/unlock a card and refresh.",
                )
                return
            }
            val currentId = _state.value.selectedStorageId
            val selectedId = storages.firstOrNull { it.id == currentId }?.id ?: storages.first().id
            _state.value = _state.value.copy(cameraStorages = storages, selectedStorageId = selectedId)
            refreshCamera()
        } catch (error: Exception) {
            _state.value = _state.value.copy(
                cameraBusy = false,
                cameraNetworkMessage = error.message ?: "Could not read the Sony camera card.",
            )
        }
    }

    private suspend fun initializeWifiStorageAndRefresh() {
        try {
            val storages = services.camera.listStorages()
            if (storages.isEmpty()) {
                _state.value = _state.value.copy(cameraBusy = false, cameraNetworkMessage = "No camera storage was exposed.")
                return
            }
            val selectedId = storages.firstOrNull { it.id == _state.value.selectedStorageId }?.id ?: storages.first().id
            _state.value = _state.value.copy(cameraStorages = storages, selectedStorageId = selectedId)
            refreshCamera()
        } catch (error: Exception) {
            _state.value = _state.value.copy(cameraBusy = false, error = error.message ?: "Could not list camera storage.")
        }
    }

    fun selectCameraStorage(storageId: String) = viewModelScope.launch {
        if (storageId == _state.value.selectedStorageId) return@launch
        _state.value = _state.value.copy(selectedStorageId = storageId, folderPath = emptyList(), photos = emptyList())
        refreshCamera()
    }

    fun openCameraFolder(folder: CameraPhoto) {
        if (!folder.isFolder) return
        _state.value = _state.value.copy(folderPath = _state.value.folderPath + folder)
        refreshCamera()
    }

    fun navigateCameraUp() {
        if (_state.value.folderPath.isEmpty()) return
        _state.value = _state.value.copy(folderPath = _state.value.folderPath.dropLast(1))
        refreshCamera()
    }

    fun selectCurrentFolderRecursively() = viewModelScope.launch {
        val current = _state.value
        val storageId = current.selectedStorageId ?: return@launch
        val targetFolder = current.folderPath.lastOrNull()?.objectId
        val root = mutableListOf<CameraPhoto>()
        val todo = ArrayDeque<String?>().apply { add(targetFolder) }
        val visited = mutableSetOf<String?>()
        while (todo.isNotEmpty()) {
            val parent = todo.removeFirst()
            if (!visited.add(parent)) continue
            val items = if (current.cameraTransport == CameraTransportType.USB) {
                services.usbCamera.listFolder(storageId, parent)
            } else {
                services.camera.listFolder(storageId, parent)
            }
            items.forEach { item ->
                cameraPhotoCache[item.objectId] = item
                if (item.isFolder) todo.add(item.objectId) else root.add(item)
            }
        }
        val selected = root.filter(::isSupported).map { it.objectId }.toSet()
        _state.value = _state.value.copy(
            selectedPhotoIds = _state.value.selectedPhotoIds + selected,
            message = "${selected.size} supported image${if (selected.size == 1) "" else "s"} selected from this folder and its descendants.",
        )
    }

    fun previewPhoto(photo: CameraPhoto) {
        if (!photo.isFolder) _state.value = _state.value.copy(previewPhoto = photo)
    }

    fun closePhotoPreview() {
        _state.value = _state.value.copy(previewPhoto = null)
    }

    fun uploadPhoto(photo: CameraPhoto) {
        if (!isSupported(photo)) return
        cameraPhotoCache[photo.objectId] = photo
        _state.value = _state.value.copy(selectedPhotoIds = _state.value.selectedPhotoIds + photo.objectId)
        sendSelected()
    }

    fun setAutoUploadEnabled(enabled: Boolean) = viewModelScope.launch {
        val current = _state.value
        if (!enabled) {
            services.settings.setAutoUploadEnabled(false)
            _state.value = current.copy(autoUploadEnabled = false, message = "Auto Upload is OFF. New images remain available for manual selection.")
            return@launch
        }
        if (current.cameraTransport != CameraTransportType.USB || !services.usbCamera.isReady()) {
            _state.value = current.copy(error = "Connect a Sony camera using USB Cable before enabling Auto Upload.")
            return@launch
        }
        if (!current.signedIn || current.selectedAlbumId.isNullOrBlank()) {
            _state.value = current.copy(error = "Sign in and select a WED Faces album in Settings before enabling Auto Upload.")
            return@launch
        }
        try {
            val cameraIdentity = services.usbCamera.cameraIdentity()
            val baseline = services.usbCamera.listAllPhotos()
            services.queue.setAutoUploadBaseline(cameraIdentity, baseline.map(::autoObjectIdentity))
            services.settings.setAutoUploadEnabled(true)
            _state.value = current.copy(
                autoUploadEnabled = true,
                message = "Auto Upload is ON. Existing card images were recorded as the baseline and will not be uploaded.",
            )
        } catch (error: Exception) {
            services.settings.setAutoUploadEnabled(false)
            _state.value = current.copy(
                autoUploadEnabled = false,
                error = error.message ?: "Could not read the card baseline. Auto Upload remains OFF.",
            )
        }
    }

    private suspend fun ensureAutoUploadBaseline() {
        if (!_state.value.autoUploadEnabled || !_state.value.cameraConnected ||
            _state.value.cameraTransport != CameraTransportType.USB
        ) return
        val cameraIdentity = services.usbCamera.cameraIdentity()
        if (services.queue.hasAutoUploadBaseline(cameraIdentity)) return
        val existing = services.usbCamera.listAllPhotos()
        services.queue.setAutoUploadBaseline(cameraIdentity, existing.map(::autoObjectIdentity))
        _state.value = _state.value.copy(
            message = "Auto Upload baseline saved for this Sony camera; existing card images were skipped.",
        )
    }

    private suspend fun pollForUsbPhotos() {
        try {
            ensureAutoUploadBaseline()
            val existing = services.usbCamera.listAllPhotos()
            existing.forEach { processAutoUploadPhoto(it) }
        } catch (error: Exception) {
            _state.value = _state.value.copy(
                cameraNetworkMessage = error.message ?: "Auto Upload could not scan the Sony card; it will retry.",
            )
        }
    }

    private suspend fun handleAutoUploadObject(handle: Long) {
        try {
            ensureAutoUploadBaseline()
            processAutoUploadPhoto(services.usbCamera.getPhotoInfo(handle))
        } catch (error: Exception) {
            _state.value = _state.value.copy(
                cameraNetworkMessage = error.message ?: "Could not read the new camera object; refresh and retry.",
            )
        }
    }

    private suspend fun processAutoUploadPhoto(photo: CameraPhoto) {
        val current = _state.value
        if (!current.autoUploadEnabled || !current.signedIn || !services.usbCamera.isReady() ||
            photo.isFolder || !isSupported(photo)
        ) return
        val settings = services.settings.snapshot()
        val albumId = settings.selectedAlbumId?.takeIf(String::isNotBlank) ?: return
        val albumName = settings.selectedAlbumName?.takeIf(String::isNotBlank) ?: return
        val cameraIdentity = services.usbCamera.cameraIdentity()
        val objectIdentity = autoObjectIdentity(photo)
        if (services.queue.isInAutoUploadBaseline(cameraIdentity, objectIdentity)) return
        cameraPhotoCache[photo.objectId] = photo
        val batchId = UUID.randomUUID().toString()
        val item = TransferQueueRepository.newTransfer(
            batchId = batchId,
            albumId = albumId,
            albumName = albumName,
            source = PhotoSource.CAMERA,
            sourceObjectId = photo.objectId,
            fileName = safeFileName(photo.fileName),
            mimeType = mimeTypeFor(photo.fileName) ?: "image/jpeg",
            sizeBytes = photo.sizeBytes,
            sourceCapturedAtMillis = photo.capturedAtMillis,
            initialState = QueueState.DOWNLOADING,
            transportType = CameraTransportType.USB,
        )
        if (services.queue.insertAutoUploadIfNew(item, cameraIdentity, objectIdentity)) {
            services.startCameraDownload(batchId)
            _state.value = _state.value.copy(
                message = "New image detected: ${photo.fileName}. Its original was added to the existing transfer queue.",
                queue = services.queue.getAll(),
            )
        }
    }

    private fun autoObjectIdentity(photo: CameraPhoto): String =
        listOf(
            photo.storageId.orEmpty(),
            photo.objectId.substringAfter(':', photo.objectId),
            photo.fileName.trim(),
            photo.sizeBytes?.toString().orEmpty(),
            photo.capturedAtMillis?.toString().orEmpty(),
        ).joinToString("\u0000")

    fun savePhotoToPhone(photo: CameraPhoto) = viewModelScope.launch {
        if (!isSupported(photo) || photo.isFolder) {
            _state.value = _state.value.copy(error = "Only supported JPG, PNG and WebP images can be saved to the phone.")
            return@launch
        }
        if (!services.usbCamera.isReady() && _state.value.cameraTransport == CameraTransportType.USB ||
            !_state.value.cameraConnected
        ) {
            _state.value = _state.value.copy(error = "Reconnect the camera before saving this original.")
            return@launch
        }
        val app = getApplication<Application>()
        val temp = File(app.cacheDir, "gallery_${UUID.randomUUID()}_${safeFileName(photo.fileName)}")
        var galleryUri: Uri? = null
        try {
            _state.value = _state.value.copy(cameraBusy = true, error = null)
            withContext(Dispatchers.IO) {
                if (_state.value.cameraTransport == CameraTransportType.USB) {
                    services.usbCamera.downloadOriginal(photo.objectId, temp) { _, _ -> }
                } else {
                    services.camera.downloadSelectedOriginal(photo.objectId, temp) { _, _ -> }
                }
            }
            val resolver = app.contentResolver
            val name = uniqueGalleryName(photo.fileName)
            val mime = mimeTypeFor(name) ?: photo.mimeType ?: "image/jpeg"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, mime)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/WED Faces")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                } else {
                    val directory = File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                        "WED Faces",
                    )
                    if (!directory.exists() && !directory.mkdirs()) {
                        throw IllegalStateException("Could not create Pictures/WED Faces.")
                    }
                    put(MediaStore.Images.Media.DATA, File(directory, name).absolutePath)
                }
            }
            galleryUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Android could not create a Gallery item.")
            withContext(Dispatchers.IO) {
                resolver.openOutputStream(galleryUri!!, "w").use { output ->
                    requireNotNull(output) { "Android could not open the Gallery destination." }
                    temp.inputStream().use { input -> input.copyTo(output) }
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(galleryUri!!, ContentValues().apply {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                }, null, null)
            }
            _state.value = _state.value.copy(
                cameraBusy = false,
                message = "${photo.fileName} was saved to Pictures/WED Faces.",
            )
        } catch (error: Exception) {
            galleryUri?.let { runCatching { app.contentResolver.delete(it, null, null) } }
            _state.value = _state.value.copy(
                cameraBusy = false,
                error = error.message ?: "Could not save the camera original to the phone.",
            )
        } finally {
            temp.delete()
        }
    }

    fun onGalleryPermissionDenied() {
        _state.value = _state.value.copy(
            error = "Storage permission is needed to save this image on Android 8 or 9. No permission is requested for newer Android versions.",
        )
    }

    private fun uniqueGalleryName(originalName: String): String {
        val resolver = getApplication<Application>().contentResolver
        val base = safeFileName(originalName)
        val stem = base.substringBeforeLast('.')
        val extension = base.substringAfterLast('.', "jpg")
        var candidate = base
        var suffix = 1
        while (resolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media._ID),
                "${MediaStore.Images.Media.DISPLAY_NAME} = ?",
                arrayOf(candidate),
                null,
            )?.use { it.moveToFirst() } == true
        ) {
            candidate = "$stem ($suffix).$extension"
            suffix += 1
        }
        return candidate
    }

    fun refreshCamera() = viewModelScope.launch {
        if (_state.value.testMode) return@launch
        if (!_state.value.cameraConnected) {
            _state.value = _state.value.copy(cameraNetworkMessage = "Connect to the camera before browsing its card.")
            return@launch
        }
        _state.value = _state.value.copy(cameraBusy = true, error = null)
        try {
            val current = _state.value
            val storageId = current.selectedStorageId
                ?: throw IllegalStateException("Select a camera storage before browsing.")
            val folderId = current.folderPath.lastOrNull()?.objectId
            val listed = when (current.cameraTransport) {
                CameraTransportType.USB -> services.usbCamera.listFolder(storageId, folderId)
                CameraTransportType.WIFI -> services.camera.listFolder(storageId, folderId)
                CameraTransportType.NONE -> emptyList()
            }
            val photos = listed.map { photo ->
                if (photo.source == PhotoSource.CAMERA) {
                    photo.copy(
                        alreadySent = services.queue.isCameraObjectSent(
                            "${current.cameraTransport}:${photo.storageId}:${photo.objectId}:${photo.fileName}",
                            photo.sizeBytes,
                            photo.capturedAtMillis,
                        ),
                    )
                } else {
                    photo
                }
            }
            val cameraPhotos = photos
            cameraPhotos.forEach { cameraPhotoCache[it.objectId] = it }
            val supported = cameraPhotos.filter { !it.isFolder && isSupported(it) }
            _state.value = _state.value.copy(
                cameraBusy = false,
                photos = cameraPhotos,
                unsupportedCameraCount = cameraPhotos.count { !it.isFolder && !isSupported(it) },
                selectedPhotoIds = _state.value.selectedPhotoIds.intersect(
                    cameraPhotoCache.values.filter { !it.isFolder && isSupported(it) }.map { it.objectId }.toSet(),
                ),
                cameraNetworkMessage = "${current.cameraTransport.name} — showing card folders and file information. Originals are read only when selected.",
            )
        } catch (error: Exception) {
            _state.value = _state.value.copy(cameraBusy = false, error = error.message ?: "Could not list camera images.")
        }
    }

    fun requestThumbnail(objectId: String) {
        thumbnailCache.get(objectId)?.let { cached ->
            _state.value = _state.value.copy(thumbnails = _state.value.thumbnails + (objectId to cached))
            return
        }
        if (_state.value.thumbnails.containsKey(objectId) || !pendingThumbnails.add(objectId)) return
        if (!_state.value.cameraConnected || _state.value.testMode) {
            pendingThumbnails.remove(objectId)
            return
        }
        viewModelScope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) {
                    if (_state.value.cameraTransport == CameraTransportType.USB) {
                        services.usbCamera.getThumbnail(objectId)
                    } else {
                        services.camera.getThumbnail(objectId)
                    }
                }
                if (bytes != null && _state.value.cameraConnected) {
                    thumbnailCache.put(objectId, bytes)
                    val updated = _state.value.thumbnails + (objectId to bytes)
                    _state.value = _state.value.copy(
                        thumbnails = updated.entries
                            .takeLast(THUMBNAIL_CACHE_SIZE)
                            .associate { it.toPair() },
                    )
                }
            } finally {
                pendingThumbnails.remove(objectId)
            }
        }
    }

    fun disconnectCamera() = viewModelScope.launch {
        if (_state.value.cameraTransport == CameraTransportType.USB) {
            services.usbCamera.disconnect()
        } else {
            services.camera.disconnect()
            services.cameraNetwork.release()
            cameraNetwork = null
        }
        cameraPhotoCache.clear()
        _state.value = _state.value.copy(
            cameraConnected = false,
            cameraModel = null,
            photos = emptyList(),
            cameraStorages = emptyList(),
            selectedStorageId = null,
            folderPath = emptyList(),
            selectedPhotoIds = emptySet(),
            thumbnails = emptyMap(),
            unsupportedCameraCount = 0,
            cameraNetworkMessage = "Camera disconnected.",
        )
    }

    fun openWifiSettings(context: android.content.Context) {
        services.cameraNetwork.openWifiSettings(context)
    }

    fun showTestImages(uris: List<Uri>) = viewModelScope.launch {
        if (!_state.value.testMode) {
            _state.value = _state.value.copy(error = "Enable Test Mode before choosing device images.")
            return@launch
        }
        val resolver = getApplication<Application>().contentResolver
        val imported = withContext(Dispatchers.IO) {
            uris.mapNotNull { uri ->
                val name = displayName(uri) ?: "test-image-${UUID.randomUUID()}.jpg"
                val mime = resolver.getType(uri)?.lowercase() ?: mimeTypeFor(name)
                val photo = CameraPhoto(
                    objectId = uri.toString(),
                    fileName = name,
                    sizeBytes = querySize(uri),
                    capturedAtMillis = null,
                    formatCode = null,
                    mimeType = mime,
                    source = PhotoSource.TEST_GALLERY,
                    testUri = uri,
                )
                photo
            }
        }
        _state.value = _state.value.copy(
            photos = imported,
            selectedPhotoIds = imported.filter(::isSupported).map { it.objectId }.toSet(),
            message = "${imported.size} device image${if (imported.size == 1) "" else "s"} loaded in TEST MODE.",
            error = null,
        )
    }

    fun togglePhoto(photo: CameraPhoto) {
        if (photo.isFolder || !isSupported(photo)) return
        cameraPhotoCache[photo.objectId] = photo
        val ids = _state.value.selectedPhotoIds
        _state.value = _state.value.copy(
            selectedPhotoIds = if (photo.objectId in ids) ids - photo.objectId else ids + photo.objectId,
        )
    }

    fun selectAllSupported() {
        _state.value = _state.value.copy(
            selectedPhotoIds = _state.value.visibleCameraPhotos().filter(::isSupported).map { it.objectId }.toSet(),
        )
    }

    fun clearSelection() {
        _state.value = _state.value.copy(selectedPhotoIds = emptySet())
    }

    fun sendSelected() = viewModelScope.launch {
        val current = _state.value
        val albumId = current.selectedAlbumId
        val albumName = current.selectedAlbumName
        if (!current.signedIn || albumId.isNullOrBlank() || albumName.isNullOrBlank()) {
            _state.value = current.copy(error = "Sign in and select a WED Faces album in Settings first.")
            return@launch
        }
        val available = (cameraPhotoCache.values + current.photos).associateBy { it.objectId }.values
        val selected = available.filter { it.objectId in current.selectedPhotoIds && isSupported(it) }
        if (selected.isEmpty()) {
            _state.value = current.copy(error = "Select at least one supported image.")
            return@launch
        }
        if (selected.any { it.source == PhotoSource.CAMERA } && !current.cameraConnected) {
            _state.value = current.copy(error = "Reconnect the camera before retrieving selected originals.")
            return@launch
        }
        val batchId = UUID.randomUUID().toString()
        _state.value = current.copy(busy = true, error = null, message = null, destination = AppDestination.QUEUE)
        val hasCameraPhotos = selected.any { it.source == PhotoSource.CAMERA }
        services.queue.createBatch(batchId, albumId, downloadComplete = !hasCameraPhotos)
        for (photo in selected) {
            val item = TransferQueueRepository.newTransfer(
                batchId = batchId,
                albumId = albumId,
                albumName = albumName,
                source = photo.source,
                sourceObjectId = photo.objectId,
                fileName = safeFileName(photo.fileName),
                mimeType = mimeTypeFor(photo.fileName) ?: photo.mimeType.orEmpty(),
                sizeBytes = photo.sizeBytes,
                sourceCapturedAtMillis = photo.capturedAtMillis,
                initialState = if (photo.source == PhotoSource.CAMERA) QueueState.DOWNLOADING else QueueState.READY_TO_UPLOAD,
                transportType = if (photo.source == PhotoSource.CAMERA) current.cameraTransport else CameraTransportType.NONE,
            )
            services.queue.insert(item)
            if (photo.source == PhotoSource.CAMERA) {
                continue
            }
            val destination = File(getApplication<Application>().filesDir, "transfers/${item.id}_${item.fileName}")
            try {
                destination.parentFile?.mkdirs()
                val uri = photo.testUri ?: throw IllegalStateException("The selected device image is no longer available.")
                withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openInputStream(uri).use { input ->
                        requireNotNull(input) { "Android could not open the selected image." }
                        FileOutputStream(destination).use(input::copyTo)
                    }
                }
                if (destination.length() == 0L) throw IllegalStateException("The selected device image is empty.")
                services.queue.update(
                    item.id,
                    QueueState.READY_TO_UPLOAD,
                    progressBytes = destination.length(),
                    temporaryFilePath = destination.absolutePath,
                )
            } catch (error: Exception) {
                destination.delete()
                services.queue.update(item.id, QueueState.FAILED, lastError = error.message ?: "Could not prepare this original.")
            }
        }
        val batch = services.queue.getBatch(batchId)
        if (hasCameraPhotos) {
            if (!current.cameraConnected) {
                services.queue.setBatchDownloadComplete(batchId, true)
                batch.filter { it.state == QueueState.DOWNLOADING }
                    .forEach { services.queue.update(it.id, QueueState.FAILED, lastError = "Reconnect the camera before retrieving selected originals.") }
            } else {
                services.startCameraDownload(batchId)
                _state.value = _state.value.copy(message = "Camera originals are downloading. Each finished file will enter the upload queue immediately.")
            }
        }
        if (batch.any { it.state == QueueState.READY_TO_UPLOAD } && batch.none { it.state == QueueState.FAILED }) {
            services.enqueueBatch(batchId)
            if (!hasCameraPhotos) {
                _state.value = _state.value.copy(message = "Selected originals are queued. Upload starts when internet is available.")
            }
        } else if (batch.any { it.state == QueueState.READY_TO_UPLOAD }) {
            services.enqueueBatch(batchId)
            if (!hasCameraPhotos) {
                _state.value = _state.value.copy(message = "Ready items are queued; failed image preparations need a retry.")
            }
        } else if (hasCameraPhotos && current.cameraConnected) {
            // The foreground service enqueues each successfully retrieved original.
        } else {
            _state.value = _state.value.copy(error = "No originals could be prepared. Check the camera connection and try again.")
        }
        _state.value = _state.value.copy(busy = false, queue = services.queue.getAll(), selectedPhotoIds = emptySet())
    }

    fun retryBatch(batchId: String) {
        val batch = services.queue.getBatch(batchId)
        batch.filter {
            it.state == QueueState.FAILED ||
                it.state == QueueState.WAITING_FOR_INTERNET ||
                it.state == QueueState.IMPORT_REVIEW ||
                it.state == QueueState.AUTH_REQUIRED ||
                it.state == QueueState.PAUSED
        }
            .forEach { item ->
                val path = item.temporaryFilePath?.let(::File)
                if (path?.isFile == true) {
                    services.queue.update(
                        item.id,
                        QueueState.READY_TO_UPLOAD,
                        progressBytes = 0,
                        attempts = 0,
                        lastError = null,
                        importStarted = false,
                    )
                } else if (item.source == PhotoSource.CAMERA && item.state == QueueState.FAILED) {
                    services.queue.update(
                        item.id,
                        QueueState.DOWNLOADING,
                        progressBytes = 0,
                        attempts = 0,
                        lastError = null,
                    )
                }
            }
        if (services.queue.getBatch(batchId).any { it.state == QueueState.DOWNLOADING }) {
            services.queue.setBatchDownloadComplete(batchId, false)
            services.startCameraDownload(batchId)
        } else {
            services.enqueueBatch(batchId)
        }
        _state.value = _state.value.copy(queue = services.queue.getAll(), message = "Retry queued.", error = null)
    }

    fun cancelBatch(batchId: String) {
        services.cancelBatch(batchId)
        services.queue.getBatch(batchId)
            .filter { it.state != QueueState.IMPORTED && it.state != QueueState.CANCELLED }
            .forEach { services.queue.update(it.id, QueueState.CANCELLED, lastError = "Cancelled locally; any already accepted server upload is not rolled back.") }
        _state.value = _state.value.copy(queue = services.queue.getAll(), message = "Stopped local follow-up. Server-side staged files, if any, are unchanged.")
    }

    fun clearImported() {
        val queue = services.queue.getAll()
        val batches = queue.filter { it.state == QueueState.IMPORTED }.map { it.batchId }.toSet()
        queue.filter { it.state == QueueState.IMPORTED }.forEach { services.queue.deleteImported(it.id) }
        batches.forEach { batchId ->
            if (services.queue.getBatch(batchId).isEmpty()) services.queue.deleteImportStatus(batchId)
        }
        val updated = services.queue.getAll()
        _state.value = _state.value.copy(queue = updated, importStatuses = loadImportStatuses(updated))
    }

    fun diagnostics(): String {
        val current = _state.value
        val queueCounts = current.queue.groupingBy { it.state.title }.eachCount().entries
            .sortedBy { it.key }.joinToString("\n") { "${it.key}: ${it.value}" }
        return buildString {
            appendLine("WED Faces Camera Operator ${BuildConfig.VERSION_NAME}")
            appendLine("Server: ${current.serverUrl.ifBlank { "(not configured)" }}")
            appendLine("Signed in: ${current.signedIn}")
            appendLine("Album selected: ${current.selectedAlbumName ?: "(none)"}")
            appendLine("Test Mode: ${current.testMode}")
            appendLine("Phone folder watch: ${current.folderWatchEnabled}")
            appendLine("Camera connected: ${current.cameraConnected}")
            appendLine("Queue:")
            append(queueCounts.ifBlank { "(empty)" })
        }
    }

    private fun isSupported(photo: CameraPhoto): Boolean {
        if (photo.isFolder) return false
        val extension = photo.fileName.substringAfterLast('.', "").lowercase()
        return extension in SUPPORTED_EXTENSIONS
    }

    private fun safeFileName(value: String): String {
        val cleaned = value.replace(Regex("[^A-Za-z0-9._-]"), "_").take(140)
        return cleaned.takeIf { it.contains('.') && it.substringAfterLast('.').lowercase() in SUPPORTED_EXTENSIONS }
            ?: "image-${UUID.randomUUID()}.jpg"
    }

    private fun mimeTypeFor(name: String): String? = when (name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        else -> null
    }

    private fun displayName(uri: Uri): String? =
        getApplication<Application>().contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    private fun querySize(uri: Uri): Long? =
        getApplication<Application>().contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null }

    private fun loadImportStatuses(queue: List<TransferItem>): Map<String, ImportStatus> =
        queue.map { it.batchId }.distinct().mapNotNull { batchId ->
            services.queue.getImportStatus(batchId)?.let { batchId to it }
        }.toMap()

    private companion object {
        const val THUMBNAIL_CACHE_SIZE = 8
        const val AUTO_UPLOAD_FALLBACK_INTERVAL_MS = 90_000L
        val SUPPORTED_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")
        val SUPPORTED_MIME_TYPES = setOf("image/jpeg", "image/png", "image/webp")
        val JPEG_EXTENSIONS = setOf("jpg", "jpeg")
        val RAW_EXTENSIONS = setOf("arw", "cr2", "cr3", "nef", "dng", "raw")
    }
}
