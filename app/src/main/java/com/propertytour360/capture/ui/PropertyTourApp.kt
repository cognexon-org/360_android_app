package com.propertytour360.capture.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.DesignServices
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.propertytour360.capture.ar.ArScanActivity
import com.propertytour360.capture.camera.PanoramaCaptureScreen
import com.propertytour360.capture.model.CaptureMode
import com.propertytour360.capture.model.RoomDraft
import java.io.File

@Composable
fun PropertyTourApp(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var cameraRoomId by remember { mutableStateOf<String?>(null) }
    var settingsOpen by remember { mutableStateOf(false) }

    LaunchedEffect(state.message, state.error) {
        val text = state.error ?: state.message
        if (!text.isNullOrBlank()) {
            snackbar.showSnackbar(text)
            viewModel.clearMessage()
        }
    }

    if (cameraRoomId != null) {
        PanoramaCaptureScreen(
            roomId = cameraRoomId!!,
            onCancel = { cameraRoomId = null },
            onComplete = { roomId, files ->
                viewModel.setRoomPhotos(roomId, files)
                cameraRoomId = null
            }
        )
        return
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.token == null -> LoginScreen(
                    backendUrl = state.backendUrl,
                    developmentOtp = state.developmentOtp,
                    busy = state.busy,
                    onSaveBackend = viewModel::saveBackendUrl,
                    onRequestOtp = viewModel::requestOtp,
                    onVerifyOtp = viewModel::verifyOtp
                )
                state.workspace == null -> DashboardScreen(
                    backendUrl = state.backendUrl,
                    busy = state.busy,
                    onSettings = { settingsOpen = true },
                    onLogout = viewModel::logout,
                    onStart = viewModel::startWorkspace
                )
                state.workspace?.mode == CaptureMode.PROPERTY_TOUR -> ModeAWorkspaceScreen(
                    state = state,
                    onBack = viewModel::resetWorkspace,
                    onAddRoom = viewModel::addRoom,
                    onCapture = { cameraRoomId = it },
                    onImportPanorama = viewModel::importPanorama,
                    onUploadRoom = viewModel::uploadModeARoom,
                    onPublish = viewModel::submitAndPublishTour
                )
                else -> ModeBWorkspaceScreen(
                    state = state,
                    onBack = viewModel::resetWorkspace,
                    onAddRoom = viewModel::addRoom,
                    onCaptureReference = { cameraRoomId = it },
                    onImportPanorama = viewModel::importPanorama,
                    onUploadReference = viewModel::uploadModeARoom,
                    onSetArEvidence = viewModel::setArEvidence,
                    onSaveMeasurements = viewModel::saveMeasurements,
                    onUploadEvidence = viewModel::uploadArEvidence,
                    onPublish = viewModel::submitDesignScan
                )
            }

            if (state.busy) {
                Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Card {
                        Column(
                            Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator()
                            state.uploadProgress?.let {
                                Spacer(Modifier.height(12.dp))
                                Text(it)
                            }
                        }
                    }
                }
            }
        }
    }

    if (settingsOpen) {
        BackendSettingsDialog(
            current = state.backendUrl,
            onDismiss = { settingsOpen = false },
            onSave = {
                viewModel.saveBackendUrl(it)
                settingsOpen = false
            }
        )
    }
}

@Composable
private fun LoginScreen(
    backendUrl: String,
    developmentOtp: String?,
    busy: Boolean,
    onSaveBackend: (String) -> Unit,
    onRequestOtp: (String) -> Unit,
    onVerifyOtp: (String, String, String?) -> Unit
) {
    var server by remember(backendUrl) { mutableStateOf(backendUrl) }
    var phone by remember { mutableStateOf("+919999999999") }
    var otp by remember(developmentOtp) { mutableStateOf(developmentOtp ?: "") }
    var name by remember { mutableStateOf("Demo Owner") }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp).imePadding(),
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Default.Home, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text("PropertyTour360", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Text("Android capture app — Property Tour and Design Scan")
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(server, { server = it }, label = { Text("Backend URL") }, modifier = Modifier.fillMaxWidth())
        Text("Emulator: http://10.0.2.2:3000/ • Physical phone: use your computer's LAN IP", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { onSaveBackend(server) }, modifier = Modifier.fillMaxWidth()) { Text("Save server") }
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(phone, { phone = it }, label = { Text("Phone number") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(name, { name = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Button(onClick = { onRequestOtp(phone) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Request OTP") }
        developmentOtp?.let {
            Text("Development OTP: $it", color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold)
        }
        OutlinedTextField(otp, { otp = it }, label = { Text("OTP") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Button(onClick = { onVerifyOtp(phone, otp, name.ifBlank { null }) }, enabled = otp.length == 6 && !busy, modifier = Modifier.fillMaxWidth()) { Text("Sign in") }
    }
}

@Composable
private fun DashboardScreen(
    backendUrl: String,
    busy: Boolean,
    onSettings: () -> Unit,
    onLogout: () -> Unit,
    onStart: (CaptureMode, String, String, String, String, Int?, Int?) -> Unit
) {
    var mode by remember { mutableStateOf<CaptureMode?>(null) }
    var propertyName by remember { mutableStateOf("Demo Property") }
    var address by remember { mutableStateOf("Bengaluru") }
    var type by remember { mutableStateOf("Apartment") }
    var unit by remember { mutableStateOf("Unit 101") }
    var bedrooms by remember { mutableStateOf("1") }
    var bathrooms by remember { mutableStateOf("1") }

    LazyColumn(
        Modifier.fillMaxSize().navigationBarsPadding(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("New capture", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text(backendUrl, style = MaterialTheme.typography.bodySmall)
                }
                Row {
                    IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Settings") }
                    IconButton(onClick = onLogout) { Icon(Icons.Default.Logout, "Logout") }
                }
            }
        }
        item {
            ModeCard(
                title = "Mode A — Property Tour",
                subtitle = "Capture connected room panoramas, room names and doorway links.",
                icon = { Icon(Icons.Default.Map, null) },
                selected = mode == CaptureMode.PROPERTY_TOUR,
                onClick = { mode = CaptureMode.PROPERTY_TOUR }
            )
        }
        item {
            ModeCard(
                title = "Mode B — Design Scan",
                subtitle = "Record ARCore poses/depth where available, then confirm dimensions for an editable room shell.",
                icon = { Icon(Icons.Default.DesignServices, null) },
                selected = mode == CaptureMode.DESIGN_SCAN,
                onClick = { mode = CaptureMode.DESIGN_SCAN }
            )
        }
        item {
            Text("Property and unit", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            OutlinedTextField(propertyName, { propertyName = it }, label = { Text("Property name") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(address, { address = it }, label = { Text("Address") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(type, { type = it }, label = { Text("Property type") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(unit, { unit = it }, label = { Text("Unit / room label") }, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(bedrooms, { bedrooms = it }, label = { Text("Beds") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                OutlinedTextField(bathrooms, { bathrooms = it }, label = { Text("Baths") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    onStart(mode!!, propertyName, address, type, unit, bedrooms.toIntOrNull(), bathrooms.toIntOrNull())
                },
                enabled = mode != null && propertyName.isNotBlank() && address.isNotBlank() && unit.isNotBlank() && !busy,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Create capture session") }
        }
    }
}

@Composable
private fun ModeCard(
    title: String,
    subtitle: String,
    icon: @Composable () -> Unit,
    selected: Boolean,
    onClick: () -> Unit
) {
    Card(onClick = onClick) {
        Row(Modifier.fillMaxWidth().padding(18.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            icon()
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(subtitle)
            }
            if (selected) Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModeAWorkspaceScreen(
    state: com.propertytour360.capture.model.AppUiState,
    onBack: () -> Unit,
    onAddRoom: (String) -> Unit,
    onCapture: (String) -> Unit,
    onImportPanorama: (String, Uri) -> Unit,
    onUploadRoom: (String) -> Unit,
    onPublish: (String) -> Unit
) {
    val workspace = state.workspace ?: return
    var roomName by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("${workspace.propertyName} — ${workspace.unitLabel}") }
    var importRoomId by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val roomId = importRoomId
        if (uri != null && roomId != null) onImportPanorama(roomId, uri)
        importRoomId = null
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Property Tour") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") } }
        )
    }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(workspace.propertyName, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("Stand near the room centre. Capture 12 overlapping directions or import a valid 2:1 panorama.")
            }
            items(workspace.rooms, key = { it.serverId }) { room ->
                RoomTourCard(
                    room = room,
                    onCapture = { onCapture(room.serverId) },
                    onImport = {
                        importRoomId = room.serverId
                        picker.launch("image/jpeg")
                    },
                    onUpload = { onUploadRoom(room.serverId) }
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(roomName, { roomName = it }, label = { Text("Next room name") }, modifier = Modifier.weight(1f))
                    Button(onClick = { onAddRoom(roomName); roomName = "" }, enabled = roomName.isNotBlank()) { Text("Add") }
                }
            }
            if (workspace.rooms.isNotEmpty()) {
                item {
                    OutlinedTextField(title, { title = it }, label = { Text("Public tour title") }, modifier = Modifier.fillMaxWidth())
                    Button(onClick = { onPublish(title) }, enabled = workspace.rooms.all { it.processingStatus.contains("approved", true) || it.processingStatus.contains("uploaded", true) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Validate and publish connected tour")
                    }
                    workspace.publicUrl?.let { Text("Published: $it", color = MaterialTheme.colorScheme.primary) }
                }
            }
        }
    }
}

@Composable
private fun RoomTourCard(room: RoomDraft, onCapture: () -> Unit, onImport: () -> Unit, onUpload: () -> Unit) {
    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(room.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(room.processingStatus)
            if (room.localPhotos.isNotEmpty()) Text("${room.localPhotos.size} overlapping photos")
            if (room.panoramaFile != null) Text("Imported panorama: ${room.panoramaFile.name}")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onCapture, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.CameraAlt, null); Text(" Capture")
                }
                FilledTonalButton(onClick = onImport, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Image, null); Text(" Import")
                }
            }
            Button(onClick = onUpload, enabled = room.localPhotos.size >= 3 || room.panoramaFile != null, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.CloudUpload, null); Text(" Upload and process")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModeBWorkspaceScreen(
    state: com.propertytour360.capture.model.AppUiState,
    onBack: () -> Unit,
    onAddRoom: (String) -> Unit,
    onCaptureReference: (String) -> Unit,
    onImportPanorama: (String, Uri) -> Unit,
    onUploadReference: (String) -> Unit,
    onSetArEvidence: (String, File, Boolean) -> Unit,
    onSaveMeasurements: (String, Double, Double, Double, Double?, Double?, Double?, Double?) -> Unit,
    onUploadEvidence: (String) -> Unit,
    onPublish: (String) -> Unit
) {
    val workspace = state.workspace ?: return
    val context = LocalContext.current
    var roomName by remember { mutableStateOf("") }
    var measurementRoom by remember { mutableStateOf<RoomDraft?>(null) }
    var arRoomId by remember { mutableStateOf<String?>(null) }
    var importRoomId by remember { mutableStateOf<String?>(null) }
    var projectName by remember { mutableStateOf("${workspace.propertyName} design shell") }

    val arLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val roomId = arRoomId
        val dirPath = result.data?.getStringExtra(ArScanActivity.EXTRA_OUTPUT_DIR)
        val depth = result.data?.getBooleanExtra(ArScanActivity.EXTRA_DEPTH_SUPPORTED, false) ?: false
        if (roomId != null && dirPath != null) onSetArEvidence(roomId, File(dirPath), depth)
        arRoomId = null
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val roomId = importRoomId
        if (uri != null && roomId != null) onImportPanorama(roomId, uri)
        importRoomId = null
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Design Scan") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") } }
        )
    }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text("ARCore is optional", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("Use AR scan when supported, then confirm wall lengths and ceiling height. Manual/laser dimensions remain the source of truth.")
            }
            items(workspace.rooms, key = { it.serverId }) { room ->
                Card {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(room.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(room.processingStatus)
                        Text(if (room.depthSupported) "Raw Depth supported" else "Depth unavailable or not scanned")
                        room.lengthM?.let { Text("Confirmed shell: ${room.lengthM}m × ${room.widthM}m × ${room.heightM}m") }
                        Button(
                            onClick = {
                                arRoomId = room.serverId
                                val intent = Intent(context, ArScanActivity::class.java)
                                    .putExtra(ArScanActivity.EXTRA_ROOM_NAME, room.name)
                                arLauncher.launch(intent)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Run ARCore pose/depth scan") }
                        FilledTonalButton(onClick = { measurementRoom = room }, modifier = Modifier.fillMaxWidth()) {
                            Text("Confirm room measurements")
                        }
                        if (room.arEvidenceDir != null) {
                            OutlinedButton(onClick = { onUploadEvidence(room.serverId) }, modifier = Modifier.fillMaxWidth()) {
                                Text("Upload AR evidence")
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { onCaptureReference(room.serverId) }, modifier = Modifier.weight(1f)) { Text("Reference photos") }
                            OutlinedButton(onClick = { importRoomId = room.serverId; picker.launch("image/jpeg") }, modifier = Modifier.weight(1f)) { Text("Import pano") }
                        }
                        if (room.localPhotos.size >= 3 || room.panoramaFile != null) {
                            OutlinedButton(onClick = { onUploadReference(room.serverId) }, modifier = Modifier.fillMaxWidth()) { Text("Upload visual reference") }
                        }
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(roomName, { roomName = it }, label = { Text("Room name") }, modifier = Modifier.weight(1f))
                    Button(onClick = { onAddRoom(roomName); roomName = "" }, enabled = roomName.isNotBlank()) { Text("Add") }
                }
            }
            if (workspace.rooms.isNotEmpty()) {
                item {
                    OutlinedTextField(projectName, { projectName = it }, label = { Text("Design project name") }, modifier = Modifier.fillMaxWidth())
                    Button(
                        onClick = { onPublish(projectName) },
                        enabled = workspace.rooms.all { it.lengthM != null && it.widthM != null && it.heightM != null },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Generate and publish editable room shell") }
                    workspace.publicUrl?.let { Text("Published: $it", color = MaterialTheme.colorScheme.primary) }
                }
            }
        }
    }

    measurementRoom?.let { room ->
        MeasurementsDialog(
            room = room,
            onDismiss = { measurementRoom = null },
            onSave = { l, w, h, dw, dh, ww, wh ->
                onSaveMeasurements(room.serverId, l, w, h, dw, dh, ww, wh)
                measurementRoom = null
            }
        )
    }
}

@Composable
private fun MeasurementsDialog(
    room: RoomDraft,
    onDismiss: () -> Unit,
    onSave: (Double, Double, Double, Double?, Double?, Double?, Double?) -> Unit
) {
    var length by remember { mutableStateOf(room.lengthM?.toString() ?: "4.0") }
    var width by remember { mutableStateOf(room.widthM?.toString() ?: "3.0") }
    var height by remember { mutableStateOf(room.heightM?.toString() ?: "2.8") }
    var doorWidth by remember { mutableStateOf(room.doorWidthM?.toString() ?: "0.9") }
    var doorHeight by remember { mutableStateOf(room.doorHeightM?.toString() ?: "2.1") }
    var windowWidth by remember { mutableStateOf(room.windowWidthM?.toString() ?: "1.2") }
    var windowHeight by remember { mutableStateOf(room.windowHeightM?.toString() ?: "1.2") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Confirm ${room.name}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Use a tape or low-cost laser meter for important dimensions.")
                NumberField("Length (m)", length) { length = it }
                NumberField("Width (m)", width) { width = it }
                NumberField("Ceiling height (m)", height) { height = it }
                NumberField("Door width (m)", doorWidth) { doorWidth = it }
                NumberField("Door height (m)", doorHeight) { doorHeight = it }
                NumberField("Window width (m)", windowWidth) { windowWidth = it }
                NumberField("Window height (m)", windowHeight) { windowHeight = it }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        length.toDouble(), width.toDouble(), height.toDouble(),
                        doorWidth.toDoubleOrNull(), doorHeight.toDoubleOrNull(),
                        windowWidth.toDoubleOrNull(), windowHeight.toDoubleOrNull()
                    )
                },
                enabled = length.toDoubleOrNull() != null && width.toDoubleOrNull() != null && height.toDoubleOrNull() != null
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun NumberField(label: String, value: String, onValue: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun BackendSettingsDialog(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var value by remember(current) { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Backend settings") },
        text = {
            Column {
                OutlinedTextField(value, { value = it }, label = { Text("Node API base URL") }, modifier = Modifier.fillMaxWidth())
                Text("Use HTTPS in production. Cleartext HTTP is enabled only for local development.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(value) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
