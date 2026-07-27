package com.propertytour360.capture.util

import com.propertytour360.capture.model.RoomDraft

object DesignModelBuilder {
    fun buildProjectModel(rooms: List<RoomDraft>): Map<String, Any> = mapOf(
        "units" to "meters",
        "rooms" to rooms.map { room ->
            buildRoomModel(
                room.serverId, room.name,
                requireNotNull(room.lengthM), requireNotNull(room.widthM), requireNotNull(room.heightM),
                room.doorWidthM, room.doorHeightM, room.windowWidthM, room.windowHeightM
            )
        },
        "metadata" to mapOf(
            "source" to "android_arcore_plus_manual_confirmation",
            "draft" to true,
            "scaleStatus" to "MEASURED_DRAFT",
            "geometryStatus" to "DESIGNER_CONFIRMATION_REQUIRED",
            "structuralVerificationRequired" to true,
            "sourceEvidencePreserved" to true
        )
    )

    fun buildRoomModel(
        id: String,
        name: String,
        length: Double,
        width: Double,
        height: Double,
        doorWidth: Double?,
        doorHeight: Double?,
        windowWidth: Double?,
        windowHeight: Double?
    ): Map<String, Any> {
        fun wall(
            wallId: String,
            start: List<Double>,
            end: List<Double>,
            openings: List<Map<String, Any>> = emptyList()
        ) = mapOf(
            "id" to wallId,
            "start" to start,
            "end" to end,
            "thicknessM" to 0.12,
            "material" to "existing",
            "structuralStatus" to "UNKNOWN",
            "openings" to openings
        )
        val door = if (doorWidth != null && doorHeight != null) listOf(
            mapOf(
                "id" to "door-$id-1", "type" to "DOOR", "offsetM" to 0.25,
                "widthM" to doorWidth, "heightM" to doorHeight, "bottomM" to 0.0
            )
        ) else emptyList()
        val window = if (windowWidth != null && windowHeight != null) listOf(
            mapOf(
                "id" to "window-$id-1", "type" to "WINDOW", "offsetM" to 0.5,
                "widthM" to windowWidth, "heightM" to windowHeight, "bottomM" to 0.9
            )
        ) else emptyList()
        return mapOf(
            "id" to id,
            "name" to name,
            "heightM" to height,
            "scaleStatus" to "MEASURED_DRAFT",
            "confidence" to "MANUAL_CONFIRMED_DIMENSIONS",
            "floorPolygon" to listOf(
                listOf(0.0, 0.0), listOf(length, 0.0),
                listOf(length, width), listOf(0.0, width)
            ),
            "walls" to listOf(
                wall("$id-w1", listOf(0.0, 0.0), listOf(length, 0.0), door),
                wall("$id-w2", listOf(length, 0.0), listOf(length, width), window),
                wall("$id-w3", listOf(length, width), listOf(0.0, width)),
                wall("$id-w4", listOf(0.0, width), listOf(0.0, 0.0))
            ),
            "objects" to emptyList<Map<String, Any>>()
        )
    }
}
