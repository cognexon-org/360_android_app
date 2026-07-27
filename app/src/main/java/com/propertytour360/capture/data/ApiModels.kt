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
    val bathrooms: Int? = null
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

data class StitchBody(val assetIds: List<String>)
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

data class DesignProjectCreateBody(val captureId: String, val name: String, val model: Map<String, Any>)
data class DesignProjectDto(val id: String, val slug: String, val status: String, val name: String)
data class PublishDesignResponse(val publicUrl: String)