package com.propertytour360.capture.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.propertytour360.capture.model.*
import com.propertytour360.capture.util.DesignModelBuilder
import kotlin.math.hypot

@Composable
fun ModeBPlanEditorDialog(
    room: RoomDraft,
    onDismiss: () -> Unit,
    onSave: (List<PlanPoint>, Double, List<OpeningDraft>, List<MeasurementDraft>, RoomPlacement) -> Unit
) {
    var shape by remember { mutableStateOf(if (room.floorPolygon.size >= 3) "FREE" else "RECTANGLE") }
    var length by remember { mutableStateOf((room.lengthM ?: 4.0).toString()) }
    var width by remember { mutableStateOf((room.widthM ?: 3.0).toString()) }
    var cutLength by remember { mutableStateOf("1.2") }
    var cutWidth by remember { mutableStateOf("1.0") }
    var height by remember { mutableStateOf((room.heightM ?: 2.8).toString()) }
    var floorId by remember { mutableStateOf(room.placement.floorId) }
    var elevation by remember { mutableStateOf(room.placement.elevationM.toString()) }
    var originX by remember { mutableStateOf(room.placement.originXM.toString()) }
    var originZ by remember { mutableStateOf(room.placement.originZM.toString()) }
    var rotation by remember { mutableStateOf(room.placement.rotationDegrees.toString()) }
    var points by remember { mutableStateOf(room.floorPolygon.ifEmpty { DesignModelBuilder.rectangle(4.0, 3.0) }) }
    var openings by remember { mutableStateOf(room.openings) }
    var openingType by remember { mutableStateOf(OpeningType.DOOR) }
    var openingWall by remember { mutableStateOf("1") }
    var openingOffset by remember { mutableStateOf("0.2") }
    var openingWidth by remember { mutableStateOf("0.9") }
    var openingHeight by remember { mutableStateOf("2.1") }
    var sill by remember { mutableStateOf("0.0") }
    var openingSwing by remember { mutableStateOf("") }
    var measurementMethod by remember { mutableStateOf("LASER") }
    var measurementTolerance by remember { mutableStateOf("0.01") }
    var connectionAnchorId by remember { mutableStateOf(room.placement.connectionAnchorId.orEmpty()) }
    var connectionConfidence by remember { mutableStateOf(room.placement.connectionConfidence?.toString().orEmpty()) }

    fun applyTemplate() {
        val l = length.toDoubleOrNull() ?: return
        val w = width.toDoubleOrNull() ?: return
        points = when (shape) {
            "L_SHAPE" -> DesignModelBuilder.lShape(l, w, (cutLength.toDoubleOrNull() ?: 1.0).coerceAtMost(l - .2), (cutWidth.toDoubleOrNull() ?: 1.0).coerceAtMost(w - .2))
            else -> DesignModelBuilder.rectangle(l, w)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Field plan — ${room.name}") },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Create the operator-confirmed parametric draft. Drag polygon vertices in the preview; final correction still happens in Designer Studio.")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = shape == "RECTANGLE", onClick = { shape = "RECTANGLE"; applyTemplate() }, label = { Text("Rectangle") })
                    FilterChip(selected = shape == "L_SHAPE", onClick = { shape = "L_SHAPE"; applyTemplate() }, label = { Text("L-shape") })
                    FilterChip(selected = shape == "FREE", onClick = { shape = "FREE" }, label = { Text("Free") })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DecimalField("Length", length, { length = it }, Modifier.weight(1f))
                    DecimalField("Width", width, { width = it }, Modifier.weight(1f))
                }
                if (shape == "L_SHAPE") Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DecimalField("Cut length", cutLength, { cutLength = it }, Modifier.weight(1f))
                    DecimalField("Cut width", cutWidth, { cutWidth = it }, Modifier.weight(1f))
                }
                Button(onClick = { applyTemplate() }, modifier = Modifier.fillMaxWidth()) { Text("Apply shape") }
                PlanCanvas(points = points, onPointsChanged = { points = it })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        val last = points.lastOrNull() ?: PlanPoint(0.0, 0.0)
                        points = points + PlanPoint(last.xM + 0.5, last.zM + 0.5)
                        shape = "FREE"
                    }, modifier = Modifier.weight(1f)) { Text("Add vertex") }
                    OutlinedButton(onClick = { if (points.size > 3) points = points.dropLast(1) }, modifier = Modifier.weight(1f)) { Text("Remove last") }
                }
                Text("Vertices: " + points.mapIndexed { i, p -> "${i+1}: %.2f,%.2f".format(p.xM,p.zM) }.joinToString("  "), style = MaterialTheme.typography.bodySmall)
                DecimalField("Ceiling height (m)", height, { height = it })
                HorizontalDivider()
                Text("Structure placement", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(floorId, { floorId = it }, label = { Text("Floor ID") }, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DecimalField("Elevation", elevation, { elevation = it }, Modifier.weight(1f))
                    DecimalField("Rotation°", rotation, { rotation = it }, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DecimalField("Origin X", originX, { originX = it }, Modifier.weight(1f))
                    DecimalField("Origin Z", originZ, { originZ = it }, Modifier.weight(1f))
                }
                OutlinedTextField(connectionAnchorId, { connectionAnchorId = it }, label = { Text("Doorway / connection anchor ID (optional)") }, modifier = Modifier.fillMaxWidth())
                DecimalField("Connection confidence 0–1 (optional)", connectionConfidence, { connectionConfidence = it })
                HorizontalDivider()
                Text("Measurement provenance", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("LASER", "TAPE", "AR_ASSISTED", "MANUAL").forEach { method ->
                        FilterChip(selected = measurementMethod == method, onClick = { measurementMethod = method }, label = { Text(method.replace('_', ' ')) })
                    }
                }
                DecimalField("Estimated tolerance (m)", measurementTolerance, { measurementTolerance = it })
                HorizontalDivider()
                Text("Doors, windows and passages", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OpeningType.entries.forEach { type -> FilterChip(selected = openingType == type, onClick = { openingType = type; sill = if (type == OpeningType.WINDOW) "0.9" else "0.0" }, label = { Text(type.name.replace('_',' ')) }) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DecimalField("Wall #", openingWall, { openingWall = it }, Modifier.weight(1f))
                    DecimalField("Offset", openingOffset, { openingOffset = it }, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DecimalField("Width", openingWidth, { openingWidth = it }, Modifier.weight(1f))
                    DecimalField("Height", openingHeight, { openingHeight = it }, Modifier.weight(1f))
                    DecimalField("Sill", sill, { sill = it }, Modifier.weight(1f))
                }
                OutlinedTextField(openingSwing, { openingSwing = it }, label = { Text("Door swing / opening direction (optional)") }, modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = {
                    val wi = (openingWall.toIntOrNull() ?: 1) - 1
                    val candidate = OpeningDraft(type = openingType, wallIndex = wi, offsetM = openingOffset.toDoubleOrNull() ?: 0.0, widthM = openingWidth.toDoubleOrNull() ?: 0.9, heightM = openingHeight.toDoubleOrNull() ?: 2.1, sillM = sill.toDoubleOrNull() ?: 0.0, swing = openingSwing.ifBlank { null })
                    openings = openings + candidate
                }, modifier = Modifier.fillMaxWidth()) { Text("Add opening") }
                openings.forEachIndexed { index, o ->
                    ListItem(
                        headlineContent = { Text("${o.type.name.replace('_',' ')} on wall ${o.wallIndex+1}") },
                        supportingContent = { Text("offset ${o.offsetM}m • ${o.widthM} × ${o.heightM}m • sill ${o.sillM}m") },
                        trailingContent = { TextButton(onClick = { openings = openings.filterIndexed { i, _ -> i != index } }) { Text("Remove") } }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val h = height.toDoubleOrNull() ?: return@TextButton
                val tolerance = (measurementTolerance.toDoubleOrNull() ?: 0.02).coerceIn(0.001, 1.0)
                val measurements = buildList {
                    points.indices.forEach { i ->
                        val a = points[i]; val b = points[(i+1)%points.size]
                        add(MeasurementDraft(label = "Wall ${i+1}", valueM = hypot(b.xM-a.xM,b.zM-a.zM), method = measurementMethod, toleranceM = tolerance, start = a, end = b))
                    }
                    add(MeasurementDraft(label = "Ceiling height", valueM = h, method = measurementMethod, toleranceM = tolerance))
                }
                onSave(points, h, openings, measurements, RoomPlacement(
                    floorId = floorId,
                    elevationM = elevation.toDoubleOrNull() ?: 0.0,
                    originXM = originX.toDoubleOrNull() ?: 0.0,
                    originZM = originZ.toDoubleOrNull() ?: 0.0,
                    rotationDegrees = rotation.toDoubleOrNull() ?: 0.0,
                    connectionAnchorId = connectionAnchorId.ifBlank { null },
                    connectionConfidence = connectionConfidence.toDoubleOrNull()?.coerceIn(0.0, 1.0)
                ))
            }, enabled = points.size >= 3 && height.toDoubleOrNull() != null) { Text("Save field plan") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun PlanCanvas(points: List<PlanPoint>, onPointsChanged: (List<PlanPoint>) -> Unit) {
    Canvas(
        Modifier.fillMaxWidth().height(260.dp).background(Color(0xFF101820)).pointerInput(points) {
            detectDragGestures { change, drag ->
                change.consume()
                val scale = minOf(size.width, size.height).toFloat() / 8f
                val nearest = points.indices.minByOrNull { i ->
                    val p = points[i]; val sx = size.width/2f + p.xM.toFloat()*scale; val sy = size.height/2f - p.zM.toFloat()*scale
                    hypot((change.position.x-sx).toDouble(), (change.position.y-sy).toDouble())
                } ?: return@detectDragGestures
                val updated = points.toMutableList(); val p = updated[nearest]
                updated[nearest] = PlanPoint((p.xM + drag.x/scale).coerceIn(-20.0,20.0), (p.zM - drag.y/scale).coerceIn(-20.0,20.0)); onPointsChanged(updated)
            }
        }
    ) {
        val scale = size.minDimension / 8f
        for (i in -20..20) {
            drawLine(Color(0x2238BDF8), Offset(0f,size.height/2f+i*scale/2), Offset(size.width,size.height/2f+i*scale/2))
            drawLine(Color(0x2238BDF8), Offset(size.width/2f+i*scale/2,0f), Offset(size.width/2f+i*scale/2,size.height))
        }
        if (points.size >= 3) {
            val path = Path(); points.forEachIndexed { i,p -> val x=size.width/2f+p.xM.toFloat()*scale; val y=size.height/2f-p.zM.toFloat()*scale; if(i==0) path.moveTo(x,y) else path.lineTo(x,y) }; path.close()
            drawPath(path, Color(0x5544C2FF)); drawPath(path, Color(0xFF66D9FF), style = androidx.compose.ui.graphics.drawscope.Stroke(4f))
            points.forEach { p -> drawCircle(Color.White, 10f, Offset(size.width/2f+p.xM.toFloat()*scale,size.height/2f-p.zM.toFloat()*scale)) }
        }
    }
}

@Composable
private fun DecimalField(label: String, value: String, onValue: (String)->Unit, modifier: Modifier = Modifier.fillMaxWidth()) = OutlinedTextField(value,onValue,label={Text(label)},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),modifier=modifier)
