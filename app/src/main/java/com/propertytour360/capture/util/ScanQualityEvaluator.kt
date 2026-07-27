package com.propertytour360.capture.util

import com.google.gson.Gson
import java.io.File

data class ScanQualityReport(
    val score: Int,
    val status: String,
    val issues: List<String>,
    val poseCount: Int,
    val planeSnapshots: Int,
    val depthFrames: Int,
    val durationSeconds: Double
)

object ScanQualityEvaluator {
    private val gson = Gson()

    fun evaluate(directory: File): ScanQualityReport {
        val summary = File(directory, "capture_summary.json")
        if (!summary.exists()) return ScanQualityReport(0, "INCOMPLETE", listOf("Capture summary is missing"), 0, 0, 0, 0.0)
        @Suppress("UNCHECKED_CAST")
        val data = gson.fromJson(summary.readText(), Map::class.java) as Map<String, Any?>
        fun number(key: String) = (data[key] as? Number)?.toDouble() ?: 0.0
        val poses = number("poseCount").toInt()
        val planes = number("planeSnapshotCount").toInt()
        val depths = number("depthFrames").toInt()
        val duration = number("durationSeconds")
        val issues = mutableListOf<String>()
        var score = 100
        if (duration < 20) { score -= 25; issues += "Scan is too short; walk the complete room perimeter" }
        if (poses < 60) { score -= 25; issues += "Insufficient tracked camera poses" }
        if (planes < 4) { score -= 25; issues += "Too few stable floor/wall plane observations" }
        if (depths == 0) { score -= 10; issues += "No Raw Depth frames; manual measurements remain mandatory" }
        val intrinsics = File(directory, "intrinsics.json").exists()
        if (!intrinsics) { score -= 15; issues += "Camera intrinsics are missing" }
        score = score.coerceIn(0, 100)
        val status = when {
            score >= 80 -> "GOOD_DRAFT"
            score >= 55 -> "USABLE_WITH_CORRECTION"
            else -> "RESCAN_RECOMMENDED"
        }
        return ScanQualityReport(score, status, issues, poses, planes, depths, duration)
    }
}
