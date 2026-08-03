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
    val durationSeconds: Double,
    val keyframes: Int = 0,
    val wallObservations: Int = 0,
    val floorObservations: Int = 0,
    val ceilingObservations: Int = 0,
    val operatorMarkups: Int = 0
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
        val walls = number("verticalPlaneObservations").toInt()
        val depths = number("depthFrames").toInt()
        val floors = number("floorPlaneObservations").toInt()
        val ceilings = number("ceilingPlaneObservations").toInt()
        val duration = number("durationSeconds")
        val keyframes = number("keyframeCount").toInt()
        val markups = number("operatorMarkupCount").toInt()
        val issues = mutableListOf<String>()
        var score = 100
        if (duration < 25) { score -= 20; issues += "Scan is too short; walk the complete perimeter" }
        if (poses < 80) { score -= 12; issues += "Insufficient tracked camera poses" }
        if (keyframes < 16) { score -= 22; issues += "Too few RGB keyframes; pause at every wall, corner and opening" }
        if (planes < 5) { score -= 12; issues += "Too few plane snapshots" }
        if (walls < 6) { score -= 15; issues += "Insufficient vertical wall observations" }
        if (floors == 0) { score -= 6; issues += "Floor boundary was not observed" }
        if (ceilings == 0) { score -= 6; issues += "Ceiling boundary was not observed" }
        if (depths == 0) { score -= 10; issues += "No depth frames; the project will use AR/manual fallback" }
        if (!File(directory, "intrinsics.json").exists()) { score -= 15; issues += "Camera intrinsics are missing" }
        if (!File(directory, "checksums.sha256").exists()) { score -= 10; issues += "Checksum manifest is missing" }
        if (markups == 0) { score -= 4; issues += "No operator corner/opening proposals were marked" }
        score = score.coerceIn(0, 100)
        val status = when {
            score >= 82 -> "GOOD_DRAFT"
            score >= 58 -> "USABLE_WITH_CORRECTION"
            else -> "RESCAN_RECOMMENDED"
        }
        return ScanQualityReport(score, status, issues, poses, planes, depths, duration, keyframes, walls, floors, ceilings, markups)
    }
}
