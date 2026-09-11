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

    data class CameraLensOption(
        val label: String,                // "0.5×", "0.6×", "1×"
        val zoomRatio: Float,             // 0.5f, 0.6f, 1.0f
        val fov: PortraitFov,
        val isUltraWide: Boolean,
        val physicalCameraId: String? = null
    )

    fun estimateNormalBackCameraPortrait(context: Context): PortraitFov {
        val candidates = getBackCameraCandidates(context)
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

    fun detectAvailableLenses(context: Context): List<CameraLensOption> {
        val candidates = getBackCameraCandidates(context)
        val standard = candidates.minByOrNull { abs(it.landscapeHorizontal - 70f) }
        val standardFocal = standard?.focalLength ?: 4.5f
        val standardFov = standard?.let {
            PortraitFov(
                horizontalDegrees = it.portraitHorizontal.coerceIn(42f, 72f),
                verticalDegrees = it.portraitVertical.coerceIn(55f, 88f)
            )
        } ?: PortraitFov(52f, 68f)

        val options = mutableListOf<CameraLensOption>()

        // Find physical ultra-wide cameras (landscape FOV >= 80° or focal length significantly smaller than standard)
        val ultraWides = candidates.filter {
            it.landscapeHorizontal >= 80f || (it.focalLength > 0f && it.focalLength <= standardFocal * 0.70f)
        }

        val bestUltraWide = ultraWides.maxByOrNull { it.landscapeHorizontal }
        if (bestUltraWide != null) {
            val ratio = if (bestUltraWide.focalLength > 0f && standardFocal > 0f) {
                (bestUltraWide.focalLength / standardFocal).coerceIn(0.4f, 0.75f)
            } else {
                0.5f
            }
            val label = if (ratio <= 0.55f) "0.5×" else "0.6×"
            val targetRatio = if (ratio <= 0.55f) 0.5f else 0.6f
            val ultraFov = PortraitFov(
                horizontalDegrees = bestUltraWide.portraitHorizontal.coerceIn(70f, 125f),
                verticalDegrees = bestUltraWide.portraitVertical.coerceIn(85f, 135f)
            )
            options += CameraLensOption(
                label = label,
                zoomRatio = targetRatio,
                fov = ultraFov,
                isUltraWide = true,
                physicalCameraId = bestUltraWide.cameraId
            )
        }

        // Standard 1x option
        options += CameraLensOption(
            label = "1×",
            zoomRatio = 1.0f,
            fov = standardFov,
            isUltraWide = false,
            physicalCameraId = standard?.cameraId
        )

        return options.distinctBy { it.label }.sortedBy { it.zoomRatio }
    }

    fun calculateEffectiveFov(baseFov: PortraitFov, zoomRatio: Float): PortraitFov {
        if (zoomRatio <= 0f) return baseFov
        val baseHRad = Math.toRadians(baseFov.horizontalDegrees.toDouble()) / 2.0
        val baseVRad = Math.toRadians(baseFov.verticalDegrees.toDouble()) / 2.0
        val effectiveHRad = 2.0 * atan(kotlin.math.tan(baseHRad) / zoomRatio.toDouble())
        val effectiveVRad = 2.0 * atan(kotlin.math.tan(baseVRad) / zoomRatio.toDouble())
        return PortraitFov(
            horizontalDegrees = Math.toDegrees(effectiveHRad).toFloat().coerceIn(30f, 130f),
            verticalDegrees = Math.toDegrees(effectiveVRad).toFloat().coerceIn(30f, 140f)
        )
    }

    fun recommendedRingFrameCount(horizontalFovDegrees: Float): Int {
        // 0.60 leaves ~40% overlap between neighbouring frames. The server now
        // aligns frames by matching image features, and the solve was measured to
        // fragment in 1 of 4 captures at the previous 28% overlap (0.72 factor)
        // once real sensor noise is included, while 40% held in every trial.
        // A few extra shots per ring buys deterministic stitching.
        // For standard 1× (HFOV ~52°), this gives ~12 frames.
        // For 0.6× (HFOV ~78°), this gives ~8 frames.
        // For 0.5× (HFOV ~88°), this gives ~7 frames.
        val usableStep = horizontalFovDegrees * 0.60f
        return ceil(360f / usableStep).toInt().coerceIn(6, 16)
    }

    private fun getBackCameraCandidates(context: Context): List<Candidate> {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return emptyList()
        return manager.cameraIdList.flatMap { cameraId ->
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
                        cameraId = cameraId,
                        focalLength = focalLength,
                        portraitHorizontal = minOf(landscapeHorizontal, landscapeVertical),
                        portraitVertical = maxOf(landscapeHorizontal, landscapeVertical),
                        landscapeHorizontal = landscapeHorizontal
                    )
                }
            }.getOrDefault(emptyList<Candidate>())
        }
    }

    private fun fieldOfView(sensorDimensionMm: Float, focalLengthMm: Float): Float =
        Math.toDegrees(2.0 * atan((sensorDimensionMm / (2f * focalLengthMm)).toDouble())).toFloat()

    private data class Candidate(
        val cameraId: String,
        val focalLength: Float,
        val portraitHorizontal: Float,
        val portraitVertical: Float,
        val landscapeHorizontal: Float
    )
}
