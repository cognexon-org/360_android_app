package com.propertytour360.capture.util

import com.propertytour360.capture.model.PanoramaCapturePattern
import com.propertytour360.capture.model.PanoramaCaptureResult
import kotlin.math.abs

/** Fast operator feedback from capture metadata before any cloud stitching cost is spent. */
data class PanoramaQualityReport(
    val score: Int,
    val status: String,
    val issues: List<String>,
    val frameCount: Int,
    val meanAngularErrorDegrees: Double,
    val maxAngularSpeedDegreesPerSecond: Double,
    val fullSphere: Boolean
) {
    fun toMap(): Map<String, Any> = mapOf(
        "score" to score,
        "status" to status,
        "issues" to issues,
        "frameCount" to frameCount,
        "meanAngularErrorDegrees" to meanAngularErrorDegrees,
        "maxAngularSpeedDegreesPerSecond" to maxAngularSpeedDegreesPerSecond,
        "fullSphere" to fullSphere
    )
}

object PanoramaQualityEvaluator {
    fun evaluate(result: PanoramaCaptureResult): PanoramaQualityReport {
        val issues = mutableListOf<String>()
        var score = 100
        val minimumFrames = when (result.pattern) {
            PanoramaCapturePattern.QUICK_CENTRAL_RING -> 8
            PanoramaCapturePattern.FULL_TWO_RINGS_WITH_CAPS -> 18
        }
        if (result.frames.size < minimumFrames) {
            score -= 35
            issues += "Too few guided frames; recapture the full route"
        }
        val angularErrors = result.frames.map {
            abs(it.targetYawDegrees - it.measuredYawDegrees).toDouble() +
                abs(it.targetPitchDegrees - it.measuredPitchDegrees).toDouble()
        }
        val meanAngularError = angularErrors.average().takeIf { !it.isNaN() } ?: 0.0
        val maxSpeed = result.frames.maxOfOrNull { it.angularSpeedDegreesPerSecond.toDouble() } ?: 0.0
        if (meanAngularError > 12.0) { score -= 22; issues += "Camera did not settle on several guide targets" }
        else if (meanAngularError > 7.0) { score -= 10; issues += "Some guide targets were captured off-centre" }
        if (maxSpeed > 55.0) { score -= 25; issues += "Phone moved too quickly during capture; blur/stitch risk is high" }
        else if (maxSpeed > 35.0) { score -= 10; issues += "Slow the rotation for more stable stitching" }
        score = score.coerceIn(0, 100)
        val status = when {
            score >= 82 -> "GOOD"
            score >= 60 -> "USABLE_WITH_WARNING"
            else -> "RECAPTURE_RECOMMENDED"
        }
        return PanoramaQualityReport(score, status, issues, result.frames.size, meanAngularError, maxSpeed, result.pattern.fullSphere)
    }
}
