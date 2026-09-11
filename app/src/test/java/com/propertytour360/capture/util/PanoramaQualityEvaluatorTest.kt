package com.propertytour360.capture.util

import com.propertytour360.capture.model.PanoramaCapturePattern
import com.propertytour360.capture.model.PanoramaCaptureResult
import com.propertytour360.capture.model.PanoramaFrameMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PanoramaQualityEvaluatorTest {
    @Test
    fun stableQuickRingScoresGood() {
        val frames = (0 until 8).map { index ->
            PanoramaFrameMetadata(
                fileName = "frame-$index.jpg",
                targetYawDegrees = index * 45f,
                targetPitchDegrees = 0f,
                measuredYawDegrees = index * 45f + 1f,
                measuredPitchDegrees = 0.5f,
                measuredRollDegrees = 0f,
                angularSpeedDegreesPerSecond = 12f,
                sensorTimestampNs = index.toLong(),
                capturedAtEpochMs = index.toLong()
            )
        }
        val result = PanoramaCaptureResult(
            files = frames.map { File(it.fileName) },
            manifestFile = File("manifest.json"),
            pattern = PanoramaCapturePattern.QUICK_CENTRAL_RING,
            frames = frames,
            horizontalFovDegrees = 52f,
            verticalFovDegrees = 68f,
            minPitchDegrees = -35f,
            maxPitchDegrees = 35f
        )
        val report = PanoramaQualityEvaluator.evaluate(result)
        assertEquals("GOOD", report.status)
        assertTrue(report.score >= 82)
    }

    @Test
    fun fastSparseCaptureRequestsRecapture() {
        val frames = (0 until 3).map { index ->
            PanoramaFrameMetadata(
                fileName = "frame-$index.jpg",
                targetYawDegrees = index * 120f,
                targetPitchDegrees = 0f,
                measuredYawDegrees = index * 120f + 20f,
                measuredPitchDegrees = 12f,
                measuredRollDegrees = 0f,
                angularSpeedDegreesPerSecond = 80f,
                sensorTimestampNs = index.toLong(),
                capturedAtEpochMs = index.toLong()
            )
        }
        val result = PanoramaCaptureResult(
            frames.map { File(it.fileName) }, File("manifest.json"), PanoramaCapturePattern.QUICK_CENTRAL_RING,
            frames, 52f, 68f, -35f, 35f
        )
        val report = PanoramaQualityEvaluator.evaluate(result)
        assertEquals("RECAPTURE_RECOMMENDED", report.status)
        assertTrue(report.issues.isNotEmpty())
    }
}
