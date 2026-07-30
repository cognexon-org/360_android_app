package com.propertytour360.capture.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.ceil

/**
 * Estimates the normal rear camera field of view from Camera2 metadata.
 * No user calibration and no ultrawide dependency are required. The estimate is
 * used only to choose 8, 9 or 10 guided positions and to seed server projection.
 */
object CameraFovEstimator {
    data class PortraitFov(
        val horizontalDegrees: Float,
        val verticalDegrees: Float
    )

    fun estimateNormalBackCameraPortrait(context: Context): PortraitFov {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val candidates: List<Candidate> = manager.cameraIdList.flatMap { cameraId ->
            runCatching {
                val characteristics = manager.getCameraCharacteristics(cameraId)
                if (characteristics.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) {
                    return@runCatching emptyList<Candidate>()
                }
                val sensorSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                    ?: return@runCatching emptyList<Candidate>()
                val focalLengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                    ?: return@runCatching emptyList<Candidate>()

                focalLengths.filter { it > 0f }.map { focalLength ->
                    val landscapeHorizontal = fieldOfView(sensorSize.width, focalLength)
                    val landscapeVertical = fieldOfView(sensorSize.height, focalLength)
                    Candidate(
                        portraitHorizontal = minOf(landscapeHorizontal, landscapeVertical),
                        portraitVertical = maxOf(landscapeHorizontal, landscapeVertical),
                        landscapeHorizontal = landscapeHorizontal
                    )
                }
            }.getOrDefault(emptyList<Candidate>())
        }

        // Prefer a normal wide camera around 65–75° landscape HFOV and avoid ultrawide lenses.
        val selected = candidates.minByOrNull { candidate ->
            abs(candidate.landscapeHorizontal - 70f) +
                if (candidate.landscapeHorizontal !in 50f..90f) 100f else 0f
        }

        return selected?.let {
            PortraitFov(
                horizontalDegrees = it.portraitHorizontal.coerceIn(42f, 72f),
                verticalDegrees = it.portraitVertical.coerceIn(55f, 88f)
            )
        } ?: PortraitFov(horizontalDegrees = 52f, verticalDegrees = 68f)
    }

    fun recommendedRingFrameCount(horizontalFovDegrees: Float): Int {
        // 0.60 leaves ~40% overlap between neighbouring frames. The server now
        // aligns frames by matching image features, and the solve was measured to
        // fragment in 1 of 4 captures at the previous 28% overlap (0.72 factor)
        // once real sensor noise is included, while 40% held in every trial.
        // A few extra shots per ring buys deterministic stitching.
        val usableStep = horizontalFovDegrees * 0.60f
        return ceil(360f / usableStep).toInt().coerceIn(10, 16)
    }

    private fun fieldOfView(sensorDimensionMm: Float, focalLengthMm: Float): Float =
        Math.toDegrees(2.0 * atan((sensorDimensionMm / (2f * focalLengthMm)).toDouble())).toFloat()

    private data class Candidate(
        val portraitHorizontal: Float,
        val portraitVertical: Float,
        val landscapeHorizontal: Float
    )
}
