package com.propertytour360.capture.ar

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.os.Build
import com.propertytour360.capture.BuildConfig
import com.google.ar.core.Camera
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Point
import com.google.ar.core.Pose
import com.google.ar.core.TrackingState
import com.google.gson.Gson
import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.FileWriter
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * Mode B Capture Package v2.
 *
 * Records traceable RGB-D keyframes instead of a loose collection of poses and sparse depth files.
 * Every keyframe stores RGB, pose, intrinsics, optional dense/raw depth, confidence and acquisition metadata.
 */
class ArEvidenceRecorder(
    val outputDirectory: File,
    private val roomName: String
) : Closeable {
    data class Progress(
        val keyframes: Int,
        val poses: Int,
        val floorPlaneObservations: Int,
        val ceilingPlaneObservations: Int,
        val verticalPlanes: Int,
        val depthKeyframes: Int,
        val cornerMarkups: Int,
        val openingMarkups: Int,
        val hasCenterHit: Boolean
    )

    private val gson = Gson()
    private val posesWriter: BufferedWriter
    private val planesWriter: BufferedWriter
    private val keyframesWriter: BufferedWriter
    private val markupsWriter: BufferedWriter
    private val keyframesDirectory = File(outputDirectory, "keyframes")
    private var lastPoseTimestampNs = 0L
    private var lastPlaneTimestampNs = 0L
    private var lastKeyframeTimestampNs = 0L
    private var lastKeyframePose: Pose? = null
    private var lastDenseDepthTimestampNs = -1L
    private var lastRawDepthTimestampNs = -1L
    private var intrinsicsWritten = false
    private var depthSupported = false
    private val startedAtMs = System.currentTimeMillis()
    private var poseCount = 0
    private var planeSnapshotCount = 0
    private var keyframeCount = 0
    private var denseDepthCount = 0
    private var rawDepthCount = 0
    private var confidenceCount = 0
    private var trackingLossCount = 0
    private var floorPlaneCount = 0
    private var ceilingPlaneCount = 0
    private var verticalPlaneCount = 0
    private val uniquePlaneTypes = linkedSetOf<String>()
    private var closed = false
    private var latestPose: Pose? = null
    private var latestFrameTimestampNs: Long = 0L
    private var latestDisplayRotation: Int = 0
    private var markupCount: Int = 0
    private val markupTypeCounts = linkedMapOf<String, Int>()
    @Volatile private var latestHitPose: Pose? = null
    @Volatile private var latestHitTrackableType: String? = null

    init {
        outputDirectory.mkdirs()
        keyframesDirectory.mkdirs()
        posesWriter = BufferedWriter(FileWriter(File(outputDirectory, "poses.jsonl"), true))
        planesWriter = BufferedWriter(FileWriter(File(outputDirectory, "planes.jsonl"), true))
        keyframesWriter = BufferedWriter(FileWriter(File(outputDirectory, "keyframes.jsonl"), true))
        markupsWriter = BufferedWriter(FileWriter(File(outputDirectory, "operator-markups.jsonl"), true))
        File(outputDirectory, "measurements.json").writeText("[]")
    }

    fun record(frame: Frame, camera: Camera, canAcquireDepth: Boolean, displayRotation: Int, viewportWidth: Int, viewportHeight: Int) {
        if (closed) return
        depthSupported = canAcquireDepth
        val timestamp = frame.timestamp
        latestPose = camera.pose
        latestFrameTimestampNs = timestamp
        latestDisplayRotation = displayRotation
        updateCenterHit(frame, viewportWidth, viewportHeight)
        if (!intrinsicsWritten) writeIntrinsics(camera)
        if (camera.trackingState == TrackingState.TRACKING) {
            if (timestamp - lastPoseTimestampNs >= 200_000_000L) {
                writePose(timestamp, camera)
                lastPoseTimestampNs = timestamp
            }
            if (shouldCreateKeyframe(timestamp, camera.pose) && keyframeCount < MAX_KEYFRAMES) {
                tryWriteKeyframe(timestamp, frame, camera)
            }
        } else {
            trackingLossCount++
        }
        if (timestamp - lastPlaneTimestampNs >= 800_000_000L) {
            writePlanes(timestamp, frame)
            lastPlaneTimestampNs = timestamp
        }
    }

    fun addOperatorMarkup(type: String, label: String? = null) {
        if (closed) return
        val cameraPose = latestPose ?: return
        val cameraTranslation = FloatArray(3); val cameraRotation = FloatArray(4)
        cameraPose.getTranslation(cameraTranslation, 0); cameraPose.getRotationQuaternion(cameraRotation, 0)
        val hitPose = latestHitPose
        val hitTranslation = hitPose?.let { pose -> FloatArray(3).also { pose.getTranslation(it, 0) }.toList() }
        val payload = mapOf(
            "id" to "markup-${markupCount + 1}", "type" to type, "label" to label,
            "frameTimestampNs" to latestFrameTimestampNs, "capturedAtEpochMs" to System.currentTimeMillis(),
            "cameraTranslationM" to cameraTranslation.toList(), "cameraQuaternionXYZW" to cameraRotation.toList(),
            "worldPointM" to hitTranslation, "hitTrackableType" to latestHitTrackableType,
            "pointSource" to if (hitTranslation != null) "ARCORE_CENTER_HIT" else "CAMERA_POSE_FALLBACK",
            "displayRotation" to latestDisplayRotation, "status" to "OPERATOR_PROPOSAL"
        )
        markupsWriter.write(gson.toJson(payload)); markupsWriter.newLine(); markupsWriter.flush()
        markupCount++
        markupTypeCounts[type] = (markupTypeCounts[type] ?: 0) + 1
    }

    fun progress(): Progress = Progress(
        keyframes = keyframeCount,
        poses = poseCount,
        floorPlaneObservations = floorPlaneCount,
        ceilingPlaneObservations = ceilingPlaneCount,
        verticalPlanes = verticalPlaneCount,
        depthKeyframes = maxOf(denseDepthCount, rawDepthCount),
        cornerMarkups = markupTypeCounts["WALL_CORNER"] ?: 0,
        openingMarkups = listOf("DOOR", "WINDOW", "OPEN_PASSAGE").sumOf { markupTypeCounts[it] ?: 0 },
        hasCenterHit = latestHitPose != null
    )

    private fun updateCenterHit(frame: Frame, viewportWidth: Int, viewportHeight: Int) {
        if (viewportWidth <= 0 || viewportHeight <= 0) return
        val accepted = runCatching {
            frame.hitTest(viewportWidth / 2f, viewportHeight / 2f).firstOrNull { hit ->
                when (val trackable = hit.trackable) {
                    is Plane -> trackable.isPoseInPolygon(hit.hitPose)
                    is Point -> trackable.orientationMode == Point.OrientationMode.ESTIMATED_SURFACE_NORMAL
                    else -> false
                }
            }
        }.getOrNull()
        latestHitPose = accepted?.hitPose
        latestHitTrackableType = accepted?.trackable?.javaClass?.simpleName
    }

    private fun shouldCreateKeyframe(timestamp: Long, pose: Pose): Boolean {
        val previous = lastKeyframePose ?: return true
        val elapsed = timestamp - lastKeyframeTimestampNs
        if (elapsed < MIN_KEYFRAME_INTERVAL_NS) return false
        val a = FloatArray(3); val b = FloatArray(3)
        previous.getTranslation(a, 0); pose.getTranslation(b, 0)
        val distance = sqrt((a[0]-b[0])*(a[0]-b[0]) + (a[1]-b[1])*(a[1]-b[1]) + (a[2]-b[2])*(a[2]-b[2]))
        val qa = FloatArray(4); val qb = FloatArray(4)
        previous.getRotationQuaternion(qa, 0); pose.getRotationQuaternion(qb, 0)
        val dot = kotlin.math.abs(qa[0]*qb[0] + qa[1]*qb[1] + qa[2]*qb[2] + qa[3]*qb[3]).coerceIn(0f, 1f)
        val angleDegrees = Math.toDegrees((2.0 * acos(dot.toDouble())))
        return distance >= MIN_TRANSLATION_M || angleDegrees >= MIN_ROTATION_DEGREES || elapsed >= FORCED_KEYFRAME_INTERVAL_NS
    }

    private fun tryWriteKeyframe(timestamp: Long, frame: Frame, camera: Camera) {
        var cameraImage: Image? = null
        var denseDepth: Image? = null
        var rawDepth: Image? = null
        var confidence: Image? = null
        try {
            cameraImage = frame.acquireCameraImage()
            val index = keyframeCount
            val keyframeId = index.toString().padStart(4, '0')
            val directory = File(keyframesDirectory, keyframeId).apply { mkdirs() }
            val rgbFile = File(directory, "rgb.jpg")
            writeYuv420Jpeg(cameraImage, rgbFile)

            try { denseDepth = frame.acquireDepthImage16Bits() } catch (_: Throwable) { }
            try { rawDepth = frame.acquireRawDepthImage16Bits() } catch (_: Throwable) { }
            try { confidence = frame.acquireRawDepthConfidenceImage() } catch (_: Throwable) { }

            val depthMetadata = linkedMapOf<String, Any?>()
            denseDepth?.takeIf { it.timestamp > lastDenseDepthTimestampNs }?.let {
                val file = File(directory, "depth_dense.depth16")
                writePlane(file, it.planes[0])
                denseDepthCount++; lastDenseDepthTimestampNs = it.timestamp
                depthMetadata["denseDepth"] = imageDescriptor(file, it, "millimeters_uint16_little_endian")
            }
            val hasNewRawDepth = rawDepth?.timestamp?.let { it > lastRawDepthTimestampNs } == true
            rawDepth?.takeIf { hasNewRawDepth }?.let {
                val file = File(directory, "depth_raw.depth16")
                writePlane(file, it.planes[0])
                rawDepthCount++; lastRawDepthTimestampNs = it.timestamp
                depthMetadata["rawDepth"] = imageDescriptor(file, it, "millimeters_uint16_little_endian")
            }
            confidence?.takeIf { hasNewRawDepth }?.let {
                val file = File(directory, "confidence.confidence8")
                writePlane(file, it.planes[0])
                confidenceCount++
                depthMetadata["confidence"] = imageDescriptor(file, it, "normalized_uint8")
            }

            val pose = camera.pose
            val translation = FloatArray(3); val rotation = FloatArray(4)
            pose.getTranslation(translation, 0); pose.getRotationQuaternion(rotation, 0)
            val intrinsics = camera.imageIntrinsics
            val focal = FloatArray(2); val principal = FloatArray(2); val dimensions = IntArray(2)
            intrinsics.getFocalLength(focal, 0); intrinsics.getPrincipalPoint(principal, 0); intrinsics.getImageDimensions(dimensions, 0)
            val metadata = linkedMapOf<String, Any?>(
                "schemaVersion" to "2.0",
                "keyframeId" to keyframeId,
                "frameTimestampNs" to timestamp,
                "cameraImageTimestampNs" to cameraImage.timestamp,
                "capturedAtEpochMs" to System.currentTimeMillis(),
                "trackingState" to camera.trackingState.name,
                "trackingFailureReason" to camera.trackingFailureReason.name,
                "displayRotation" to latestDisplayRotation,
                "imageOrientation" to "SENSOR_NATIVE_CPU_IMAGE",
                "deviceManufacturer" to Build.MANUFACTURER,
                "deviceModel" to Build.MODEL,
                "androidSdk" to Build.VERSION.SDK_INT,
                "appVersion" to BuildConfig.VERSION_NAME,
                "roomName" to roomName,
                "newDenseDepth" to depthMetadata.containsKey("denseDepth"),
                "newRawDepth" to depthMetadata.containsKey("rawDepth"),
                "translationM" to translation.toList(),
                "quaternionXYZW" to rotation.toList(),
                "rgbFile" to "rgb.jpg",
                "rgbWidth" to cameraImage.width,
                "rgbHeight" to cameraImage.height,
                "focalLength" to focal.toList(),
                "principalPoint" to principal.toList(),
                "intrinsicsImageDimensions" to dimensions.toList(),
                "depth" to depthMetadata
            )
            File(directory, "metadata.json").writeText(gson.toJson(metadata))
            keyframesWriter.write(gson.toJson(metadata + mapOf("relativeDirectory" to "keyframes/$keyframeId")))
            keyframesWriter.newLine(); keyframesWriter.flush()
            keyframeCount++
            lastKeyframeTimestampNs = timestamp
            lastKeyframePose = pose
        } catch (_: Throwable) {
            // Camera/depth images are not available on every ARCore frame. A later keyframe will retry.
        } finally {
            confidence?.close(); rawDepth?.close(); denseDepth?.close(); cameraImage?.close()
        }
    }

    private fun writePose(timestamp: Long, camera: Camera) {
        val pose = camera.pose
        val translation = FloatArray(3); val rotation = FloatArray(4)
        pose.getTranslation(translation, 0); pose.getRotationQuaternion(rotation, 0)
        val payload = mapOf("timestampNs" to timestamp, "trackingState" to camera.trackingState.name, "translation" to translation.toList(), "quaternion" to rotation.toList())
        posesWriter.write(gson.toJson(payload)); posesWriter.newLine(); posesWriter.flush(); poseCount++
    }

    private fun writeIntrinsics(camera: Camera) {
        val image = camera.imageIntrinsics
        val texture = camera.textureIntrinsics
        fun encode(intrinsics: com.google.ar.core.CameraIntrinsics): Map<String, Any> {
            val focal = FloatArray(2); val principal = FloatArray(2); val dimensions = IntArray(2)
            intrinsics.getFocalLength(focal, 0); intrinsics.getPrincipalPoint(principal, 0); intrinsics.getImageDimensions(dimensions, 0)
            return mapOf("focalLength" to focal.toList(), "principalPoint" to principal.toList(), "imageDimensions" to dimensions.toList())
        }
        File(outputDirectory, "intrinsics.json").writeText(gson.toJson(mapOf("schemaVersion" to "2.0", "roomName" to roomName, "cpuImage" to encode(image), "gpuTexture" to encode(texture))))
        intrinsicsWritten = true
    }

    private fun writePlanes(timestamp: Long, frame: Frame) {
        val planes = frame.getUpdatedTrackables(Plane::class.java)
            .filter { it.trackingState == TrackingState.TRACKING && it.subsumedBy == null }
            .map { plane ->
                val center = plane.centerPose
                val translation = FloatArray(3); val rotation = FloatArray(4)
                center.getTranslation(translation, 0); center.getRotationQuaternion(rotation, 0)
                val polygonBuffer = plane.polygon.duplicate(); val polygon = mutableListOf<Float>()
                while (polygonBuffer.hasRemaining()) polygon += polygonBuffer.get()
                when (plane.type) {
                    Plane.Type.VERTICAL -> verticalPlaneCount++
                    Plane.Type.HORIZONTAL_UPWARD_FACING -> floorPlaneCount++
                    Plane.Type.HORIZONTAL_DOWNWARD_FACING -> ceilingPlaneCount++
                    else -> Unit
                }
                mapOf("trackableId" to System.identityHashCode(plane), "type" to plane.type.name, "extentX" to plane.extentX, "extentZ" to plane.extentZ, "translation" to translation.toList(), "quaternion" to rotation.toList(), "polygonXZ" to polygon)
            }
        if (planes.isNotEmpty()) {
            planeSnapshotCount++
            planes.mapNotNull { it["type"] as? String }.forEach(uniquePlaneTypes::add)
            planesWriter.write(gson.toJson(mapOf("timestampNs" to timestamp, "planes" to planes))); planesWriter.newLine(); planesWriter.flush()
        }
    }

    private fun imageDescriptor(file: File, image: Image, unit: String): Map<String, Any> = mapOf(
        "file" to file.name, "width" to image.width, "height" to image.height,
        "rowStride" to image.planes[0].rowStride, "pixelStride" to image.planes[0].pixelStride, "unit" to unit,
        "timestampNs" to image.timestamp
    )

    private fun writePlane(file: File, plane: Image.Plane) {
        val buffer = plane.buffer.duplicate(); buffer.rewind(); val bytes = ByteArray(buffer.remaining()); buffer.get(bytes); file.writeBytes(bytes)
    }

    private fun writeYuv420Jpeg(image: Image, file: File) {
        require(image.format == ImageFormat.YUV_420_888)
        val width = image.width; val height = image.height
        val nv21 = ByteArray(width * height * 3 / 2)
        copyPlane(image.planes[0], width, height, nv21, 0, 1)
        copyChromaInterleaved(image.planes[2], image.planes[1], width / 2, height / 2, nv21, width * height)
        FileOutputStream(file).use { output ->
            check(YuvImage(nv21, ImageFormat.NV21, width, height, null).compressToJpeg(Rect(0, 0, width, height), 88, output))
        }
    }

    private fun copyPlane(plane: Image.Plane, width: Int, height: Int, output: ByteArray, offset: Int, outputPixelStride: Int) {
        val buffer = plane.buffer.duplicate(); val rowStride = plane.rowStride; val pixelStride = plane.pixelStride
        val base = buffer.position()
        var out = offset
        for (row in 0 until height) {
            val rowStart = base + row * rowStride
            for (col in 0 until width) {
                output[out] = buffer.get(rowStart + col * pixelStride); out += outputPixelStride
            }
        }
    }

    private fun copyChromaInterleaved(vPlane: Image.Plane, uPlane: Image.Plane, width: Int, height: Int, output: ByteArray, offset: Int) {
        val v = vPlane.buffer.duplicate(); val u = uPlane.buffer.duplicate(); var out = offset
        val vBase = v.position(); val uBase = u.position()
        for (row in 0 until height) for (col in 0 until width) {
            output[out++] = v.get(vBase + row * vPlane.rowStride + col * vPlane.pixelStride)
            output[out++] = u.get(uBase + row * uPlane.rowStride + col * uPlane.pixelStride)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        posesWriter.close(); planesWriter.close(); keyframesWriter.close(); markupsWriter.close()
        val duration = (System.currentTimeMillis() - startedAtMs) / 1000.0
        val summary = mapOf(
            "schemaVersion" to "2.0", "roomName" to roomName, "depthSupported" to depthSupported,
            "keyframeCount" to keyframeCount, "rgbKeyframes" to keyframeCount, "denseDepthFrames" to denseDepthCount,
            "rawDepthFrames" to rawDepthCount, "depthFrames" to maxOf(denseDepthCount, rawDepthCount), "confidenceFrames" to confidenceCount,
            "poseCount" to poseCount, "planeSnapshotCount" to planeSnapshotCount,
            "floorPlaneObservations" to floorPlaneCount, "ceilingPlaneObservations" to ceilingPlaneCount,
            "verticalPlaneObservations" to verticalPlaneCount, "planeTypes" to uniquePlaneTypes.toList(), "trackingLossCount" to trackingLossCount,
            "durationSeconds" to duration, "operatorMarkupCount" to markupCount, "operatorMarkupTypes" to markupTypeCounts,
            "draftEvidence" to true, "requiresManualDimensionConfirmation" to true
        )
        File(outputDirectory, "capture_summary.json").writeText(gson.toJson(summary))
        File(outputDirectory, "manifest.json").writeText(gson.toJson(mapOf(
            "schemaVersion" to "2.0", "captureType" to "ANDROID_RGBD_ROOM_SCAN", "roomName" to roomName,
            "createdAtEpochMs" to startedAtMs, "completedAtEpochMs" to System.currentTimeMillis(),
            "keyframeIndex" to "keyframes.jsonl", "poses" to "poses.jsonl", "planes" to "planes.jsonl",
            "intrinsics" to "intrinsics.json", "summary" to "capture_summary.json", "measurements" to "measurements.json", "operatorMarkups" to "operator-markups.jsonl", "checksums" to "checksums.sha256", "keyframeCount" to keyframeCount,
            "sourceEvidenceImmutable" to true, "geometryStatus" to "DRAFT_MODEL"
        )))
        writeChecksums()
    }

    private fun writeChecksums() {
        val checksumFile = File(outputDirectory, "checksums.sha256")
        val lines = outputDirectory.walkTopDown().filter { it.isFile && it != checksumFile }.sortedBy { it.relativeTo(outputDirectory).invariantSeparatorsPath }.map { file ->
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) { val read = input.read(buffer); if (read <= 0) break; digest.update(buffer, 0, read) }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            "$hash  ${file.relativeTo(outputDirectory).invariantSeparatorsPath}"
        }.toList()
        checksumFile.writeText(lines.joinToString("\n", postfix = if (lines.isEmpty()) "" else "\n"))
    }

    companion object {
        private const val MAX_KEYFRAMES = 60
        private const val MIN_KEYFRAME_INTERVAL_NS = 600_000_000L
        private const val FORCED_KEYFRAME_INTERVAL_NS = 2_500_000_000L
        private const val MIN_TRANSLATION_M = 0.18f
        private const val MIN_ROTATION_DEGREES = 11.0
    }
}
