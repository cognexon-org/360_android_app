package com.propertytour360.capture.model

import java.io.File

enum class CaptureMode(val apiValue: String, val title: String) {
    PROPERTY_TOUR("PROPERTY_TOUR", "Mode A — Property Tour"),
    DESIGN_SCAN("DESIGN_SCAN", "Mode B — Design Scan")
}

data class RoomDraft(
    val serverId: String,
    val name: String,
    val sortOrder: Int,
    val localPhotos: List<File> = emptyList(),
    val panoramaFile: File? = null,
    val processingStatus: String = "Not captured",
    val lengthM: Double? = null,
    val widthM: Double? = null,
    val heightM: Double? = null,
    val doorWidthM: Double? = null,
    val doorHeightM: Double? = null,
    val windowWidthM: Double? = null,
    val windowHeightM: Double? = null,
    val arEvidenceDir: File? = null,
    val depthSupported: Boolean = false,
    val scanQualityScore: Int? = null,
    val scanQualityStatus: String = "Not evaluated"
)

data class CaptureWorkspace(
    val mode: CaptureMode,
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
    val uploadProgress: String? = null
)
