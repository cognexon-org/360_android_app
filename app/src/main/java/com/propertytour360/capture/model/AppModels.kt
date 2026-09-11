package com.propertytour360.capture.model

import java.io.File

enum class CaptureMode(val apiValue: String, val title: String) {
    PROPERTY_TOUR("PROPERTY_TOUR", "Mode A — Property Tour"),
    DESIGN_SCAN("DESIGN_SCAN", "Mode B — Design Scan")
}

enum class PanoramaCapturePattern(
    val apiValue: String,
    val title: String,
    val shortDescription: String,
    val fullSphere: Boolean
) {
    QUICK_CENTRAL_RING(
        apiValue = "QUICK_CENTRAL_RING",
        title = "Quick Room View",
        shortDescription = "One guided rotation for a full horizontal 360° view",
        fullSphere = false
    ),
    FULL_TWO_RINGS_WITH_CAPS(
        apiValue = "FULL_TWO_RINGS_WITH_CAPS",
        title = "Full Room Sphere",
        shortDescription = "Two guided rotations plus ceiling and floor",
        fullSphere = true
    )
}

data class PanoramaFrameMetadata(
    val fileName: String,
    val targetYawDegrees: Float,
    val targetPitchDegrees: Float,
    val measuredYawDegrees: Float,
    val measuredPitchDegrees: Float,
    val measuredRollDegrees: Float,
    val angularSpeedDegreesPerSecond: Float,
    val sensorTimestampNs: Long,
    val capturedAtEpochMs: Long
)

data class PanoramaCaptureResult(
    val files: List<File>,
    val manifestFile: File,
    val pattern: PanoramaCapturePattern,
    val frames: List<PanoramaFrameMetadata>,
    val horizontalFovDegrees: Float,
    val verticalFovDegrees: Float,
    val minPitchDegrees: Float,
    val maxPitchDegrees: Float
)

data class RoomDraft(
    val serverId: String,
    val name: String,
    val spatialRoomId: String? = null,
    val sortOrder: Int,
    val localPhotos: List<File> = emptyList(),
    val panoramaFile: File? = null,
    val panoramaCapturePattern: PanoramaCapturePattern? = null,
    val panoramaManifestFile: File? = null,
    val panoramaFrames: List<PanoramaFrameMetadata> = emptyList(),
    val panoramaHorizontalFovDegrees: Float? = null,
    val panoramaVerticalFovDegrees: Float? = null,
    val panoramaMinPitchDegrees: Float? = null,
    val panoramaMaxPitchDegrees: Float? = null,
    val processingStatus: String = "Not captured",
    val lengthM: Double? = null,
    val widthM: Double? = null,
    val heightM: Double? = null,
    val floorPolygon: List<PlanPoint> = emptyList(),
    val openings: List<OpeningDraft> = emptyList(),
    val measurements: List<MeasurementDraft> = emptyList(),
    val placement: RoomPlacement = RoomPlacement(),
    val doorWidthM: Double? = null,
    val doorHeightM: Double? = null,
    val windowWidthM: Double? = null,
    val windowHeightM: Double? = null,
    val arEvidenceDir: File? = null,
    val depthSupported: Boolean = false,
    val scanQualityScore: Int? = null,
    val scanQualityStatus: String = "Not evaluated",
    val evidenceUploaded: Boolean = false
)

data class CaptureWorkspace(
    val mode: CaptureMode,
    val progressProjectId: String,
    val floorId: String? = null,
    val propertyId: String,
    val unitId: String,
    val captureId: String,
    val propertyName: String,
    val unitLabel: String,
    val rooms: List<RoomDraft> = emptyList(),
    val status: String = "CAPTURING",
    val publicUrl: String? = null,
    val designProjectId: String? = null
)

data class AppUiState(
    val backendUrl: String = "",
    val token: String? = null,
    val phone: String = "",
    val developmentOtp: String? = null,
    val busy: Boolean = false,
    val message: String? = null,
    val error: String? = null,
    val workspace: CaptureWorkspace? = null,
    val uploadProgress: String? = null,
    val preflightStatus: String = "CHECKING",
    val preflightScore: Int? = null,
    val preflightWarnings: List<String> = emptyList(),
    val preflightBlockers: List<String> = emptyList(),
    val progressProjects: List<com.propertytour360.capture.data.ProgressProjectDto> = emptyList()
)
