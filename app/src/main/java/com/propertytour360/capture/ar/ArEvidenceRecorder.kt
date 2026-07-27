package com.propertytour360.capture.ar

import android.media.Image
import com.google.ar.core.Camera
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.TrackingState
import com.google.gson.Gson
import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.io.FileWriter
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

class ArEvidenceRecorder(
    val outputDirectory: File,
    private val roomName: String
) : Closeable {
    private val gson = Gson()
    private val posesWriter: BufferedWriter
    private val planesWriter: BufferedWriter
    private var lastPoseTimestampNs = 0L
    private var lastDepthTimestampNs = 0L
    private var lastPlaneTimestampNs = 0L
    private var intrinsicsWritten = false
    private val depthIndex = AtomicInteger(0)
    private var depthSupported = false
    private val startedAtMs = System.currentTimeMillis()
    private var poseCount = 0
    private var planeSnapshotCount = 0
    private val uniquePlaneTypes = linkedSetOf<String>()

    init {
        outputDirectory.mkdirs()
        posesWriter = BufferedWriter(FileWriter(File(outputDirectory, "poses.jsonl"), true))
        planesWriter = BufferedWriter(FileWriter(File(outputDirectory, "planes.jsonl"), true))
    }

    fun record(frame: Frame, camera: Camera, canAcquireDepth: Boolean) {
        depthSupported = canAcquireDepth
        val timestamp = frame.timestamp
        if (!intrinsicsWritten) writeIntrinsics(camera)
        if (camera.trackingState == TrackingState.TRACKING && timestamp - lastPoseTimestampNs >= 200_000_000L) {
            writePose(timestamp, camera)
            lastPoseTimestampNs = timestamp
        }
        if (timestamp - lastPlaneTimestampNs >= 1_000_000_000L) {
            writePlanes(timestamp, frame)
            lastPlaneTimestampNs = timestamp
        }
        if (canAcquireDepth && timestamp - lastDepthTimestampNs >= 2_000_000_000L && depthIndex.get() < 30) {
            tryWriteDepth(timestamp, frame)
            lastDepthTimestampNs = timestamp
        }
    }

    private fun writePose(timestamp: Long, camera: Camera) {
        val pose = camera.pose
        val translation = FloatArray(3)
        val rotation = FloatArray(4)
        pose.getTranslation(translation, 0)
        pose.getRotationQuaternion(rotation, 0)
        val payload = mapOf(
            "timestampNs" to timestamp,
            "trackingState" to camera.trackingState.name,
            "translation" to translation.toList(),
            "quaternion" to rotation.toList()
        )
        posesWriter.write(gson.toJson(payload))
        posesWriter.newLine()
        posesWriter.flush()
        poseCount++
    }

    private fun writeIntrinsics(camera: Camera) {
        val intrinsics = camera.imageIntrinsics
        val focal = FloatArray(2)
        val principal = FloatArray(2)
        val dimensions = IntArray(2)
        intrinsics.getFocalLength(focal, 0)
        intrinsics.getPrincipalPoint(principal, 0)
        intrinsics.getImageDimensions(dimensions, 0)
        File(outputDirectory, "intrinsics.json").writeText(
            gson.toJson(
                mapOf(
                    "roomName" to roomName,
                    "focalLength" to focal.toList(),
                    "principalPoint" to principal.toList(),
                    "imageDimensions" to dimensions.toList()
                )
            )
        )
        intrinsicsWritten = true
    }

    private fun writePlanes(timestamp: Long, frame: Frame) {
        val planes = frame.getUpdatedTrackables(Plane::class.java)
            .filter { it.trackingState == TrackingState.TRACKING && it.subsumedBy == null }
            .map { plane ->
                val center = plane.centerPose
                val translation = FloatArray(3)
                val rotation = FloatArray(4)
                center.getTranslation(translation, 0)
                center.getRotationQuaternion(rotation, 0)
                val polygonBuffer = plane.polygon.duplicate()
                val polygon = mutableListOf<Float>()
                while (polygonBuffer.hasRemaining()) polygon += polygonBuffer.get()
                mapOf(
                    "type" to plane.type.name,
                    "extentX" to plane.extentX,
                    "extentZ" to plane.extentZ,
                    "translation" to translation.toList(),
                    "quaternion" to rotation.toList(),
                    "polygonXZ" to polygon
                )
            }
        if (planes.isNotEmpty()) {
            planeSnapshotCount++
            planes.flatMap { listOfNotNull(it["type"] as? String) }.forEach(uniquePlaneTypes::add)
            planesWriter.write(gson.toJson(mapOf("timestampNs" to timestamp, "planes" to planes)))
            planesWriter.newLine()
            planesWriter.flush()
        }
    }

    private fun tryWriteDepth(timestamp: Long, frame: Frame) {
        var depth: Image? = null
        var confidence: Image? = null
        try {
            depth = frame.acquireRawDepthImage16Bits()
            confidence = frame.acquireRawDepthConfidenceImage()
            val index = depthIndex.getAndIncrement()
            val depthName = "depth_${index.toString().padStart(3, '0')}.depth16"
            val confidenceName = "confidence_${index.toString().padStart(3, '0')}.confidence8"
            writeBuffer(File(outputDirectory, depthName), depth.planes[0].buffer)
            writeBuffer(File(outputDirectory, confidenceName), confidence.planes[0].buffer)
            File(outputDirectory, "depth_${index.toString().padStart(3, '0')}.json").writeText(
                gson.toJson(
                    mapOf(
                        "timestampNs" to timestamp,
                        "depthFile" to depthName,
                        "confidenceFile" to confidenceName,
                        "width" to depth.width,
                        "height" to depth.height,
                        "depthRowStride" to depth.planes[0].rowStride,
                        "depthPixelStride" to depth.planes[0].pixelStride,
                        "confidenceRowStride" to confidence.planes[0].rowStride,
                        "confidencePixelStride" to confidence.planes[0].pixelStride,
                        "depthUnit" to "millimeters_uint16_little_endian"
                    )
                )
            )
        } catch (_: Throwable) {
            // Raw depth is not available on every frame. Skip and try on a later frame.
        } finally {
            confidence?.close()
            depth?.close()
        }
    }

    private fun writeBuffer(file: File, source: ByteBuffer) {
        val copy = source.duplicate()
        copy.rewind()
        val bytes = ByteArray(copy.remaining())
        copy.get(bytes)
        file.writeBytes(bytes)
    }

    override fun close() {
        posesWriter.close()
        planesWriter.close()
        File(outputDirectory, "capture_summary.json").writeText(
            gson.toJson(
                mapOf(
                    "roomName" to roomName,
                    "depthSupported" to depthSupported,
                    "depthFrames" to depthIndex.get(),
                    "poseCount" to poseCount,
                    "planeSnapshotCount" to planeSnapshotCount,
                    "planeTypes" to uniquePlaneTypes.toList(),
                    "durationSeconds" to ((System.currentTimeMillis() - startedAtMs) / 1000.0),
                    "draftEvidence" to true,
                    "requiresManualDimensionConfirmation" to true
                )
            )
        )
    }
}
