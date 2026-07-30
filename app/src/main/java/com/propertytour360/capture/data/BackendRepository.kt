package com.propertytour360.capture.data

import android.os.Build
import com.propertytour360.capture.model.RoomDraft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.security.MessageDigest

class BackendRepository(
    private val serviceProvider: (String) -> ApiService,
    private val uploadClient: OkHttpClient
) {
    private fun bearer(token: String) = "Bearer $token"

    suspend fun requestOtp(baseUrl: String, phone: String) = serviceProvider(baseUrl).requestOtp(OtpRequestBody(phone))

    suspend fun verifyOtp(baseUrl: String, phone: String, code: String, name: String?) =
        serviceProvider(baseUrl).verifyOtp(OtpVerifyBody(phone, code, name))

    suspend fun createPropertyAndUnit(
        baseUrl: String,
        token: String,
        propertyName: String,
        address: String,
        propertyType: String,
        unitLabel: String,
        bedrooms: Int?,
        bathrooms: Int?
    ): Pair<PropertyDto, UnitDto> {
        val api = serviceProvider(baseUrl)
        val property = api.createProperty(bearer(token), PropertyCreateBody(propertyName, address, propertyType))
        val unit = api.createUnit(bearer(token), property.id, UnitCreateBody(unitLabel, bedrooms, bathrooms))
        return property to unit
    }

    suspend fun createCapture(baseUrl: String, token: String, unitId: String, mode: String): CaptureDto {
        val metadata = mapOf(
            "manufacturer" to Build.MANUFACTURER,
            "model" to Build.MODEL,
            "sdk" to Build.VERSION.SDK_INT,
            "appVersion" to "2.1.0"
        )
        return serviceProvider(baseUrl).createCapture(
            bearer(token),
            CaptureCreateBody(unitId, mode, deviceMetadata = metadata)
        )
    }

    suspend fun createRoom(baseUrl: String, token: String, captureId: String, name: String, sortOrder: Int) =
        serviceProvider(baseUrl).createRoom(bearer(token), captureId, RoomCreateBody(name, sortOrder))

    suspend fun connectRooms(baseUrl: String, token: String, captureId: String, fromRoomId: String, toRoomId: String, label: String) =
        serviceProvider(baseUrl).connectRooms(bearer(token), captureId, ConnectionBody(fromRoomId, toRoomId, label))

    suspend fun patchRoom(baseUrl: String, token: String, captureId: String, roomId: String, body: Map<String, Any?>) =
        serviceProvider(baseUrl).patchRoom(bearer(token), captureId, roomId, body)

    suspend fun uploadFile(
        baseUrl: String,
        token: String,
        captureId: String,
        roomId: String?,
        kind: String,
        file: File,
        mimeType: String
    ): AssetDto = withContext(Dispatchers.IO) {
        val api = serviceProvider(baseUrl)
        val upload = api.requestUploadUrl(
            bearer(token), captureId,
            UploadUrlBody(roomId, kind, file.name, mimeType, file.length())
        )
        val request = Request.Builder()
            .url(upload.uploadUrl)
            .put(file.asRequestBody(mimeType.toMediaTypeOrNull()))
            .build()
        uploadClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Upload failed: HTTP ${response.code}")
        }
        api.completeUpload(bearer(token), captureId, upload.assetId, CompleteUploadBody(sha256(file)))
    }

    suspend fun uploadRoomPhotosAndStitch(
        baseUrl: String,
        token: String,
        captureId: String,
        room: RoomDraft,
        progress: (Int, Int) -> Unit
    ): String {
        val pattern = room.panoramaCapturePattern
            ?: error("Capture pattern is missing. Recapture this room with the updated guided flow.")
        require(room.panoramaFrames.size == room.localPhotos.size) {
            "Capture metadata does not match the photo count. Please recapture the room."
        }

        val uploadedByFileName = linkedMapOf<String, AssetDto>()
        room.localPhotos.forEachIndexed { index, file ->
            val response = uploadFile(baseUrl, token, captureId, room.serverId, "PHOTO", file, "image/jpeg")
            uploadedByFileName[file.name] = response
            progress(index + 1, room.localPhotos.size)
        }

        val manifestAssetId = room.panoramaManifestFile?.takeIf { it.exists() }?.let { manifest ->
            uploadFile(
                baseUrl,
                token,
                captureId,
                room.serverId,
                "OTHER",
                manifest,
                "application/json"
            ).id
        }

        val frames = room.panoramaFrames.map { metadata ->
            val asset = uploadedByFileName[metadata.fileName]
                ?: error("Uploaded asset missing for ${metadata.fileName}")
            StitchFrameBody(
                assetId = asset.id,
                fileName = metadata.fileName,
                targetYawDegrees = metadata.targetYawDegrees,
                targetPitchDegrees = metadata.targetPitchDegrees,
                measuredYawDegrees = metadata.measuredYawDegrees,
                measuredPitchDegrees = metadata.measuredPitchDegrees,
                measuredRollDegrees = metadata.measuredRollDegrees,
                capturedAtEpochMs = metadata.capturedAtEpochMs
            )
        }
        val assetIds = frames.map { it.assetId }
        val body = StitchBody(
            assetIds = assetIds,
            capturePattern = pattern.apiValue,
            frames = frames,
            horizontalFovDegrees = room.panoramaHorizontalFovDegrees ?: 52f,
            verticalFovDegrees = room.panoramaVerticalFovDegrees ?: 68f,
            minPitchDegrees = room.panoramaMinPitchDegrees ?: -35f,
            maxPitchDegrees = room.panoramaMaxPitchDegrees ?: 35f,
            manifestAssetId = manifestAssetId
        )
        return serviceProvider(baseUrl)
            .stitchPanorama(bearer(token), captureId, room.serverId, body)
            .jobId
    }

    suspend fun uploadPanorama(
        baseUrl: String,
        token: String,
        captureId: String,
        roomId: String,
        file: File,
        mimeType: String
    ): AssetDto = uploadFile(baseUrl, token, captureId, roomId, "PANORAMA", file, mimeType)


    suspend fun waitForAsset(
        baseUrl: String,
        token: String,
        captureId: String,
        assetId: String,
        timeoutMs: Long = 180_000
    ): AssetDto {
        val started = System.currentTimeMillis()
        while (System.currentTimeMillis() - started < timeoutMs) {
            val capture = getCapture(baseUrl, token, captureId)
            val asset = capture.assets.firstOrNull { it.id == assetId }
                ?: error("Uploaded asset disappeared from capture")
            if (asset.status == "APPROVED") return asset
            if (asset.status == "REJECTED") error("Panorama quality check rejected the asset")
            delay(1500)
        }
        error("Timed out waiting for panorama quality check")
    }

    suspend fun submitCapture(baseUrl: String, token: String, captureId: String) =
        serviceProvider(baseUrl).submitCapture(bearer(token), captureId)

    suspend fun waitForJob(baseUrl: String, token: String, jobId: String, timeoutMs: Long = 180_000): ProcessingJobDto {
        val api = serviceProvider(baseUrl)
        val started = System.currentTimeMillis()
        while (System.currentTimeMillis() - started < timeoutMs) {
            val job = api.getJob(bearer(token), jobId)
            if (job.status in setOf("SUCCEEDED", "FAILED")) return job
            delay(1500)
        }
        error("Timed out waiting for processing job")
    }

    suspend fun getCapture(baseUrl: String, token: String, captureId: String) =
        serviceProvider(baseUrl).getCapture(bearer(token), captureId)

    suspend fun createAndPublishTour(
        baseUrl: String,
        token: String,
        captureId: String,
        title: String,
        rooms: List<RoomDraft>
    ): PublishTourResponse {
        val api = serviceProvider(baseUrl)
        val tour = api.createTour(bearer(token), TourCreateBody(captureId, title))
        rooms.zipWithNext().forEach { (from, to) ->
            api.createHotspot(
                bearer(token), tour.id,
                HotspotBody(from.serverId, to.serverId, 0.0, 0.0, to.name)
            )
        }
        return api.publishTour(bearer(token), tour.id)
    }

    suspend fun createDesignProject(
        baseUrl: String,
        token: String,
        captureId: String,
        name: String,
        model: Map<String, Any>
    ): Pair<DesignProjectDto, String> {
        val api = serviceProvider(baseUrl)
        val project = api.createDesignProject(bearer(token), DesignProjectCreateBody(captureId, name, model))
        val job = api.generateShell(bearer(token), project.id)
        return project to job.jobId
    }

    suspend fun publishDesign(baseUrl: String, token: String, projectId: String): PublishDesignResponse =
        serviceProvider(baseUrl).publishDesign(bearer(token), projectId)

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count <= 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
