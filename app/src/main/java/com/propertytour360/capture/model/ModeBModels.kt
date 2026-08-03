package com.propertytour360.capture.model

import java.util.UUID

data class PlanPoint(val xM: Double, val zM: Double)

enum class OpeningType { DOOR, WINDOW, OPEN_PASSAGE }

data class OpeningDraft(
    val id: String = UUID.randomUUID().toString(),
    val type: OpeningType,
    val wallIndex: Int,
    val offsetM: Double,
    val widthM: Double,
    val heightM: Double,
    val sillM: Double = if (type == OpeningType.WINDOW) 0.9 else 0.0,
    val swing: String? = null,
    val confidence: Double = 1.0,
    val source: String = "OPERATOR_MARKUP"
)

data class MeasurementDraft(
    val id: String = UUID.randomUUID().toString(),
    val label: String,
    val valueM: Double,
    val method: String,
    val toleranceM: Double,
    val start: PlanPoint? = null,
    val end: PlanPoint? = null,
    val verified: Boolean = true,
    val verificationStatus: String = "OPERATOR_CONFIRMED",
    val operatorId: String? = null,
    val deviceManufacturer: String? = null,
    val deviceModel: String? = null,
    val evidenceRefs: List<String> = emptyList(),
    val notes: String? = null,
    val capturedAtEpochMs: Long = System.currentTimeMillis()
)

data class RoomPlacement(
    val floorId: String = "floor-1",
    val elevationM: Double = 0.0,
    val originXM: Double = 0.0,
    val originZM: Double = 0.0,
    val rotationDegrees: Double = 0.0,
    val connectionAnchorId: String? = null,
    val connectionConfidence: Double? = null
)
