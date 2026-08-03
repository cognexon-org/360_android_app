package com.propertytour360.capture.ui

import android.app.Application
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.propertytour360.capture.PropertyTourApplication
import com.propertytour360.capture.data.AppPreferences
import com.propertytour360.capture.data.FinalizePackageRoom
import com.propertytour360.capture.model.AppUiState
import com.propertytour360.capture.model.CaptureMode
import com.propertytour360.capture.model.CaptureWorkspace
import com.propertytour360.capture.model.PanoramaCaptureResult
import com.propertytour360.capture.model.RoomDraft
import com.propertytour360.capture.model.PlanPoint
import com.propertytour360.capture.model.OpeningDraft
import com.propertytour360.capture.model.MeasurementDraft
import com.propertytour360.capture.model.RoomPlacement
import com.propertytour360.capture.util.DesignModelBuilder
import com.propertytour360.capture.util.ScanQualityEvaluator
import com.propertytour360.capture.util.ModeBCapturePackage
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

    fun setRoomPhotos(roomId: String, result: PanoramaCaptureResult) {
        updateRoom(roomId) {
            it.copy(
                localPhotos = result.files,
                panoramaFile = null,
                panoramaCapturePattern = result.pattern,
                panoramaManifestFile = result.manifestFile,
                panoramaFrames = result.frames,
                panoramaHorizontalFovDegrees = result.horizontalFovDegrees,
                panoramaVerticalFovDegrees = result.verticalFovDegrees,
                panoramaMinPitchDegrees = result.minPitchDegrees,
                panoramaMaxPitchDegrees = result.maxPitchDegrees,
                processingStatus = "${result.pattern.title}: ${result.files.size} photos ready"
            )
        }
    }

    fun importPanorama(roomId: String, uri: Uri) = runAction {
        val file = copyUriToCache(uri, "panorama")
        updateRoom(roomId) {
            it.copy(
                panoramaFile = file,
                localPhotos = emptyList(),
                panoramaCapturePattern = null,
                panoramaManifestFile = null,
                panoramaFrames = emptyList(),
                panoramaHorizontalFovDegrees = null,
                panoramaVerticalFovDegrees = null,
                panoramaMinPitchDegrees = null,
                panoramaMaxPitchDegrees = null,
                processingStatus = "Panorama ready"
            )
        }
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
                    baseUrl, token, workspace.captureId, room
                ) { done, total ->
                    _state.update { it.copy(uploadProgress = "${room.name}: uploaded $done of $total") }
                }
                val job = repository.waitForJob(baseUrl, token, jobId)
                if (job.status == "FAILED") error(job.error ?: "Panorama stitching failed")
                val refreshedCapture = repository.getCapture(baseUrl, token, workspace.captureId)
                val refreshedRoom = refreshedCapture.rooms.firstOrNull { it.id == room.serverId }
                if (refreshedRoom?.panoramaAssetId == null) {
                    error("Panorama QA rejected this room. Review capture stability and recapture.")
                }
            }
            else -> error("Complete a guided room capture or import a 2:1 panorama")
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
                processingStatus = "AR scan ${report.status.lowercase().replace('_', ' ')} (${report.score}/100)",
                evidenceUploaded = false
            )
        }
        _state.update { it.copy(message = if (report.issues.isEmpty()) "AR scan quality passed" else report.issues.joinToString(" • ")) }
    }

    fun saveRoomPlan(
        roomId: String,
        polygon: List<PlanPoint>,
        heightM: Double,
        openings: List<OpeningDraft>,
        measurements: List<MeasurementDraft>,
        placement: RoomPlacement
    ) = runAction {
        require(polygon.size in 3..32) { "A room polygon must contain 3–32 vertices" }
        require(heightM in 1.8..8.0) { "Ceiling height must be between 1.8 m and 8 m" }
        require(kotlin.math.abs(polygonArea(polygon)) >= 0.5) { "Room polygon area is too small" }
        require(!hasSelfIntersection(polygon)) { "Room polygon self-intersects" }
        val workspace = requireWorkspace()
        val room = workspace.rooms.first { it.serverId == roomId }
        openings.forEach { opening ->
            require(opening.wallIndex in polygon.indices) { "Opening is attached to an invalid wall" }
            val a = polygon[opening.wallIndex]; val b = polygon[(opening.wallIndex + 1) % polygon.size]
            val wallLength = kotlin.math.hypot(b.xM - a.xM, b.zM - a.zM)
            require(opening.widthM > 0.1 && opening.offsetM >= 0.0 && opening.offsetM + opening.widthM <= wallLength + 0.001) {
                "${opening.type} does not fit on wall ${opening.wallIndex + 1}"
            }
        }
        val evidenceRef = room.arEvidenceDir?.name
        val normalizedMeasurements = measurements.map { measurement ->
            measurement.copy(
                deviceManufacturer = measurement.deviceManufacturer ?: Build.MANUFACTURER,
                deviceModel = measurement.deviceModel ?: Build.MODEL,
                evidenceRefs = if (measurement.evidenceRefs.isNotEmpty()) measurement.evidenceRefs else listOfNotNull(evidenceRef)
            )
        }
        val updated = room.copy(
            floorPolygon = polygon,
            heightM = heightM,
            openings = openings,
            measurements = normalizedMeasurements,
            placement = placement,
            lengthM = polygon.maxOf { it.xM } - polygon.minOf { it.xM },
            widthM = polygon.maxOf { it.zM } - polygon.minOf { it.zM },
            processingStatus = "Field plan confirmed (${polygon.size} vertices, ${openings.size} openings)",
            evidenceUploaded = false
        )
        val token = requireToken()
        val roomModel = DesignModelBuilder.buildRoomModel(updated)
        repository.patchRoom(
            requireBaseUrl(), token, workspace.captureId, roomId,
            mapOf(
                "ceilingHeightM" to heightM,
                "floorPolygon" to polygon.map { listOf(it.xM, it.zM) },
                "measurements" to normalizedMeasurements.map { mapOf(
                    "id" to it.id, "label" to it.label, "valueM" to it.valueM, "unit" to "m",
                    "method" to it.method, "toleranceM" to it.toleranceM,
                    "start" to it.start?.let { p -> listOf(p.xM, p.zM) },
                    "end" to it.end?.let { p -> listOf(p.xM, p.zM) },
                    "verified" to it.verified, "verificationStatus" to it.verificationStatus,
                    "operatorId" to it.operatorId, "deviceManufacturer" to it.deviceManufacturer,
                    "deviceModel" to it.deviceModel, "evidenceRefs" to it.evidenceRefs,
                    "notes" to it.notes, "capturedAtEpochMs" to it.capturedAtEpochMs
                ) },
                "openings" to openings.map { opening -> mapOf(
                    "id" to opening.id, "type" to opening.type.name, "wallIndex" to opening.wallIndex,
                    "offsetM" to opening.offsetM, "widthM" to opening.widthM, "heightM" to opening.heightM,
                    "sillM" to opening.sillM, "swing" to opening.swing, "confidence" to opening.confidence,
                    "source" to opening.source
                ) },
                "roomPlacement" to mapOf(
                    "floorId" to placement.floorId, "elevationM" to placement.elevationM,
                    "originXM" to placement.originXM, "originZM" to placement.originZM,
                    "rotationDegrees" to placement.rotationDegrees,
                    "connectionAnchorId" to placement.connectionAnchorId,
                    "connectionConfidence" to placement.connectionConfidence
                ),
                "roomModel" to roomModel
            )
        )
        updated.arEvidenceDir?.let { directory -> ModeBCapturePackage.attachFieldPlan(directory, updated) }
        updateRoom(roomId) { updated }
        _state.update { it.copy(message = "Room polygon, openings and measurements saved; evidence must be uploaded again after edits") }
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
    ) {
        val polygon = DesignModelBuilder.rectangle(lengthM, widthM)
        val openings = buildList {
            if (doorWidthM != null && doorHeightM != null) add(OpeningDraft(type = com.propertytour360.capture.model.OpeningType.DOOR, wallIndex = 0, offsetM = 0.2, widthM = doorWidthM, heightM = doorHeightM))
            if (windowWidthM != null && windowHeightM != null) add(OpeningDraft(type = com.propertytour360.capture.model.OpeningType.WINDOW, wallIndex = 1, offsetM = 0.2, widthM = windowWidthM, heightM = windowHeightM))
        }
        val measurements = listOf(
            MeasurementDraft(label = "Room length", valueM = lengthM, method = "MANUAL_OR_LASER", toleranceM = 0.01, start = polygon[0], end = polygon[1]),
            MeasurementDraft(label = "Room width", valueM = widthM, method = "MANUAL_OR_LASER", toleranceM = 0.01, start = polygon[1], end = polygon[2]),
            MeasurementDraft(label = "Ceiling height", valueM = heightM, method = "MANUAL_OR_LASER", toleranceM = 0.01)
        )
        saveRoomPlan(roomId, polygon, heightM, openings, measurements, RoomPlacement())
    }

    private fun polygonArea(points: List<PlanPoint>): Double = points.indices.sumOf { i ->
        val a = points[i]; val b = points[(i + 1) % points.size]; a.xM * b.zM - b.xM * a.zM
    } / 2.0

    private fun hasSelfIntersection(points: List<PlanPoint>): Boolean {
        fun ccw(a: PlanPoint, b: PlanPoint, c: PlanPoint) = (c.zM-a.zM)*(b.xM-a.xM) > (b.zM-a.zM)*(c.xM-a.xM)
        fun intersects(a: PlanPoint, b: PlanPoint, c: PlanPoint, d: PlanPoint) = ccw(a,c,d) != ccw(b,c,d) && ccw(a,b,c) != ccw(a,b,d)
        for (i in points.indices) for (j in i + 1 until points.size) {
            if (j == i || j == (i + 1) % points.size || i == (j + 1) % points.size) continue
            if (intersects(points[i], points[(i+1)%points.size], points[j], points[(j+1)%points.size])) return true
        }
        return false
    }

    fun uploadArEvidence(roomId: String) = runAction {
        val workspace = requireWorkspace()
        val room = workspace.rooms.first { it.serverId == roomId }
        val dir = room.arEvidenceDir ?: error("Run AR scan first")
        val token = requireToken()
        val base = requireBaseUrl()
        require(room.floorPolygon.size >= 3 && room.heightM != null) { "Confirm the field plan and ceiling height before uploading evidence" }
        ModeBCapturePackage.attachFieldPlan(dir, room)
        val checksumProblems = ModeBCapturePackage.verifyChecksums(dir)
        require(checksumProblems.isEmpty()) { checksumProblems.joinToString(" • ") }
        require(File(dir, "manifest.json").exists()) { "Capture Package v2 manifest is missing" }

        // Capture Package v2 is a SINGLE archive plus its manifest.
        //
        // The previous implementation walked the evidence directory and uploaded
        // every file individually — up to ~310 files for a 60-keyframe room
        // (rgb.jpg + metadata.json + two depth maps + confidence per keyframe).
        // Each upload is two API calls (presign + complete), so one room issued
        // ~620 requests in a burst and tripped the rate limiter with
        // "Rate limit exceeded, retry in 36 seconds".
        //
        // Every one of those files is already inside the archive, so the loop was
        // pure duplication: the same bytes were stored twice in MinIO, and the
        // backend's /v2/.../packages/finalize contract only ever consumes the
        // manifest asset and the archive asset. Uploading just those two takes
        // four requests and transfers less data, because the zip is compressed.
        _state.update { it.copy(uploadProgress = "Packaging Mode B evidence") }
        val archive = zipDirectory(dir, "${room.name.replace(' ', '_')}_capture_package_v2.zip")

        _state.update { it.copy(uploadProgress = "Uploading capture manifest") }
        val manifestAsset = repository.uploadFile(
            base, token, workspace.captureId, roomId,
            "CAPTURE_MANIFEST", File(dir, "manifest.json"), "application/json"
        )

        _state.update {
            it.copy(uploadProgress = "Uploading evidence archive (${archive.length() / (1024 * 1024)} MB)")
        }
        val archiveAsset = repository.uploadFile(
            base, token, workspace.captureId, roomId,
            "MODEL_EVIDENCE", archive, "application/zip"
        )

        // Register the pair as a capture package so the server can verify the
        // checksums inside the archive and start geometry processing.
        _state.update { it.copy(uploadProgress = "Finalizing capture package") }
        repository.finalizeCapturePackages(
            base, token, workspace.captureId,
            listOf(
                FinalizePackageRoom(
                    roomId = roomId,
                    manifestAssetId = manifestAsset.id,
                    archiveAssetId = archiveAsset.id
                )
            )
        )

        updateRoom(roomId) { it.copy(processingStatus = "Capture Package v2 uploaded", evidenceUploaded = true) }
        _state.update { it.copy(uploadProgress = null, message = "RGB-D evidence package uploaded") }
    }

    fun submitDesignScan(projectName: String) = runAction {
        val workspace = requireWorkspace()
        require(workspace.mode == CaptureMode.DESIGN_SCAN)
        require(workspace.rooms.isNotEmpty()) { "Add at least one room" }
        require(workspace.rooms.all { it.lengthM != null && it.widthM != null && it.heightM != null }) { "Confirm dimensions for every room" }
        require(workspace.rooms.filter { it.arEvidenceDir != null }.all { it.evidenceUploaded }) { "Upload the captured RGB-D evidence for every scanned room" }
        val weakScans = workspace.rooms.filter { it.arEvidenceDir != null && (it.scanQualityScore ?: 0) < 55 }
        require(weakScans.isEmpty()) { "Rescan recommended for: ${weakScans.joinToString { it.name }}" }
        val token = requireToken(); val base = requireBaseUrl()
        val submit = repository.submitCapture(base, token, workspace.captureId)
        _state.update { it.copy(uploadProgress = "Validating Mode B capture") }
        val validation = repository.waitForJob(base, token, submit.jobId)
        if (validation.status == "FAILED") error(validation.error ?: "Capture validation failed")
        val capture = repository.getCapture(base, token, workspace.captureId)
        if (capture.status != "READY") error("Design capture is ${capture.status}; review measurements and evidence")
        val model = DesignModelBuilder.buildProjectModel(workspace.rooms)
        val project = repository.createDesignProject(base, token, workspace.captureId, projectName, model)
        project.geometryJobId?.let { jobId ->
            _state.update { it.copy(uploadProgress = "Generating sensor/manual geometry proposal") }
            val geometryJob = repository.waitForJob(base, token, jobId, timeoutMs = 300_000)
            if (geometryJob.status == "FAILED") error(geometryJob.error ?: "Geometry proposal failed")
        }
        _state.update {
            it.copy(
                workspace = workspace.copy(status = "DESIGNER_REVIEW", publicUrl = "/studio/${project.id}", designProjectId = project.id),
                uploadProgress = null,
                message = "Draft model created. Open it in Designer Studio for correction and confirmation."
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
