package com.wedfaces.cameraoperator

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ImageSearch
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wedfaces.cameraoperator.model.CameraFileSupport
import com.wedfaces.cameraoperator.model.CameraPhoto
import com.wedfaces.cameraoperator.model.CameraTransportType
import com.wedfaces.cameraoperator.model.PhotoSource
import com.wedfaces.cameraoperator.model.QueueState
import com.wedfaces.cameraoperator.model.TransferItem

private val Ink = Color(0xFF000000)
private val Paper = Color(0xFFFFFFFF)
private val Muted = Color(0xFF757575)
private val Line = Color(0xFFD6D6D6)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        forwardUsbAttach(intent)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color.White,
                    onPrimary = Ink,
                    secondary = Color(0xFFBDBDBD),
                    surface = Color(0xFF1A1A1A),
                    onSurface = Color.White,
                    background = Ink,
                    onBackground = Color.White,
                    outline = Color(0xFF494949),
                    error = Color(0xFFBDBDBD),
                ),
            ) {
                CameraOperatorRoot()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        forwardUsbAttach(intent)
    }

    @Suppress("DEPRECATION")
    private fun forwardUsbAttach(intent: Intent?) {
        if (intent?.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return
        val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
        if (device != null) {
            (application as CameraOperatorApp).services.usbCameraClient.onUsbDeviceAttached(device)
        }
    }
}

@Composable
private fun CameraOperatorRoot(viewModel: AppViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    var useCurrentAfterPermission by remember { mutableStateOf(false) }
    var pendingGalleryPhoto by remember { mutableStateOf<CameraPhoto?>(null) }
    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(50),
    ) { uris -> if (uris.isNotEmpty()) viewModel.showTestImages(uris) }
    val phoneFolderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri -> if (uri != null) viewModel.setFolderWatchFolder(uri) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            if (useCurrentAfterPermission) viewModel.useCurrentWifi()
            else cameraConnectAction(viewModel, state)
        }
        else viewModel.onCameraPermissionDenied()
        useCurrentAfterPermission = false
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        viewModel.sendSelected()
    }
    val galleryPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val photo = pendingGalleryPhoto
        pendingGalleryPhoto = null
        if (granted && photo != null) viewModel.savePhotoToPhone(photo)
        else if (!granted) viewModel.onGalleryPermissionDenied()
    }
    val saveToPhone: (CameraPhoto) -> Unit = { photo ->
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            pendingGalleryPhoto = photo
            galleryPermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            viewModel.savePhotoToPhone(photo)
        }
    }
    val beginSend: () -> Unit = {
        if (
            Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            viewModel.sendSelected()
        }
    }
    val keepScreenOn = state.keepScreenOn && state.queue.any {
        it.state == QueueState.DOWNLOADING ||
            it.state == QueueState.READY_TO_UPLOAD ||
            it.state == QueueState.UPLOADING
    }
    SideEffect {
        val window = (context as? Activity)?.window ?: return@SideEffect
        if (keepScreenOn) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    DisposableEffect(context) {
        onDispose {
            (context as? Activity)?.window
                ?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
    fun requestWifiPermission(useCurrent: Boolean) {
        useCurrentAfterPermission = useCurrent
        val permission = if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (ContextCompat.checkSelfPermission(context, permission) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            if (useCurrent) viewModel.useCurrentWifi() else cameraConnectAction(viewModel, state)
        } else {
            permissionLauncher.launch(permission)
        }
    }

    Scaffold(
        containerColor = Ink,
        contentWindowInsets = WindowInsets.navigationBars,
        bottomBar = {
            NavigationBar(containerColor = Color(0xFF1A1A1A), contentColor = Color.White) {
                AppDestination.entries.forEach { destination ->
                    val icon = when (destination) {
                        AppDestination.CAMERA -> Icons.Default.CameraAlt
                        AppDestination.QUEUE -> Icons.Default.CloudUpload
                        AppDestination.SETTINGS -> Icons.Default.Settings
                    }
                    NavigationBarItem(
                        selected = state.destination == destination,
                        onClick = { viewModel.navigate(destination) },
                        icon = { Icon(icon, contentDescription = destination.title) },
                        label = { Text(destination.title, fontSize = 11.sp) },
                    )
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().background(Ink).padding(padding),
        ) {
            Header(state)
            if (state.error != null) Notice(state.error!!, isError = true)
            if (state.message != null) Notice(state.message!!, isError = false)
            when (state.destination) {
                AppDestination.CAMERA -> CameraScreen(
                    state = state,
                    onConnect = { requestWifiPermission(useCurrent = false) },
                    onConnectUsb = viewModel::connectUsbCamera,
                    onRescanUsb = viewModel::rescanUsb,
                    onTransport = viewModel::selectCameraTransport,
                    onUseWifi = { requestWifiPermission(useCurrent = true) },
                    onWifiSsid = viewModel::updateCameraWifiSsid,
                    onWifiPassword = viewModel::updateCameraWifiPassword,
                    onWifiSettings = { viewModel.openWifiSettings(context) },
                    onRefresh = viewModel::refreshCamera,
                    onDisconnect = viewModel::disconnectCamera,
                    onToggle = viewModel::togglePhoto,
                    onSelectAll = viewModel::selectAllSupported,
                    onClearSelection = viewModel::clearSelection,
                    onSend = beginSend,
                    onRequestThumbnail = viewModel::requestThumbnail,
                    onSelectStorage = viewModel::selectCameraStorage,
                    onOpenFolder = viewModel::openCameraFolder,
                    onGoUp = viewModel::navigateCameraUp,
                    onNavigateToFolder = viewModel::navigateCameraToPath,
                    onSelectFolder = viewModel::selectCurrentFolderRecursively,
                    onPreview = viewModel::previewPhoto,
                    onClosePreview = viewModel::closePhotoPreview,
                    onUploadPhoto = viewModel::uploadPhoto,
                    onSaveToPhone = saveToPhone,
                    onAutoUpload = viewModel::setAutoUploadEnabled,
                    onPhotoLimit = viewModel::setCameraPhotoLimit,
                    onOnlyNew = viewModel::setOnlyNewSinceLastSend,
                    onPickImages = {
                        imagePicker.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly))
                    },
                )
                AppDestination.QUEUE -> QueueScreen(
                    state = state,
                    onRetry = viewModel::retryBatch,
                    onCancel = viewModel::cancelBatch,
                    onClearImported = viewModel::clearImported,
                )
                AppDestination.SETTINGS -> SettingsScreen(
                    state = state,
                    onServerUrl = viewModel::updateServerUrl,
                    onEmail = viewModel::updateEmail,
                    onSave = viewModel::saveServerSettings,
                    onSignIn = viewModel::signIn,
                    onSignOut = viewModel::signOut,
                    onToggleTestMode = viewModel::setTestMode,
                    onToggleLocalHttp = viewModel::setLocalHttpAllowed,
                    onToggleKeepScreenOn = viewModel::setKeepScreenOn,
                    onPickFolder = { phoneFolderPicker.launch(null) },
                    onToggleFolderWatch = viewModel::setFolderWatchEnabled,
                    onIncludeExistingFolderFiles = viewModel::setIncludeExistingFolderFiles,
                    onSelectAlbum = viewModel::selectAlbum,
                    onReloadAlbums = viewModel::refreshAlbums,
                    diagnostics = viewModel.diagnostics(),
                )
            }
        }
    }
}

private fun cameraConnectAction(viewModel: AppViewModel, state: AppUiState) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        viewModel.useCurrentWifi()
    } else {
        viewModel.requestCameraWifi(state.wifiSsidInput, state.wifiPasswordInput)
    }
}

@Composable
private fun Header(state: AppUiState) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text("WED FACES", fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp, color = Color(0xFFBDBDBD))
            Text(
                when (state.destination) {
                    AppDestination.CAMERA -> "Camera Operator"
                    AppDestination.QUEUE -> "Transfer queue"
                    AppDestination.SETTINGS -> "Settings"
                },
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        if (state.testMode) {
            Text(
                "TEST MODE",
                modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(Color.White).padding(horizontal = 8.dp, vertical = 5.dp),
                color = Ink,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(if (state.cameraConnected) Color.White else Color(0xFF777777)))
                Spacer(Modifier.width(6.dp))
                Text(if (state.cameraConnected) "CAMERA" else "OFFLINE", fontSize = 10.sp, color = Color(0xFFBDBDBD), letterSpacing = 1.sp)
            }
        }
    }
}

@Composable
private fun CameraScreen(
    state: AppUiState,
    onConnect: () -> Unit,
    onConnectUsb: () -> Unit,
    onRescanUsb: () -> Unit,
    onTransport: (CameraTransportType) -> Unit,
    onUseWifi: () -> Unit,
    onWifiSsid: (String) -> Unit,
    onWifiPassword: (String) -> Unit,
    onWifiSettings: () -> Unit,
    onRefresh: () -> Unit,
    onDisconnect: () -> Unit,
    onToggle: (CameraPhoto) -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onSend: () -> Unit,
    onRequestThumbnail: (String) -> Unit,
    onSelectStorage: (String) -> Unit,
    onOpenFolder: (CameraPhoto) -> Unit,
    onGoUp: () -> Unit,
    onNavigateToFolder: (Int) -> Unit,
    onSelectFolder: () -> Unit,
    onPreview: (CameraPhoto) -> Unit,
    onClosePreview: () -> Unit,
    onUploadPhoto: (CameraPhoto) -> Unit,
    onSaveToPhone: (CameraPhoto) -> Unit,
    onAutoUpload: (Boolean) -> Unit,
    onPhotoLimit: (String) -> Unit,
    onOnlyNew: (Boolean) -> Unit,
    onPickImages: () -> Unit,
) {
    var ssid by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var photoLimitMenu by remember { mutableStateOf(false) }
    var storageMenu by remember { mutableStateOf(false) }
    var folderMenu by remember { mutableStateOf(false) }
    val visiblePhotos = state.visibleCameraPhotos()
    val visibleFolders = state.photos.filter { it.isFolder }
    val canSend = state.signedIn && state.selectedAlbumId != null && state.selectedPhotoIds.isNotEmpty() && !state.busy
    val cameraTransferInProgress = state.queue.any {
        it.source == PhotoSource.CAMERA && it.state == QueueState.DOWNLOADING
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A)),
            border = BorderStroke(1.dp, Line),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (state.testMode) Icons.Default.ImageSearch else Icons.Default.Wifi, null, tint = Color.White, modifier = Modifier.size(19.dp))
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (state.testMode) "Device image test" else (state.cameraModel ?: "Sony camera"), fontWeight = FontWeight.SemiBold)
                        Text(
                            if (state.testMode) "TEST MODE — no camera connected" else (state.cameraNetworkMessage ?: "Connect to browse camera thumbnails"),
                            fontSize = 12.sp,
                            color = Color(0xFFBDBDBD),
                        )
                    }
                    if (state.cameraBusy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White)
                }
                if (!state.testMode) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = { onTransport(CameraTransportType.WIFI) },
                            modifier = Modifier.weight(1f),
                            enabled = state.cameraTransport != CameraTransportType.WIFI,
                        ) { Icon(Icons.Default.Wifi, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Camera Wi-Fi") }
                        OutlinedButton(
                            onClick = { onTransport(CameraTransportType.USB) },
                            modifier = Modifier.weight(1f),
                            enabled = state.cameraTransport != CameraTransportType.USB,
                        ) { Icon(Icons.Default.CameraAlt, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("USB Cable") }
                    }
                }
                if (state.testMode) {
                    Button(onClick = onPickImages, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.ImageSearch, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Choose images from device")
                    }
                } else if (state.cameraConnected) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onRefresh, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Refresh, null, Modifier.size(17.dp)); Spacer(Modifier.width(6.dp)); Text("Refresh")
                        }
                        OutlinedButton(
                            onClick = onDisconnect,
                            modifier = Modifier.weight(1f),
                            enabled = !state.cameraBusy && !cameraTransferInProgress,
                        ) {
                            Text("Disconnect")
                        }
                    }
                    if (cameraTransferInProgress) {
                        Text(
                            "Stop the active camera batch from Queue before disconnecting.",
                            fontSize = 12.sp,
                            color = Muted,
                        )
                    }
                    if (state.cameraStorages.isNotEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Storage/Card", color = Color(0xFFBDBDBD), fontSize = 12.sp)
                            Box {
                                OutlinedButton(onClick = { storageMenu = true }) {
                                    Text(state.cameraStorages.firstOrNull { it.id == state.selectedStorageId }?.name ?: "Select card")
                                }
                                DropdownMenu(expanded = storageMenu, onDismissRequest = { storageMenu = false }) {
                                    state.cameraStorages.forEach { storage ->
                                        DropdownMenuItem(
                                            text = { Text(storage.name) },
                                            onClick = { onSelectStorage(storage.id); storageMenu = false },
                                        )
                                    }
                                }
                            }
                            TextButton(onClick = onGoUp, enabled = state.folderPath.isNotEmpty()) { Text("Back/Up") }
                        }
                        if (state.folderPath.isNotEmpty()) {
                            Row(
                                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                TextButton(onClick = { onNavigateToFolder(0) }) { Text("Card") }
                                state.folderPath.forEachIndexed { index, folder ->
                                    Text("/", color = Muted)
                                    TextButton(onClick = { onNavigateToFolder(index + 1) }) {
                                        Text(folder.fileName, maxLines = 1)
                                    }
                                }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(onClick = onSelectFolder, modifier = Modifier.weight(1f)) {
                                Text("Select Folder")
                            }
                        }
                    }
                    if (state.cameraTransport == CameraTransportType.USB) {
                        SettingSwitch(
                            title = "Auto Upload",
                            detail = "Upload newly detected supported originals. Camera-body physical-shutter detection is not verified here.",
                            checked = state.autoUploadEnabled,
                            onCheckedChange = onAutoUpload,
                            enabled = !state.autoUploadEnabled ||
                                (state.cameraConnected && state.signedIn && state.selectedAlbumId != null),
                        )
                    }
                } else if (state.cameraTransport == CameraTransportType.USB) {
                    Text(
                        if (!state.usbHostSupported) "This phone does not report USB Host/OTG support. Use Camera Wi-Fi."
                        else "Connect a data-capable USB cable and OTG adapter. Power on the camera and set USB Connection to PC Remote or Auto.",
                        fontSize = 12.sp,
                        color = Color(0xFFBDBDBD),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(onClick = onRescanUsb, modifier = Modifier.weight(1f)) { Text("Rescan USB") }
                        Button(
                            onClick = onConnectUsb,
                            enabled = !state.cameraBusy && state.usbHostSupported && state.usbDetected,
                            modifier = Modifier.weight(1f),
                        ) { Text("Connect USB Camera") }
                    }
                    SettingSwitch(
                        title = "Auto Upload",
                        detail = "Sign in, choose an album, then connect by USB. Existing card images are baseline-skipped.",
                        checked = state.autoUploadEnabled,
                        onCheckedChange = onAutoUpload,
                        enabled = true,
                    )
                } else {
                    OutlinedTextField(
                        value = state.wifiSsidInput,
                        onValueChange = onWifiSsid,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Camera Wi-Fi name (SSID)") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = state.wifiPasswordInput,
                        onValueChange = onWifiPassword,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Camera Wi-Fi password (if required)") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                    )
                    Button(onClick = onConnect, enabled = !state.cameraBusy, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Wifi, null); Spacer(Modifier.width(7.dp)); Text("Connect to camera Wi-Fi")
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(onClick = onWifiSettings, modifier = Modifier.weight(1f)) { Text("Wi-Fi settings") }
                        OutlinedButton(onClick = onUseWifi, modifier = Modifier.weight(1f)) { Text("Use current Wi-Fi") }
                    }
                    Text(
                        "Camera browsing reads file metadata and thumbnails only. Originals are retrieved only for images you select.",
                        color = Color(0xFFBDBDBD),
                        fontSize = 11.sp,
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("${visiblePhotos.size} images shown", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text("${state.selectedPhotoIds.size} selected · ${state.selectedAlbumName ?: "No album selected"}", fontSize = 11.sp, color = Color(0xFFBDBDBD))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box {
                    TextButton(onClick = { photoLimitMenu = true }) { Text("Last ${state.cameraPhotoLimit}") }
                    DropdownMenu(expanded = photoLimitMenu, onDismissRequest = { photoLimitMenu = false }) {
                        listOf("50", "100", "all").forEach { limit ->
                            DropdownMenuItem(
                                text = { Text(if (limit == "all") "All" else "Last $limit") },
                                onClick = { onPhotoLimit(limit); photoLimitMenu = false },
                            )
                        }
                    }
                }
                TextButton(onClick = onSelectAll) { Text("Select all") }
                TextButton(onClick = onClearSelection) { Text("Clear") }
            }
        }
        if (visibleFolders.isNotEmpty()) {
            Box(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                OutlinedButton(onClick = { folderMenu = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Browse folders · ${visibleFolders.size}", modifier = Modifier.weight(1f))
                    Text("⌄")
                }
                DropdownMenu(expanded = folderMenu, onDismissRequest = { folderMenu = false }) {
                    visibleFolders.forEach { folder ->
                        DropdownMenuItem(
                            text = { Text(folder.fileName) },
                            onClick = { onOpenFolder(folder); folderMenu = false },
                        )
                    }
                }
            }
        }
        if (state.unsupportedCameraCount > 0) {
            Text(
                "${state.unsupportedCameraCount} RAW or video files are not supported by the server and are not selectable.",
                color = Color(0xFFBDBDBD),
                fontSize = 11.sp,
                modifier = Modifier.padding(bottom = 5.dp),
            )
        }
        SettingSwitch(
            title = "Only new since last send",
            detail = "Hide camera images already imported to WED Faces. Turn off to select and resend them.",
            checked = state.onlyNewSinceLastSend,
            onCheckedChange = onOnlyNew,
        )
        if (visiblePhotos.isEmpty()) {
            EmptyState(
                title = when {
                    state.onlyNewSinceLastSend && state.photos.isNotEmpty() -> "No new supported images"
                    state.unsupportedCameraCount > 0 -> "No supported images"
                    state.testMode -> "No device images chosen"
                    else -> "No camera images"
                },
                detail = when {
                    state.onlyNewSinceLastSend && state.photos.isNotEmpty() -> "Turn off Only new since last send to review or resend imported images."
                    state.unsupportedCameraCount > 0 -> "This card has no JPG, PNG, or WEBP images supported by the server."
                    state.testMode -> "Choose actual images from this device to exercise the transfer flow."
                    else -> "Connect to your supported Sony camera, then refresh to browse its card."
                },
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(visiblePhotos, key = { it.objectId }) { photo ->
                    PhotoTile(
                        photo,
                        photo.objectId in state.selectedPhotoIds,
                        state.thumbnails[photo.objectId],
                        onVisible = { onRequestThumbnail(photo.objectId) },
                        onSelect = { onToggle(photo) },
                        onPreview = { onPreview(photo) },
                    )
                }
            }
        }
        Button(
            onClick = onSend,
            enabled = canSend,
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 12.dp).height(50.dp),
        ) {
            Icon(Icons.Default.CloudUpload, null)
            Spacer(Modifier.width(8.dp))
            Text(if (state.busy) "Preparing originals…" else "Upload Selected (${state.selectedPhotoIds.size})")
        }
        state.previewPhoto?.let { photo ->
            CameraPhotoPreviewDialog(
                photo = photo,
                thumbnailBytes = state.thumbnails[photo.objectId],
                selected = photo.objectId in state.selectedPhotoIds,
                onClose = onClosePreview,
                onToggle = { onToggle(photo) },
                onUpload = { onUploadPhoto(photo) },
                onSaveToPhone = { onSaveToPhone(photo) },
            )
        }
    }
}

@Composable
private fun PhotoTile(
    photo: CameraPhoto,
    selected: Boolean,
    thumbnailBytes: ByteArray?,
    onVisible: () -> Unit,
    onSelect: () -> Unit,
    onPreview: () -> Unit,
) {
    LaunchedEffect(photo.objectId, photo.source) {
        if (photo.source == PhotoSource.CAMERA) onVisible()
    }
    val bitmap = remember(thumbnailBytes) {
        thumbnailBytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
    }
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) Color(0xFF303030) else Color(0xFF1A1A1A))
            .padding(4.dp),
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(5.dp))
                .background(Color(0xFF2A2A2A)).clickable(onClick = onPreview),
            contentAlignment = Alignment.Center,
        ) {
            if (bitmap != null) {
                Image(bitmap, contentDescription = photo.fileName, modifier = Modifier.fillMaxSize())
            } else {
                Icon(Icons.Default.ImageSearch, null, tint = Muted, modifier = Modifier.size(26.dp))
            }
            if (selected) {
                Box(Modifier.align(Alignment.TopEnd).padding(5.dp).size(20.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Check, null, tint = Ink, modifier = Modifier.size(15.dp))
                }
            }
        }
        Text(photo.fileName, modifier = Modifier.padding(start = 3.dp, top = 5.dp).clickable(onClick = onPreview), maxLines = 1, fontSize = 10.sp)
        if (photo.alreadySent) {
            Text("Already sent · resend available", modifier = Modifier.padding(start = 3.dp), color = Color(0xFFBDBDBD), fontSize = 9.sp)
        }
        Text(
            photo.sizeBytes?.let(::formatBytes) ?: if (photo.source == PhotoSource.TEST_GALLERY) "Device image" else "Camera file",
            modifier = Modifier.padding(start = 3.dp, bottom = 2.dp),
            color = Muted,
            fontSize = 9.sp,
        )
        val supported = CameraFileSupport.isSupported(photo.fileName)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (supported) (photo.mimeType ?: "Image") else "Unsupported",
                modifier = Modifier.weight(1f).padding(start = 3.dp),
                color = if (supported) Muted else Color(0xFFE0A0A0),
                fontSize = 9.sp,
            )
            TextButton(onClick = onSelect, enabled = supported) {
                Text(if (selected) "Selected" else "Select", fontSize = 9.sp)
            }
        }
    }
}

@Composable
private fun CameraPhotoPreviewDialog(
    photo: CameraPhoto,
    thumbnailBytes: ByteArray?,
    selected: Boolean,
    onClose: () -> Unit,
    onToggle: () -> Unit,
    onUpload: () -> Unit,
    onSaveToPhone: () -> Unit,
) {
    val bitmap = remember(thumbnailBytes) {
        thumbnailBytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
    }
    val supported = CameraFileSupport.isSupported(photo.fileName)
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(photo.fileName, maxLines = 2) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier.fillMaxWidth().height(250.dp).background(Color(0xFF222222), RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (bitmap != null) {
                        Image(bitmap, contentDescription = "Preview of ${photo.fileName}", modifier = Modifier.fillMaxSize())
                    } else {
                        Text("Thumbnail unavailable", color = Muted)
                    }
                }
                Text("${photo.sizeBytes?.let(::formatBytes) ?: "Size unavailable"} · ${photo.mimeType ?: "Unsupported format"}")
                Text(
                    photo.capturedAtMillis?.let {
                        java.text.DateFormat.getDateTimeInstance().format(java.util.Date(it))
                    } ?: "Capture time unavailable",
                    color = Muted,
                    fontSize = 12.sp,
                )
                Text(
                    if (supported) "Preview only. The full-resolution original is fetched only when you choose an action."
                    else "This format is visible on the card but is not supported by the WED Faces upload flow.",
                    fontSize = 12.sp,
                    color = Muted,
                )
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onToggle, enabled = supported) {
                    Text(if (selected) "Unselect" else "Select")
                }
                TextButton(onClick = onUpload, enabled = supported) { Text("Upload Now") }
                TextButton(onClick = onSaveToPhone, enabled = supported && photo.source == PhotoSource.CAMERA) {
                    Text("Save to Phone")
                }
            }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Close") } },
    )
}

@Composable
private fun QueueScreen(
    state: AppUiState,
    onRetry: (String) -> Unit,
    onCancel: (String) -> Unit,
    onClearImported: () -> Unit,
) {
    val batches = state.queue.groupBy { it.batchId }.values.sortedByDescending { group -> group.maxOfOrNull { it.createdAtMillis } ?: 0L }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text("Uploads continue in the background when internet is available.", color = Color(0xFFBDBDBD), fontSize = 12.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = onClearImported, enabled = state.queue.any { it.state == QueueState.IMPORTED }) { Text("Clear done") }
        }
        if (state.queue.isEmpty()) {
            EmptyState("Queue is empty", "Selected originals and their server import status will appear here.")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f)) {
                items(batches.size, key = { batches[it].first().batchId }) { index ->
                    val batch = batches[index]
                    val batchId = batch.first().batchId
                    val retryable = batch.any {
                        it.state == QueueState.FAILED ||
                            it.state == QueueState.WAITING_FOR_INTERNET ||
                            it.state == QueueState.IMPORT_REVIEW ||
                            it.state == QueueState.AUTH_REQUIRED ||
                            it.state == QueueState.PAUSED
                    }
                    val cancellable = batch.any { it.state != QueueState.IMPORTED && it.state != QueueState.CANCELLED }
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A)),
                        border = BorderStroke(1.dp, Line),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                Text(batch.first().albumName, fontWeight = FontWeight.SemiBold)
                                Text("${batch.count { it.state == QueueState.IMPORTED }}/${batch.size}", color = Color(0xFFBDBDBD), fontSize = 12.sp)
                            }
                            state.importStatuses[batchId]?.let { status ->
                                ImportStatusSummary(status, batch)
                            }
                            batch.forEach { item ->
                                QueueItemRow(item)
                            }
                            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                                if (retryable) TextButton(onClick = { onRetry(batchId) }) { Text("Retry") }
                                if (cancellable) TextButton(onClick = { onCancel(batchId) }) { Text("Stop local work") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ImportStatusSummary(status: com.wedfaces.cameraoperator.model.ImportStatus, batch: List<TransferItem>) {
    val counts = listOfNotNull(
        status.total?.let { "Total $it" },
        status.imported?.let { "Imported $it" },
        status.duplicates?.let { "Duplicates $it" },
        status.failed?.let { "Failed $it" },
    ).joinToString(" · ")
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            "Server import: ${status.state}${if (counts.isNotBlank()) " · $counts" else ""}",
            fontSize = 10.sp,
            color = Color(0xFFBDBDBD),
        )
        status.error?.takeIf(String::isNotBlank)?.let {
            Text(it, fontSize = 10.sp, color = Color(0xFFBDBDBD))
        }
        status.failures.forEach { failure ->
            Text(failure, fontSize = 10.sp, color = Color(0xFFBDBDBD))
        }
        val matchedFailures = status.failures.count { failure ->
            batch.any { failure.contains(it.fileName, ignoreCase = true) }
        }
        val otherFailures = maxOf(
            status.failures.count { failure ->
                batch.none { failure.contains(it.fileName, ignoreCase = true) }
            },
            ((status.failed ?: 0).coerceAtLeast(status.failures.size) - matchedFailures).coerceAtLeast(0),
        )
        if (otherFailures > 0) {
            Text("$otherFailures other failures on the server", fontSize = 10.sp, color = Color(0xFFBDBDBD))
        }
    }
}

@Composable
private fun QueueItemRow(item: TransferItem) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                when (item.state) {
                    QueueState.IMPORTED -> Icons.Default.Check
                    QueueState.FAILED, QueueState.IMPORT_REVIEW, QueueState.AUTH_REQUIRED -> Icons.Default.Refresh
                    QueueState.CANCELLED -> Icons.Default.DeleteOutline
                    else -> Icons.Default.CloudUpload
                },
                null,
                tint = Color(0xFFBDBDBD),
                modifier = Modifier.size(17.dp),
            )
            Spacer(Modifier.width(7.dp))
            Column(Modifier.weight(1f)) {
                Text(item.fileName, maxLines = 1, fontSize = 12.sp)
                Text(item.state.title, fontSize = 10.sp, color = Color(0xFFBDBDBD))
            }
            if (item.sizeBytes != null && item.sizeBytes > 0) {
                Text("${formatBytes(item.progressBytes)} / ${formatBytes(item.sizeBytes)}", fontSize = 10.sp, color = Muted)
            }
        }
        item.lastError?.takeIf(String::isNotBlank)?.let {
            Text(it, color = Color(0xFFBDBDBD), fontSize = 10.sp, modifier = Modifier.padding(start = 24.dp, top = 3.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(
    state: AppUiState,
    onServerUrl: (String) -> Unit,
    onEmail: (String) -> Unit,
    onSave: () -> Unit,
    onSignIn: (String) -> Unit,
    onSignOut: () -> Unit,
    onToggleTestMode: (Boolean) -> Unit,
    onToggleLocalHttp: (Boolean) -> Unit,
    onToggleKeepScreenOn: (Boolean) -> Unit,
    onPickFolder: () -> Unit,
    onToggleFolderWatch: (Boolean) -> Unit,
    onIncludeExistingFolderFiles: (Boolean) -> Unit,
    onSelectAlbum: (com.wedfaces.cameraoperator.model.Album) -> Unit,
    onReloadAlbums: () -> Unit,
    diagnostics: String,
) {
    var password by rememberSaveable { mutableStateOf("") }
    var albumMenu by remember { mutableStateOf(false) }
    val context = LocalContext.current
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A)), border = BorderStroke(1.dp, Line)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionTitle("WED Faces server")
                OutlinedTextField(
                    value = state.serverUrl,
                    onValueChange = onServerUrl,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Server base URL") },
                    placeholder = { Text("https://your-wed-faces-server") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                OutlinedTextField(
                    value = state.email,
                    onValueChange = onEmail,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Email") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                )
                if (!state.signedIn) {
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Password") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                    )
                    Button(
                        onClick = {
                            onSave()
                            onSignIn(password)
                            password = ""
                        },
                        enabled = !state.busy && state.email.isNotBlank() && password.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.Lock, null); Spacer(Modifier.width(7.dp)); Text(if (state.busy) "Signing in…" else "Sign in")
                    }
                    Text("The password is used for sign-in only and is not saved on this device.", fontSize = 11.sp, color = Color(0xFFBDBDBD))
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        Text("Signed in as ${state.email}", fontSize = 12.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = onSignOut) { Text("Sign out") }
                    }
                    Box {
                        OutlinedButton(onClick = { albumMenu = true }, modifier = Modifier.fillMaxWidth()) {
                            Text("Album: ${state.selectedAlbumName ?: "Choose album"}", modifier = Modifier.weight(1f))
                            Text("⌄")
                        }
                        DropdownMenu(expanded = albumMenu, onDismissRequest = { albumMenu = false }) {
                            state.albums.forEach { album ->
                                DropdownMenuItem(
                                    text = { Text(album.name) },
                                    onClick = { onSelectAlbum(album); albumMenu = false },
                                )
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onReloadAlbums, modifier = Modifier.weight(1f)) { Text("Reload albums") }
                        OutlinedButton(onClick = onSave, modifier = Modifier.weight(1f)) { Text("Save URL/email") }
                    }
                    Text("Album selection uses the server album ID required by the upload route.", fontSize = 11.sp, color = Color(0xFFBDBDBD))
                }
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A)), border = BorderStroke(1.dp, Line)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                SectionTitle("Phone folder watch")
                Text(
                    if (state.folderWatchUri == null) "No phone folder selected."
                    else "Folder access saved. New supported photos are copied into the transfer queue.",
                    fontSize = 11.sp,
                    color = Color(0xFFBDBDBD),
                )
                OutlinedButton(onClick = onPickFolder, modifier = Modifier.fillMaxWidth()) {
                    Text(if (state.folderWatchUri == null) "Choose phone folder" else "Change phone folder")
                }
                SettingSwitch(
                    title = "Include existing photos",
                    detail = "When enabled, the next scan also queues supported photos already in the folder.",
                    checked = state.includeExistingFolderFiles,
                    onCheckedChange = onIncludeExistingFolderFiles,
                )
                SettingSwitch(
                    title = "Watch this folder",
                    detail = "Checks periodically; Android may delay scans to save battery.",
                    checked = state.folderWatchEnabled,
                    onCheckedChange = onToggleFolderWatch,
                )
                state.folderWatchMessage?.let {
                    Text(it, fontSize = 11.sp, color = Color(0xFFBDBDBD))
                }
                Text(
                    "Requires sign-in and a selected album. Only JPG, JPEG, PNG, and WEBP are queued. If Android does not report file dates, enable Include existing photos. Folder access can be revoked in Android settings.",
                    fontSize = 10.sp,
                    color = Color(0xFFBDBDBD),
                )
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A)), border = BorderStroke(1.dp, Line)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                SectionTitle("Testing and safety")
                SettingSwitch(
                    title = "Test Mode",
                    detail = "Choose real device images with Android's photo picker; no camera is connected.",
                    checked = state.testMode,
                    onCheckedChange = onToggleTestMode,
                )
                HorizontalDivider(color = Line)
                SettingSwitch(
                    title = "Keep screen on during transfer",
                    detail = "Keep this screen awake while camera downloads or uploads are active.",
                    checked = state.keepScreenOn,
                    onCheckedChange = onToggleKeepScreenOn,
                )
                if (BuildConfig.DEBUG) {
                    HorizontalDivider(color = Line)
                    SettingSwitch(
                        title = "Allow HTTP for local development",
                        detail = "Debug build only; the app still limits HTTP to local/private hosts.",
                        checked = state.allowLocalHttp,
                        onCheckedChange = onToggleLocalHttp,
                    )
                }
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A)), border = BorderStroke(1.dp, Line)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle("Diagnostics")
                Text("Share-safe diagnostics omit passwords, tokens, authorization headers, and image data.", fontSize = 11.sp, color = Color(0xFFBDBDBD))
                OutlinedButton(
                    onClick = {
                        val intent = android.content.Intent(android.content.Intent.ACTION_SEND)
                            .setType("text/plain")
                            .putExtra(android.content.Intent.EXTRA_TEXT, diagnostics)
                        context.startActivity(android.content.Intent.createChooser(intent, "Share diagnostics"))
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Share diagnostics") }
                Text("WED Faces Camera Operator · ${BuildConfig.VERSION_NAME}", color = Muted, fontSize = 10.sp)
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    detail: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(detail, fontSize = 10.sp, color = Color(0xFFBDBDBD))
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp, color = Color(0xFFBDBDBD))
}

@Composable
private fun EmptyState(title: String, detail: String) {
    Column(
        Modifier.fillMaxWidth().padding(top = 48.dp, bottom = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(46.dp).clip(CircleShape).background(Color(0xFF242424)), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.CameraAlt, null, tint = Color(0xFFBDBDBD), modifier = Modifier.size(21.dp))
        }
        Text(title, fontWeight = FontWeight.SemiBold)
        Text(detail, modifier = Modifier.fillMaxWidth(.78f), color = Color(0xFFBDBDBD), fontSize = 12.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
private fun Notice(text: String, isError: Boolean) {
    Text(
        text = text,
        color = if (isError) Color.White else Color(0xFFBDBDBD),
        fontSize = 12.sp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp),
    )
}

private fun formatBytes(value: Long): String = when {
    value < 1024 -> "$value B"
    value < 1024 * 1024 -> "${value / 1024} KB"
    else -> String.format(java.util.Locale.ROOT, "%.1f MB", value.toDouble() / (1024 * 1024))
}
