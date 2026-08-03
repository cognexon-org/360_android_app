package com.propertytour360.capture.util

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.propertytour360.capture.model.RoomDraft
import java.io.File
import java.security.MessageDigest

/**
 * Finalizes the immutable Mode B evidence directory after the operator confirms
 * the field plan. ARCore recording ends before measurements are entered, so the
 * field plan and measurement provenance are attached here and the checksum
 * manifest is regenerated before upload.
 */
object ModeBCapturePackage {
    private val gson = GsonBuilder().setPrettyPrinting().create()

    fun attachFieldPlan(directory: File, room: RoomDraft) {
        require(directory.isDirectory) { "Capture package directory is missing" }
        require(room.floorPolygon.size >= 3) { "Confirm the field plan before uploading evidence" }
        require(room.heightM != null) { "Confirm the ceiling height before uploading evidence" }

        val measurements = room.measurements.map { measurement ->
            mapOf(
                "id" to measurement.id,
                "label" to measurement.label,
                "valueM" to measurement.valueM,
                "unit" to "m",
                "method" to measurement.method,
                "toleranceM" to measurement.toleranceM,
                "start" to measurement.start?.let { listOf(it.xM, it.zM) },
                "end" to measurement.end?.let { listOf(it.xM, it.zM) },
                "verified" to measurement.verified,
                "verificationStatus" to measurement.verificationStatus,
                "operatorId" to measurement.operatorId,
                "deviceManufacturer" to measurement.deviceManufacturer,
                "deviceModel" to measurement.deviceModel,
                "capturedAtEpochMs" to measurement.capturedAtEpochMs,
                "evidenceRefs" to measurement.evidenceRefs,
                "notes" to measurement.notes
            )
        }
        File(directory, "measurements.json").writeText(gson.toJson(measurements))

        val fieldPlan = mapOf(
            "schemaVersion" to "2.1",
            "roomId" to room.serverId,
            "roomName" to room.name,
            "floorPolygonM" to room.floorPolygon.map { listOf(it.xM, it.zM) },
            "ceilingHeightM" to room.heightM,
            "placement" to mapOf(
                "floorId" to room.placement.floorId,
                "elevationM" to room.placement.elevationM,
                "originM" to listOf(room.placement.originXM, room.placement.originZM),
                "rotationDegrees" to room.placement.rotationDegrees,
                "connectionAnchorId" to room.placement.connectionAnchorId,
                "connectionConfidence" to room.placement.connectionConfidence
            ),
            "openings" to room.openings.map { opening ->
                mapOf(
                    "id" to opening.id,
                    "type" to opening.type.name,
                    "wallIndex" to opening.wallIndex,
                    "offsetM" to opening.offsetM,
                    "widthM" to opening.widthM,
                    "heightM" to opening.heightM,
                    "sillM" to opening.sillM,
                    "swing" to opening.swing,
                    "confidence" to opening.confidence,
                    "source" to opening.source
                )
            },
            "measurementsFile" to "measurements.json",
            "verificationStatus" to "OPERATOR_CONFIRMED_DRAFT",
            "designerReviewRequired" to true
        )
        File(directory, "field-plan.json").writeText(gson.toJson(fieldPlan))

        val manifestFile = File(directory, "manifest.json")
        val manifest = if (manifestFile.exists()) {
            runCatching { JsonParser.parseString(manifestFile.readText()).asJsonObject }.getOrElse { JsonObject() }
        } else JsonObject()
        manifest.addProperty("schemaVersion", "2.1")
        manifest.addProperty("fieldPlan", "field-plan.json")
        manifest.addProperty("measurements", "measurements.json")
        manifest.addProperty("canonicalModelSchema", "2.1")
        manifest.addProperty("operatorFieldPlanConfirmed", true)
        manifest.addProperty("openingCount", room.openings.size)
        manifest.addProperty("measurementCount", room.measurements.size)
        manifest.addProperty("checksums", "checksums.sha256")
        manifestFile.writeText(gson.toJson(manifest))
        writeChecksums(directory)
    }

    fun verifyChecksums(directory: File): List<String> {
        val checksumFile = File(directory, "checksums.sha256")
        if (!checksumFile.exists()) return listOf("checksums.sha256 is missing")
        val problems = mutableListOf<String>()
        checksumFile.readLines().filter { it.isNotBlank() }.forEach { line ->
            val separator = line.indexOf("  ")
            if (separator <= 0) {
                problems += "Invalid checksum line: $line"
                return@forEach
            }
            val expected = line.substring(0, separator)
            val relative = line.substring(separator + 2)
            val file = File(directory, relative)
            when {
                !file.exists() -> problems += "Missing file: $relative"
                sha256(file) != expected -> problems += "Checksum mismatch: $relative"
            }
        }
        return problems
    }

    fun writeChecksums(directory: File) {
        val checksumFile = File(directory, "checksums.sha256")
        val lines = directory.walkTopDown()
            .filter { it.isFile && it != checksumFile }
            .sortedBy { it.relativeTo(directory).invariantSeparatorsPath }
            .map { file -> "${sha256(file)}  ${file.relativeTo(directory).invariantSeparatorsPath}" }
            .toList()
        checksumFile.writeText(lines.joinToString("\n", postfix = if (lines.isEmpty()) "" else "\n"))
    }

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
