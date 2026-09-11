package com.propertytour360.capture.util

import com.propertytour360.capture.model.MeasurementDraft
import com.propertytour360.capture.model.OpeningDraft
import com.propertytour360.capture.model.PlanPoint
import com.propertytour360.capture.model.RoomDraft
import kotlin.math.cos
import kotlin.math.sin

object DesignModelBuilder {
    fun buildProjectModel(rooms: List<RoomDraft>): Map<String, Any> {
        val builtRooms = rooms.map(::buildRoomModel)
        val floorIds = rooms.map { it.placement.floorId }.distinct()
        val floors = floorIds.map { floorId ->
            mapOf(
                "id" to floorId,
                "name" to floorId.replace('-', ' ').replaceFirstChar { it.uppercase() },
                "elevationM" to (rooms.firstOrNull { it.placement.floorId == floorId }?.placement?.elevationM ?: 0.0),
                "roomIds" to rooms.filter { it.placement.floorId == floorId }.map { it.serverId }
            )
        }
        val bounds = builtRooms.flatMap { (it["floorPolygon"] as List<*>).filterIsInstance<List<Double>>() }
        return mapOf(
            "schemaVersion" to "2.1",
            "units" to "meters",
            "coordinateSystem" to "RIGHT_HANDED_Y_UP",
            "structure" to mapOf(
                "id" to "structure-1",
                "globalOrientationDegrees" to 0.0,
                "captureSourceIds" to rooms.mapNotNull { it.arEvidenceDir?.name },
                "bounds" to computeBounds(bounds)
            ),
            "floors" to floors,
            "rooms" to builtRooms,
            "metadata" to mapOf(
                "source" to "android_capture_package_v2_plus_field_plan",
                "draft" to true,
                "scaleStatus" to "MEASURED_DRAFT",
                "geometryStatus" to "DRAFT_MODEL",
                "verificationStatus" to "DESIGNER_REVIEW_REQUIRED",
                "structuralVerificationRequired" to true,
                "sourceEvidencePreserved" to true,
                "spatialIdentityLinked" to rooms.all { !it.spatialRoomId.isNullOrBlank() },
                "spatialRoomIds" to rooms.mapNotNull { it.spatialRoomId },
                "mobilePlanConfirmed" to rooms.all { it.floorPolygon.size >= 3 }
            )
        )
    }

    fun buildRoomModel(room: RoomDraft): Map<String, Any> {
        val local = when {
            room.floorPolygon.size >= 3 -> room.floorPolygon
            room.lengthM != null && room.widthM != null -> rectangle(room.lengthM, room.widthM)
            else -> error("Room ${room.name} requires a valid polygon or dimensions")
        }
        val global = local.map { transform(it, room.placement.originXM, room.placement.originZM, room.placement.rotationDegrees) }
        val walls = global.indices.map { index ->
            val wallOpenings = room.openings.filter { it.wallIndex == index }.map(::openingMap)
            mapOf(
                "id" to "${room.serverId}-wall-${index + 1}",
                "start" to listOf(global[index].xM, global[index].zM),
                "end" to listOf(global[(index + 1) % global.size].xM, global[(index + 1) % global.size].zM),
                "heightM" to (room.heightM ?: 2.8),
                "thicknessM" to 0.12,
                "material" to "Warm White",
                "structuralStatus" to "UNKNOWN",
                "confidence" to if (room.arEvidenceDir != null) 0.65 else 0.5,
                "source" to if (room.arEvidenceDir != null) "ARCORE_PLUS_OPERATOR" else "MANUAL_MEASUREMENT",
                "evidenceRefs" to listOfNotNull(room.arEvidenceDir?.name),
                "openings" to wallOpenings
            )
        }
        return mapOf(
            "id" to room.serverId,
            "spatialRoomId" to room.spatialRoomId,
            "name" to room.name,
            "floorId" to room.placement.floorId,
            "transform" to mapOf(
                "originM" to listOf(room.placement.originXM, room.placement.elevationM, room.placement.originZM),
                "rotationDegrees" to room.placement.rotationDegrees,
                "connectionAnchorId" to room.placement.connectionAnchorId,
                "connectionConfidence" to room.placement.connectionConfidence
            ),
            "heightM" to (room.heightM ?: 2.8),
            "scaleStatus" to "MEASURED_DRAFT",
            "verificationStatus" to "DESIGNER_REVIEW_REQUIRED",
            "confidence" to if (room.arEvidenceDir != null) 0.65 else 0.5,
            "evidenceCoverage" to mapOf("capturePackagePresent" to (room.arEvidenceDir != null), "qualityScore" to room.scanQualityScore),
            "floorPolygon" to global.map { listOf(it.xM, it.zM) },
            "walls" to walls,
            "measurements" to room.measurements.map(::measurementMap),
            "objects" to emptyList<Map<String, Any>>()
        )
    }

    fun rectangle(length: Double, width: Double) = listOf(
        PlanPoint(0.0, 0.0), PlanPoint(length, 0.0), PlanPoint(length, width), PlanPoint(0.0, width)
    )

    fun lShape(length: Double, width: Double, cutLength: Double, cutWidth: Double) = listOf(
        PlanPoint(0.0, 0.0), PlanPoint(length, 0.0), PlanPoint(length, width - cutWidth),
        PlanPoint(length - cutLength, width - cutWidth), PlanPoint(length - cutLength, width), PlanPoint(0.0, width)
    )

    private fun openingMap(opening: OpeningDraft): Map<String, Any?> = mapOf(
        "id" to opening.id,
        "type" to opening.type.name,
        "offsetM" to opening.offsetM,
        "widthM" to opening.widthM,
        "heightM" to opening.heightM,
        "sillM" to opening.sillM,
        "swing" to opening.swing,
        "confidence" to opening.confidence,
        "source" to opening.source,
        "verificationStatus" to "OPERATOR_CONFIRMED"
    )

    private fun measurementMap(m: MeasurementDraft): Map<String, Any?> = mapOf(
        "id" to m.id,
        "label" to m.label,
        "valueM" to m.valueM,
        "unit" to "m",
        "method" to m.method,
        "toleranceM" to m.toleranceM,
        "start" to m.start?.let { listOf(it.xM, it.zM) },
        "end" to m.end?.let { listOf(it.xM, it.zM) },
        "verified" to m.verified,
        "verificationStatus" to m.verificationStatus,
        "operatorId" to m.operatorId,
        "deviceManufacturer" to m.deviceManufacturer,
        "deviceModel" to m.deviceModel,
        "evidenceRefs" to m.evidenceRefs,
        "notes" to m.notes,
        "capturedAtEpochMs" to m.capturedAtEpochMs,
        "source" to "ANDROID_FIELD_CONFIRMATION"
    )

    private fun transform(p: PlanPoint, ox: Double, oz: Double, degrees: Double): PlanPoint {
        val r = Math.toRadians(degrees)
        return PlanPoint(ox + p.xM * cos(r) - p.zM * sin(r), oz + p.xM * sin(r) + p.zM * cos(r))
    }

    private fun computeBounds(points: List<List<Double>>): Map<String, Any> {
        if (points.isEmpty()) return mapOf("min" to listOf(0.0, 0.0), "max" to listOf(0.0, 0.0))
        return mapOf(
            "min" to listOf(points.minOf { it[0] }, points.minOf { it[1] }),
            "max" to listOf(points.maxOf { it[0] }, points.maxOf { it[1] })
        )
    }
}
