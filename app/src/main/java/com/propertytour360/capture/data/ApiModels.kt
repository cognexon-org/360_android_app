package com.propertytour360.capture.data


data class OtpRequestBody(val phone: String)
data class OtpRequestResponse(val requested: Boolean, val expiresInSeconds: Int, val developmentOtp: String? = null)
data class OtpVerifyBody(val phone: String, val code: String, val name: String? = null)
data class AuthResponse(val token: String, val user: UserDto)
data class UserDto(val id: String, val phone: String, val name: String? = null, val role: String, val organizationId: String)

data class PropertyCreateBody(
    val name: String,
    val address: String,
    val propertyType: String,
    val metadata: Map<String, Any>? = null
)

data class PropertyDto(
    val id: String,
    val name: String,
    val address: String,
    val propertyType: String,
    val units: List<UnitDto> = emptyList()
)

data class UnitCreateBody(
    val label: String,
    val bedrooms: Int? = null,
    val bathrooms: Int? = null,
    val areaSqFt: Double? = null,
    val availability: String? = null
)

data class UnitDto(
    val id: String,
    val propertyId: String,
    val label: String,
    val bedrooms: Int? = null,
    val bathrooms: Int? = null,
    val property: PropertyDto? = null
)

data class CaptureCreateBody(
    val unitId: String,
    val mode: String,
    val platform: String = "ANDROID",
    val deviceMetadata: Map<String, Any>? = null,
    val checklist: Map<String, Any>? = null
)

data class CaptureDto(
    val id: String,
    val unitId: String,
    val mode: String,
    val platform: String,
    val status: String,
    val rooms: List<RoomDto> = emptyList(),
    val connections: List<ConnectionDto> = emptyList(),
    val assets: List<AssetDto> = emptyList(),
    val jobs: List<ProcessingJobDto> = emptyList()
)

data class RoomCreateBody(
    val name: String,
    val sortOrder: Int = 0,
    val spatialRoomId: String? = null,
    val ceilingHeightM: Double? = null,
    val floorPolygon: List<List<Double>>? = null,
    val measurements: Map<String, Any>? = null,
    val roomModel: Map<String, Any>? = null
)

data class RoomDto(
    val id: String,
    val captureId: String,
    val name: String,
    val sortOrder: Int,
    val spatialRoomId: String? = null,
    val panoramaAssetId: String? = null,
    val ceilingHeightM: Double? = null
)

data class ConnectionBody(val fromRoomId: String, val toRoomId: String, val label: String? = null)
data class ConnectionDto(val id: String, val fromRoomId: String, val toRoomId: String, val label: String? = null)

data class UploadUrlBody(
    val roomId: String? = null,
    val kind: String,
    val filename: String,
    val mimeType: String,
    val sizeBytes: Long
)

data class UploadUrlResponse(val assetId: String, val objectKey: String, val uploadUrl: String, val expiresInSeconds: Int)
data class CompleteUploadBody(val checksumSha256: String? = null)
data class AssetDto(val id: String, val kind: String, val status: String, val roomId: String? = null, val objectKey: String? = null)

data class StitchFrameBody(
    val assetId: String,
    val fileName: String,
    val targetYawDegrees: Float,
    val targetPitchDegrees: Float,
    val measuredYawDegrees: Float,
    val measuredPitchDegrees: Float,
    val measuredRollDegrees: Float,
    val capturedAtEpochMs: Long
)

data class StitchBody(
    val assetIds: List<String>,
    val capturePattern: String,
    val frames: List<StitchFrameBody>,
    val horizontalFovDegrees: Float,
    val verticalFovDegrees: Float,
    val minPitchDegrees: Float,
    val maxPitchDegrees: Float,
    val manifestAssetId: String? = null
)
data class JobResponse(val jobId: String, val status: String? = null)
data class ProcessingJobDto(val id: String, val type: String, val status: String, val error: String? = null)

data class TourCreateBody(
    val captureId: String,
    val title: String,
    val verificationLabel: String = "PANORAMA_TOUR"
)

data class TourDto(val id: String, val slug: String, val status: String, val title: String)
data class HotspotBody(
    val fromRoomId: String,
    val toRoomId: String,
    val yaw: Double,
    val pitch: Double,
    val label: String
)
data class PublishTourResponse(val publicUrl: String, val manifestUrl: String)

data class DesignProjectCreateBody(val captureId: String, val name: String, val model: Map<String, Any>? = null, val generateGeometry: Boolean = true)
data class DesignProjectDto(val id: String, val slug: String, val status: String, val name: String, val geometryJobId: String? = null, val geometryStatus: String? = null, val verificationStatus: String? = null)
data class PublishDesignResponse(val publicUrl: String)
data class FinalizePackageRoom(
    val roomId: String,
    val manifestAssetId: String,
    val archiveAssetId: String
)

data class FinalizePackagesBody(val rooms: List<FinalizePackageRoom>)

data class FinalizePackagesResponse(val jobId: String, val packageCount: Int)

// ProgressionAi spatial-temporal foundation (v2 API)
data class SpatialFloorDto(
    val id: String,
    val projectId: String,
    val name: String,
    val level: Int? = null,
    val elevationM: Double? = null
)

data class SpatialRoomDto(
    val id: String,
    val projectId: String,
    val floorId: String? = null,
    val name: String,
    val roomType: String? = null,
    val sortOrder: Int = 0
)

data class ProgressProjectCountsDto(
    val snapshots: Int = 0,
    val issues: Int = 0,
    val observations: Int = 0
)

data class ProgressProjectDto(
    val id: String,
    val unitId: String,
    val name: String,
    val status: String = "ACTIVE",
    val captureCadence: String? = null,
    val unit: UnitDto? = null,
    val floors: List<SpatialFloorDto> = emptyList(),
    val rooms: List<SpatialRoomDto> = emptyList(),
    val _count: ProgressProjectCountsDto? = null
)

data class ProgressProjectCreateBody(
    val unitId: String,
    val name: String,
    val captureCadence: String? = null,
    val defaultFloorName: String = "Ground / Default"
)

data class SpatialRoomCreateBody(
    val floorId: String? = null,
    val name: String,
    val roomType: String? = null,
    val sortOrder: Int = 0,
    val reuseByName: Boolean = true
)

data class ProgressCaptureCreateBody(
    val mode: String,
    val platform: String = "ANDROID",
    val floorId: String? = null,
    val capturedAt: String? = null,
    val deviceMetadata: Map<String, Any>? = null,
    val checklist: Map<String, Any>? = null,
    val spatialScope: Map<String, Any>? = null
)

data class CaptureSnapshotDto(
    val id: String,
    val projectId: String,
    val captureId: String,
    val floorId: String? = null,
    val capturedAt: String,
    val sourceType: String,
    val status: String
)

data class ProgressCaptureSessionDto(
    val id: String,
    val unitId: String,
    val mode: String,
    val platform: String,
    val status: String
)

data class ProgressCaptureResponse(
    val capture: ProgressCaptureSessionDto,
    val snapshot: CaptureSnapshotDto
)


// Patch 02 — resumable upload and capture-quality contracts.
data class ResumableUploadCreateBody(
    val roomId: String? = null,
    val kind: String,
    val filename: String,
    val mimeType: String,
    val sizeBytes: Long,
    val checksumSha256: String,
    val chunkSizeBytes: Int = 5 * 1024 * 1024
)

data class ResumableUploadPartDto(
    val partNumber: Int,
    val sizeBytes: Long,
    val checksumSha256: String? = null,
    val status: String,
    val completedAt: String? = null
)

data class ResumableUploadDto(
    val id: String,
    val captureId: String,
    val roomId: String? = null,
    val assetId: String,
    val filename: String,
    val mimeType: String,
    val kind: String,
    val totalSizeBytes: Long,
    val chunkSizeBytes: Int,
    val totalParts: Int,
    val checksumSha256: String? = null,
    val status: String,
    val uploadedBytes: Long = 0,
    val lastError: String? = null,
    val parts: List<ResumableUploadPartDto> = emptyList()
)

data class ResumablePartUrlResponse(
    val partNumber: Int,
    val expectedBytes: Long,
    val uploadUrl: String? = null,
    val expiresInSeconds: Int,
    val alreadyUploaded: Boolean = false
)

data class ResumablePartCompleteBody(val checksumSha256: String? = null)
data class ResumablePartCompleteResponse(
    val partNumber: Int,
    val status: String,
    val sizeBytes: Long,
    val uploadedBytes: Long? = null
)

data class ResumableUploadCompleteResponse(
    val upload: ResumableUploadDto,
    val asset: AssetDto
)

data class CaptureQualityFeedbackBody(
    val scope: String,
    val spatialRoomId: String? = null,
    val report: Map<String, Any>,
    val deviceTelemetry: Map<String, Any>? = null
)

data class CaptureQualityFeedbackResponse(
    val captureId: String,
    val qualityReport: Map<String, Any>
)
