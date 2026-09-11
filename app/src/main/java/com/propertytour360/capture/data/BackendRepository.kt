package com.propertytour360.capture.data

import android.os.Build
import com.propertytour360.capture.model.RoomDraft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.MediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okio.BufferedSink
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
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

    suspend fun listProgressProjects(baseUrl: String, token: String): List<ProgressProjectDto> =
        serviceProvider(baseUrl).listProgressProjects(bearer(token))

    suspend fun createProgressProject(
        baseUrl: String, token: String, unitId: String, name: String, captureCadence: String? = "WEEKLY"
    ): ProgressProjectDto = serviceProvider(baseUrl).createProgressProject(
        bearer(token), ProgressProjectCreateBody(unitId = unitId, name = name, captureCadence = captureCadence)
    )

    suspend fun createProgressCapture(
        baseUrl: String,
        token: String,
        project: ProgressProjectDto,
        mode: String,
        deviceMetadataExtra: Map<String, Any> = emptyMap(),
        checklist: Map<String, Any>? = null
    ): ProgressCaptureResponse {
        val metadata = mapOf(
            "manufacturer" to Build.MANUFACTURER,
            "model" to Build.MODEL,
            "sdk" to Build.VERSION.SDK_INT,
            "appVersion" to "3.3.0",
            "progressProjectId" to project.id
        ) + deviceMetadataExtra
        return serviceProvider(baseUrl).createProgressCapture(
            bearer(token), project.id, ProgressCaptureCreateBody(
                mode = mode,
                floorId = project.floors.firstOrNull()?.id,
                deviceMetadata = metadata,
                checklist = checklist,
                spatialScope = project.floors.firstOrNull()?.id?.let { mapOf("floorId" to it) }
            )
        )
    }

    suspend fun createOrReuseSpatialRoom(
        baseUrl: String, token: String, projectId: String, floorId: String?, name: String, sortOrder: Int
    ): SpatialRoomDto = serviceProvider(baseUrl).createSpatialRoom(
        bearer(token), projectId, SpatialRoomCreateBody(
            floorId = floorId, name = name, sortOrder = sortOrder, reuseByName = true
        )
    )

    suspend fun createCapture(baseUrl: String, token: String, unitId: String, mode: String): CaptureDto {
        val metadata = mapOf(
            "manufacturer" to Build.MANUFACTURER,
            "model" to Build.MODEL,
            "sdk" to Build.VERSION.SDK_INT,
            "appVersion" to "3.1.0"
        )
        return serviceProvider(baseUrl).createCapture(
            bearer(token),
            CaptureCreateBody(unitId, mode, deviceMetadata = metadata)
        )
    }

    suspend fun createRoom(
        baseUrl: String, token: String, captureId: String, name: String, sortOrder: Int, spatialRoomId: String? = null
    ) = serviceProvider(baseUrl).createRoom(
        bearer(token), captureId, RoomCreateBody(name = name, sortOrder = sortOrder, spatialRoomId = spatialRoomId)
    )

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
        mimeType: String,
        progress: ((Long, Long) -> Unit)? = null
    ): AssetDto = uploadFileResumable(baseUrl, token, captureId, roomId, kind, file, mimeType, progress)

    suspend fun uploadFileResumable(
        baseUrl: String,
        token: String,
        captureId: String,
        roomId: String?,
        kind: String,
        file: File,
        mimeType: String,
        progress: ((Long, Long) -> Unit)? = null
    ): AssetDto = withContext(Dispatchers.IO) {
        require(file.exists() && file.isFile) { "Upload file is missing: ${file.absolutePath}" }
        val api = serviceProvider(baseUrl)
        val checksum = sha256(file)
        val idempotencyKey = sha256Text("$captureId|${roomId.orEmpty()}|$kind|${file.name}|${file.length()}|$checksum")
        var upload = api.createResumableUpload(
            bearer(token), idempotencyKey, captureId,
            ResumableUploadCreateBody(
                roomId = roomId,
                kind = kind,
                filename = file.name,
                mimeType = mimeType,
                sizeBytes = file.length(),
                checksumSha256 = checksum
            )
        )
        if (upload.status == "COMPLETED") {
            return@withContext api.completeResumableUpload(bearer(token), captureId, upload.id).asset
        }

        val completeParts = upload.parts.filter { it.status == "UPLOADED" }.associateBy { it.partNumber }
        var uploadedBytes = completeParts.values.sumOf { it.sizeBytes }
        progress?.invoke(uploadedBytes, file.length())

        for (partNumber in 1..upload.totalParts) {
            if (completeParts.containsKey(partNumber)) continue
            val offset = (partNumber - 1L) * upload.chunkSizeBytes.toLong()
            val length = minOf(upload.chunkSizeBytes.toLong(), file.length() - offset)
            var uploaded = false
            var lastFailure: Throwable? = null
            repeat(3) { attempt ->
                if (uploaded) return@repeat
                try {
                    val part = api.requestResumablePartUrl(bearer(token), captureId, upload.id, partNumber)
                    if (!part.alreadyUploaded) {
                        val url = part.uploadUrl ?: error("Upload URL missing for part $partNumber")
                        val request = Request.Builder()
                            .url(url)
                            .put(FileSliceRequestBody(file, offset, length, mimeType.toMediaTypeOrNull()))
                            .build()
                        uploadClient.newCall(request).execute().use { response ->
                            if (!response.isSuccessful) {
                                val retryable = response.code == 408 || response.code == 429 || response.code >= 500
                                if (retryable) throw IOException("Chunk upload failed: HTTP ${response.code}")
                                error("Chunk upload rejected: HTTP ${response.code}")
                            }
                        }
                        api.completeResumablePart(
                            bearer(token), captureId, upload.id, partNumber, ResumablePartCompleteBody()
                        )
                    }
                    uploaded = true
                } catch (error: Throwable) {
                    lastFailure = error
                    if (attempt < 2) delay((attempt + 1L) * 1000L)
                }
            }
            if (!uploaded) throw lastFailure ?: IOException("Unable to upload part $partNumber")
            uploadedBytes += length
            progress?.invoke(uploadedBytes.coerceAtMost(file.length()), file.length())
        }

        upload = api.getResumableUpload(bearer(token), captureId, upload.id)
        if (upload.parts.count { it.status == "UPLOADED" } != upload.totalParts) {
            throw IOException("Upload did not persist all parts; retry will resume from server state")
        }
        api.completeResumableUpload(bearer(token), captureId, upload.id).asset
    }

    suspend fun submitCaptureQualityFeedback(
        baseUrl: String,
        token: String,
        captureId: String,
        scope: String,
        spatialRoomId: String?,
        report: Map<String, Any>,
        deviceTelemetry: Map<String, Any>? = null
    ): CaptureQualityFeedbackResponse = serviceProvider(baseUrl).submitCaptureQualityFeedback(
        bearer(token), captureId,
        CaptureQualityFeedbackBody(scope = scope, spatialRoomId = spatialRoomId, report = report, deviceTelemetry = deviceTelemetry)
    )

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

    suspend fun finalizeCapturePackages(
        baseUrl: String,
        token: String,
        captureId: String,
        rooms: List<FinalizePackageRoom>
    ): FinalizePackagesResponse =
        serviceProvider(baseUrl).finalizeCapturePackages(bearer(token), captureId, FinalizePackagesBody(rooms))

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
    ): DesignProjectDto = serviceProvider(baseUrl).createDesignProject(
        bearer(token),
        DesignProjectCreateBody(captureId = captureId, name = name, model = model, generateGeometry = true)
    )

    suspend fun publishDesign(baseUrl: String, token: String, projectId: String): PublishDesignResponse =
        serviceProvider(baseUrl).publishDesign(bearer(token), projectId)

    private class FileSliceRequestBody(
        private val file: File,
        private val offset: Long,
        private val length: Long,
        private val mediaType: MediaType?
    ) : RequestBody() {
        override fun contentType(): MediaType? = mediaType
        override fun contentLength(): Long = length

        override fun writeTo(sink: BufferedSink) {
            RandomAccessFile(file, "r").use { input ->
                input.seek(offset)
                var remaining = length
                val buffer = ByteArray(64 * 1024)
                while (remaining > 0) {
                    val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (read < 0) throw IOException("Unexpected end of file while uploading ${file.name}")
                    sink.write(buffer, 0, read)
                    remaining -= read
                }
            }
        }
    }

    private fun sha256Text(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

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
