package com.propertytour360.capture.ui

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.propertytour360.capture.PropertyTourApplication
import com.propertytour360.capture.data.AppPreferences
import com.propertytour360.capture.model.AppUiState
import com.propertytour360.capture.model.CaptureMode
import com.propertytour360.capture.model.CaptureWorkspace
import com.propertytour360.capture.model.RoomDraft
import com.propertytour360.capture.util.DesignModelBuilder
import com.propertytour360.capture.util.ScanQualityEvaluator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as PropertyTourApplication
    private val preferences = app.container.preferences
    private val repository = app.container.backendRepository

    private val _state = MutableStateFlow(AppUiState())
    val state: StateFlow<AppUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val baseUrl = preferences.backendUrl.first()
            val token = preferences.token.first()
            _state.update { it.copy(backendUrl = baseUrl, token = token) }
        }
    }

    fun clearMessage() = _state.update { it.copy(message = null, error = null) }

    fun saveBackendUrl(value: String) {
        viewModelScope.launch {
            val normalized = AppPreferences.normalizeBaseUrl(value)
            preferences.setBackendUrl(normalized)
            _state.update { it.copy(backendUrl = normalized, message = "Backend URL saved") }
        }
    }

    fun requestOtp(phone: String) = runAction {
        val result = repository.requestOtp(requireBaseUrl(), phone)
        _state.update { it.copy(phone = phone, developmentOtp = result.developmentOtp, message = "OTP requested") }
    }

    fun verifyOtp(phone: String, code: String, name: String?) = runAction {
        val result = repository.verifyOtp(requireBaseUrl(), phone, code, name)
        preferences.setToken(result.token)
        _state.update { it.copy(token = result.token, phone = phone, message = "Signed in") }
    }

    fun logout() {
        viewModelScope.launch {
            preferences.setToken(null)
            _state.update { AppUiState(backendUrl = it.backendUrl, message = "Signed out") }
        }
    }

    fun startWorkspace(
        mode: CaptureMode,
        propertyName: String,
        address: String,
        propertyType: String,
        unitLabel: String,
        bedrooms: Int?,
        bathrooms: Int?
    ) = runAction {
        val token = requireToken()
        val (property, unit) = repository.createPropertyAndUnit(
            requireBaseUrl(), token, propertyName, address, propertyType,
            unitLabel, bedrooms, bathrooms
        )
        val capture = repository.createCapture(requireBaseUrl(), token, unit.id, mode.apiValue)
        _state.update {
            it.copy(
                workspace = CaptureWorkspace(
                    mode = mode,
                    propertyId = property.id,
                    unitId = unit.id,
                    captureId = capture.id,
                    propertyName = property.name,
                    unitLabel = unit.label
                ),
                message = "Capture session created"
            )
        }
    }

    fun addRoom(name: String) = runAction {
        val workspace = requireWorkspace()
        val token = requireToken()
        val room = repository.createRoom(
            requireBaseUrl(), token, workspace.captureId, name, workspace.rooms.size
        )
        val updatedRoom = RoomDraft(room.id, room.name, room.sortOrder)
        val previous = workspace.rooms.lastOrNull()
        if (previous != null) {
            repository.connectRooms(
                requireBaseUrl(), token, workspace.captureId,
                previous.serverId, updatedRoom.serverId,
                "${previous.name} to ${updatedRoom.name}"
            )
        }
        _state.update { it.copy(workspace = workspace.copy(rooms = workspace.rooms + updatedRoom), message = "$name added") }
    }

    fun setRoomPhotos(roomId: String, files: List<File>) {
        updateRoom(roomId) { it.copy(localPhotos = files, panoramaFile = null, processingStatus = "${files.size} photos ready") }
    }

    fun importPanorama(roomId: String, uri: Uri) = runAction {
        val file = copyUriToCache(uri, "panorama")
        updateRoom(roomId) { it.copy(panoramaFile = file, localPhotos = emptyList(), processingStatus = "Panorama ready") }
        _state.update { it.copy(message = "Panorama imported") }
    }

    fun uploadModeARoom(roomId: String) = runAction {
        val workspace = requireWorkspace()
        val room = workspace.rooms.first { it.serverId == roomId }
        val token = requireToken()
        val baseUrl = requireBaseUrl()
        when {
            room.panoramaFile != null -> {
                _state.update { it.copy(uploadProgress = "Uploading ${room.name} panorama") }
                val uploaded = repository.uploadPanorama(baseUrl, token, workspace.captureId, room.serverId, room.panoramaFile, "image/jpeg")
                repository.waitForAsset(baseUrl, token, workspace.captureId, uploaded.id)
            }
            room.localPhotos.size >= 3 -> {
                val jobId = repository.uploadRoomPhotosAndStitch(
                    baseUrl, token, workspace.captureId, room.serverId, room.localPhotos
                ) { done, total ->
                    _state.update { it.copy(uploadProgress = "${room.name}: uploaded $done of $total") }
                }
                val job = repository.waitForJob(baseUrl, token, jobId)
                if (job.status == "FAILED") error(job.error ?: "Panorama stitching failed")
            }
            else -> error("Capture at least 3 overlapping photos or import a 2:1 panorama")
        }
        updateRoom(roomId) { it.copy(processingStatus = "Panorama approved/uploaded") }
        _state.update { it.copy(uploadProgress = null, message = "${room.name} processed") }
    }

    fun submitAndPublishTour(title: String) = runAction {
        val workspace = requireWorkspace()
        require(workspace.mode == CaptureMode.PROPERTY_TOUR)
        val token = requireToken()
        val baseUrl = requireBaseUrl()
        val submit = repository.submitCapture(baseUrl, token, workspace.captureId)
        _state.update { it.copy(uploadProgress = "Validating capture") }
        val validation = repository.waitForJob(baseUrl, token, submit.jobId)
        if (validation.status == "FAILED") error(validation.error ?: "Capture validation failed")
        val capture = repository.getCapture(baseUrl, token, workspace.captureId)
        if (capture.status != "READY") error("Capture is ${capture.status}; review backend job details")
        val published = repository.createAndPublishTour(baseUrl, token, workspace.captureId, title, workspace.rooms)
        _state.update {
            it.copy(
                workspace = workspace.copy(status = "PUBLISHED", publicUrl = published.manifestUrl),
                uploadProgress = null,
                message = "Tour published"
            )
        }
    }

    fun setArEvidence(roomId: String, directory: File, depthSupported: Boolean) {
        val report = ScanQualityEvaluator.evaluate(directory)
        updateRoom(roomId) {
            it.copy(
                arEvidenceDir = directory,
                depthSupported = depthSupported,
                scanQualityScore = report.score,
                scanQualityStatus = report.status,
                processingStatus = "AR scan ${report.status.lowercase().replace('_', ' ')} (${report.score}/100)"
            )
        }
        _state.update { it.copy(message = if (report.issues.isEmpty()) "AR scan quality passed" else report.issues.joinToString(" • ")) }
    }

    fun saveMeasurements(
        roomId: String,
        lengthM: Double,
        widthM: Double,
        heightM: Double,
        doorWidthM: Double?,
        doorHeightM: Double?,
        windowWidthM: Double?,
        windowHeightM: Double?
    ) = runAction {
        require(lengthM in 0.5..30.0 && widthM in 0.5..30.0) { "Room length and width must be between 0.5 m and 30 m" }
        require(heightM in 1.8..8.0) { "Ceiling height must be between 1.8 m and 8 m" }
        require(doorWidthM == null || doorWidthM in 0.45..3.5) { "Door width is outside a plausible range" }
        require(doorHeightM == null || doorHeightM in 1.5..4.0) { "Door height is outside a plausible range" }
        require(windowWidthM == null || windowWidthM in 0.2..8.0) { "Window width is outside a plausible range" }
        require(windowHeightM == null || windowHeightM in 0.2..4.0) { "Window height is outside a plausible range" }
        val workspace = requireWorkspace()
        val token = requireToken()
        val floor = listOf(
            listOf(0.0, 0.0), listOf(lengthM, 0.0),
            listOf(lengthM, widthM), listOf(0.0, widthM)
        )
        val measurements = mapOf(
            "lengthM" to lengthM,
            "widthM" to widthM,
            "confirmedByUser" to true,
            "measurementMethod" to "manual_or_laser"
        )
        val roomModel = DesignModelBuilder.buildRoomModel(
            roomId, workspace.rooms.first { it.serverId == roomId }.name,
            lengthM, widthM, heightM,
            doorWidthM, doorHeightM, windowWidthM, windowHeightM
        )
        repository.patchRoom(
            requireBaseUrl(), token, workspace.captureId, roomId,
            mapOf(
                "ceilingHeightM" to heightM,
                "floorPolygon" to floor,
                "measurements" to measurements,
                "roomModel" to roomModel
            )
        )
        updateRoom(roomId) {
            it.copy(
                lengthM = lengthM, widthM = widthM, heightM = heightM,
                doorWidthM = doorWidthM, doorHeightM = doorHeightM,
                windowWidthM = windowWidthM, windowHeightM = windowHeightM,
                processingStatus = "Measurements confirmed"
            )
        }
        _state.update { it.copy(message = "Measurements saved") }
    }

    fun uploadArEvidence(roomId: String) = runAction {
        val workspace = requireWorkspace()
        val room = workspace.rooms.first { it.serverId == roomId }
        val dir = room.arEvidenceDir ?: error("Run AR scan first")
        val token = requireToken()
        val base = requireBaseUrl()
        val files = dir.walkTopDown().filter { it.isFile }.toList()
        val poses = files.firstOrNull { it.name == "poses.jsonl" }
        val intrinsics = files.firstOrNull { it.name == "intrinsics.json" }
        poses?.let { repository.uploadFile(base, token, workspace.captureId, roomId, "AR_POSES", it, "application/x-ndjson") }
        intrinsics?.let { repository.uploadFile(base, token, workspace.captureId, roomId, "CAMERA_INTRINSICS", it, "application/json") }
        val archive = zipDirectory(dir, "${room.name.replace(' ', '_')}_ar_evidence.zip")
        repository.uploadFile(base, token, workspace.captureId, roomId, "OTHER", archive, "application/zip")
        updateRoom(roomId) { it.copy(processingStatus = "AR evidence uploaded") }
        _state.update { it.copy(message = "AR evidence uploaded") }
    }

    fun submitDesignScan(projectName: String) = runAction {
        val workspace = requireWorkspace()
        require(workspace.mode == CaptureMode.DESIGN_SCAN)
        require(workspace.rooms.isNotEmpty()) { "Add at least one room" }
        require(workspace.rooms.all { it.lengthM != null && it.widthM != null && it.heightM != null }) {
            "Confirm dimensions for every room"
        }
        val weakScans = workspace.rooms.filter { it.arEvidenceDir != null && (it.scanQualityScore ?: 0) < 55 }
        require(weakScans.isEmpty()) {
            "Rescan recommended for: ${weakScans.joinToString { it.name }}. Alternatively remove the weak AR evidence and use the measured manual path."
        }
        val token = requireToken()
        val base = requireBaseUrl()
        val submit = repository.submitCapture(base, token, workspace.captureId)
        val validation = repository.waitForJob(base, token, submit.jobId)
        if (validation.status == "FAILED") error(validation.error ?: "Capture validation failed")
        val capture = repository.getCapture(base, token, workspace.captureId)
        if (capture.status != "READY") error("Design capture is ${capture.status}; review room measurements and connectivity")
        val model = DesignModelBuilder.buildProjectModel(workspace.rooms)
        val (project, shellJobId) = repository.createDesignProject(base, token, workspace.captureId, projectName, model)
        _state.update { it.copy(uploadProgress = "Generating editable GLB room shell") }
        val shellJob = repository.waitForJob(base, token, shellJobId, timeoutMs = 300_000)
        if (shellJob.status == "FAILED") error(shellJob.error ?: "Room shell generation failed")
        val published = repository.publishDesign(base, token, project.id)
        _state.update {
            it.copy(
                workspace = workspace.copy(
                    status = "PUBLISHED",
                    publicUrl = published.publicUrl,
                    designProjectId = project.id
                ),
                uploadProgress = null,
                message = "Design concept shell published"
            )
        }
    }

    fun resetWorkspace() = _state.update { it.copy(workspace = null, uploadProgress = null) }

    private fun updateRoom(roomId: String, transform: (RoomDraft) -> RoomDraft) {
        _state.update { current ->
            val workspace = current.workspace ?: return@update current
            current.copy(workspace = workspace.copy(rooms = workspace.rooms.map { if (it.serverId == roomId) transform(it) else it }))
        }
    }

    private fun copyUriToCache(uri: Uri, prefix: String): File {
        val resolver = getApplication<Application>().contentResolver
        var displayName = "$prefix-${System.currentTimeMillis()}.jpg"
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) displayName = cursor.getString(0) ?: displayName
        }
        val output = File(getApplication<Application>().cacheDir, "imports/${System.currentTimeMillis()}-$displayName")
        output.parentFile?.mkdirs()
        resolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Unable to open selected file" }
            output.outputStream().use { input.copyTo(it) }
        }
        return output
    }

    private fun zipDirectory(directory: File, filename: String): File {
        val output = File(getApplication<Application>().cacheDir, "evidence/$filename")
        output.parentFile?.mkdirs()
        ZipOutputStream(output.outputStream().buffered()).use { zip ->
            directory.walkTopDown().filter { it.isFile }.forEach { file ->
                val relative = file.relativeTo(directory).invariantSeparatorsPath
                zip.putNextEntry(ZipEntry(relative))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return output
    }

    private fun requireBaseUrl() = _state.value.backendUrl.ifBlank { error("Set backend URL") }
    private fun requireToken() = _state.value.token ?: error("Sign in first")
    private fun requireWorkspace() = _state.value.workspace ?: error("Create a capture session first")

    private fun runAction(block: suspend () -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            try {
                block()
            } catch (throwable: Throwable) {
                _state.update { it.copy(error = throwable.message ?: throwable.javaClass.simpleName, uploadProgress = null) }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }
}
