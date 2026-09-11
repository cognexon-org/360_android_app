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
import com.propertytour360.capture.data.CaptureUploadQueue
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
import com.propertytour360.capture.util.CapturePreflight
import com.propertytour360.capture.util.PanoramaQualityEvaluator
import com.propertytour360.capture.util.ScanQualityEvaluator
import com.propertytour360.capture.util.ModeBCapturePackage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
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
            val projects = if (!token.isNullOrBlank() && baseUrl.isNotBlank()) {
                runCatching { repository.listProgressProjects(baseUrl, token) }.getOrDefault(emptyList())
            } else emptyList()
            val preflight = CapturePreflight.evaluate(getApplication())
            _state.update {
                it.copy(
                    backendUrl = baseUrl,
                    token = token,
                    progressProjects = projects,
                    preflightStatus = preflight.status,
                    preflightScore = preflight.score,
                    preflightWarnings = preflight.warnings,
                    preflightBlockers = preflight.blockers
                )
            }
        }
    }

    fun clearMessage() = _state.update { it.copy(message = null, error = null) }

    fun refreshPreflight() {
        val report = CapturePreflight.evaluate(getApplication())
        _state.update {
            it.copy(
                preflightStatus = report.status,
                preflightScore = report.score,
                preflightWarnings = report.warnings,
                preflightBlockers = report.blockers
            )
        }
    }

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
        val baseUrl = requireBaseUrl()
        val result = repository.verifyOtp(baseUrl, phone, code, name)
        preferences.setToken(result.token)
        val projects = repository.listProgressProjects(baseUrl, result.token)
        _state.update { it.copy(token = result.token, phone = phone, progressProjects = projects, message = "Signed in") }
    }

    fun logout() {
        viewModelScope.launch {
            preferences.setToken(null)
            _state.update { AppUiState(backendUrl = it.backendUrl, message = "Signed out", progressProjects = emptyList()) }
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
        val baseUrl = requireBaseUrl()
        val (property, unit) = repository.createPropertyAndUnit(
            baseUrl, token, propertyName, address, propertyType,
            unitLabel, bedrooms, bathrooms
        )
        val progressProject = repository.createProgressProject(
            baseUrl, token, unit.id, "$propertyName — $unitLabel"
        )
        val preflight = CapturePreflight.evaluate(getApplication())
        require(preflight.canStart) { preflight.blockers.joinToString(" • ") }
        val progressCapture = repository.createProgressCapture(
            baseUrl, token, progressProject, mode.apiValue,
            deviceMetadataExtra = preflight.deviceTelemetry(),
            checklist = mapOf("preflight" to preflight.reportMap())
        )
        runCatching {
            repository.submitCaptureQualityFeedback(
                baseUrl, token, progressCapture.capture.id, "PREFLIGHT", null,
                preflight.reportMap(), preflight.deviceTelemetry()
            )
        }
        val refreshedProjects = repository.listProgressProjects(baseUrl, token)
        _state.update {
            it.copy(
                workspace = CaptureWorkspace(
                    mode = mode,
                    progressProjectId = progressProject.id,
                    floorId = progressProject.floors.firstOrNull()?.id,
                    propertyId = property.id,
                    unitId = unit.id,
                    captureId = progressCapture.capture.id,
                    propertyName = property.name,
                    unitLabel = unit.label
                ),
                progressProjects = refreshedProjects,
                message = "Spatial project and capture session created"
            )
        }
    }

    fun startExistingWorkspace(projectId: String, mode: CaptureMode) = runAction {
        val token = requireToken()
        val baseUrl = requireBaseUrl()
        val project = _state.value.progressProjects.firstOrNull { it.id == projectId }
            ?: repository.listProgressProjects(baseUrl, token).firstOrNull { it.id == projectId }
            ?: error("Spatial project not found")
        val unit = project.unit ?: error("Project unit details are unavailable")
        val preflight = CapturePreflight.evaluate(getApplication())
        require(preflight.canStart) { preflight.blockers.joinToString(" • ") }
        val previousSnapshots = runCatching { repository.listProgressTimeline(baseUrl, token, project.id) }.getOrDefault(emptyList())
        val capture = repository.createProgressCapture(
            baseUrl, token, project, mode.apiValue,
            deviceMetadataExtra = preflight.deviceTelemetry(),
            checklist = mapOf("preflight" to preflight.reportMap())
        )
        runCatching {
            repository.submitCaptureQualityFeedback(
                baseUrl, token, capture.capture.id, "PREFLIGHT", null,
                preflight.reportMap(), preflight.deviceTelemetry()
            )
        }
        _state.update {
            it.copy(
                workspace = CaptureWorkspace(
                    mode = mode,
                    progressProjectId = project.id,
                    floorId = project.floors.firstOrNull()?.id,
                    propertyId = unit.propertyId,
                    unitId = project.unitId,
                    captureId = capture.capture.id,
                    propertyName = unit.property?.name ?: project.name,
                    unitLabel = unit.label
                ),
                message = "New ${mode.title} capture added to ${project.name} after ${previousSnapshots.size} previous snapshot${if (previousSnapshots.size == 1) "" else "s"}"
            )
        }
    }

    fun refreshProgressProjects() = runAction {
        val projects = repository.listProgressProjects(requireBaseUrl(), requireToken())
        _state.update { it.copy(progressProjects = projects, message = "Projects refreshed") }
    }

    fun addRoom(name: String) = runAction {
        val workspace = requireWorkspace()
        require(workspace.rooms.none { it.name.equals(name, ignoreCase = true) }) { "This room is already part of the current capture" }
        val token = requireToken()
        val baseUrl = requireBaseUrl()
        val spatialRoom = repository.createOrReuseSpatialRoom(
            baseUrl, token, workspace.progressProjectId, workspace.floorId, name, workspace.rooms.size
        )
        val room = repository.createRoom(
            baseUrl, token, workspace.captureId, name, workspace.rooms.size, spatialRoom.id
        )
        val updatedRoom = RoomDraft(
            serverId = room.id,
            name = room.name,
            spatialRoomId = spatialRoom.id,
            sortOrder = room.sortOrder
        )
        val previous = workspace.rooms.lastOrNull()
        if (previous != null) {
            repository.connectRooms(
                baseUrl, token, workspace.captureId,
                previous.serverId, updatedRoom.serverId,
                "${previous.name} to ${updatedRoom.name}"
            )
        }
        _state.update { it.copy(workspace = workspace.copy(rooms = workspace.rooms + updatedRoom), message = "$name linked to the shared spatial model") }
    }

    fun setRoomPhotos(roomId: String, result: PanoramaCaptureResult) {
        val quality = PanoramaQualityEvaluator.evaluate(result)
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
                processingStatus = "${result.pattern.title}: ${quality.status.lowercase().replace('_', ' ')} (${quality.score}/100)"
            )
        }
        val workspace = _state.value.workspace
        val room = workspace?.rooms?.firstOrNull { it.serverId == roomId }
        if (workspace != null && room?.spatialRoomId != null) {
            postQualityFeedback(workspace.captureId, room.spatialRoomId, quality.toMap())
        }
        _state.update {
            it.copy(message = if (quality.issues.isEmpty()) "Guided panorama quality passed" else quality.issues.joinToString(" • "))
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
        val currentCapture = runCatching { repository.getCapture(baseUrl, token, workspace.captureId) }.getOrNull()
        if (currentCapture?.rooms?.firstOrNull { it.id == room.serverId }?.panoramaAssetId != null) {
            updateRoom(roomId) { it.copy(processingStatus = "Panorama already processed") }
            _state.update { it.copy(uploadProgress = null, message = "${room.name} is already available") }
            return@runAction
        }
        try {
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
        } catch (network: IOException) {
            val taskId = CaptureUploadQueue.enqueueModeA(getApplication(), workspace.captureId, room)
            updateRoom(roomId) { it.copy(processingStatus = "Queued for background upload") }
            _state.update {
                it.copy(
                    uploadProgress = null,
                    message = "Network interrupted. ${room.name} is safely queued and will resume automatically ($taskId)."
                )
            }
        }
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
        val workspace = _state.value.workspace
        val spatialRoomId = workspace?.rooms?.firstOrNull { it.serverId == roomId }?.spatialRoomId
        if (workspace != null && spatialRoomId != null) {
            postQualityFeedback(
                workspace.captureId,
                spatialRoomId,
                mapOf(
                    "score" to report.score,
                    "status" to report.status,
                    "issues" to report.issues,
                    "poseCount" to report.poseCount,
                    "planeSnapshots" to report.planeSnapshots,
                    "depthFrames" to report.depthFrames,
                    "durationSeconds" to report.durationSeconds,
                    "keyframes" to report.keyframes,
                    "wallObservations" to report.wallObservations,
                    "floorObservations" to report.floorObservations,
                    "ceilingObservations" to report.ceilingObservations
                )
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

        try {
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
            ) { uploaded, total ->
                val percent = if (total > 0) (uploaded * 100 / total).toInt() else 0
                _state.update { state -> state.copy(uploadProgress = "Evidence archive: $percent%") }
            }

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
        } catch (network: IOException) {
            val taskId = CaptureUploadQueue.enqueueModeBPackage(
                getApplication(), workspace.captureId, roomId, room.name,
                File(dir, "manifest.json"), archive
            )
            updateRoom(roomId) { it.copy(processingStatus = "Evidence queued for background upload", evidenceUploaded = false) }
            _state.update {
                it.copy(uploadProgress = null, message = "Network interrupted. Evidence is staged safely and will resume automatically ($taskId).")
            }
        }
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

    private fun postQualityFeedback(captureId: String, spatialRoomId: String, report: Map<String, Any>) {
        val token = _state.value.token ?: return
        val baseUrl = _state.value.backendUrl.takeIf { it.isNotBlank() } ?: return
        viewModelScope.launch {
            runCatching {
                repository.submitCaptureQualityFeedback(
                    baseUrl, token, captureId, "ROOM", spatialRoomId, report
                )
            }
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
