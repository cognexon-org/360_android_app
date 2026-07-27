package com.propertytour360.capture.camera

import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.gson.GsonBuilder
import com.propertytour360.capture.util.AngleMath
import kotlinx.coroutines.delay
import java.io.File
import java.text.DecimalFormat

private const val YAW_TOLERANCE_DEGREES = 8f
private const val PITCH_TOLERANCE_DEGREES = 8f
private const val MAX_CAPTURE_SPEED_DEGREES_PER_SECOND = 16f
private const val AUTO_CAPTURE_DWELL_MS = 650L

/**
 * 26-point sphere: three overlapping rings plus zenith and nadir.
 * This creates substantially better spherical coverage than the original 12-frame horizontal-only baseline.
 */
private val CAPTURE_TARGETS: List<CaptureTarget> = buildList {
    listOf(0f, 45f, 90f, 135f, 180f, 225f, 270f, 315f).forEach { add(CaptureTarget(it, 0f, "Horizon")) }
    listOf(22.5f, 67.5f, 112.5f, 157.5f, 202.5f, 247.5f, 292.5f, 337.5f).forEach { add(CaptureTarget(it, -42f, "Upper ring")) }
    listOf(0f, 45f, 90f, 135f, 180f, 225f, 270f, 315f).forEach { add(CaptureTarget(it, 42f, "Lower ring")) }
    add(CaptureTarget(0f, -78f, "Ceiling"))
    add(CaptureTarget(180f, 78f, "Floor"))
}

private data class CaptureTarget(val yawDegrees: Float, val pitchDegrees: Float, val label: String)

private data class CapturedFrameMetadata(
    val fileName: String,
    val targetYawDegrees: Float,
    val targetPitchDegrees: Float,
    val measuredYawDegrees: Float,
    val measuredPitchDegrees: Float,
    val angularSpeedDegreesPerSecond: Float,
    val capturedAtEpochMs: Long
)

@Composable
fun PanoramaCaptureScreen(
    roomId: String,
    onCancel: () -> Unit,
    onComplete: (String, List<File>) -> Unit
) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) permissionLauncher.launch(Manifest.permission.CAMERA) }

    if (!granted) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Camera permission is required")
                Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) { Text("Grant camera") }
                OutlinedButton(onClick = onCancel) { Text("Cancel") }
            }
        }
        return
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    val tracker = remember { OrientationTracker(context) }
    val sample by tracker.sample.collectAsStateWithLifecycle()
    val files = remember { mutableStateListOf<File>() }
    val frameMetadata = remember { mutableStateListOf<CapturedFrameMetadata>() }
    val captureFolder = remember(roomId) {
        File(context.filesDir, "captures/panorama-${System.currentTimeMillis()}-$roomId").apply { mkdirs() }
    }
    var startYaw by remember { mutableStateOf<Float?>(null) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var capturing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var autoCapture by remember { mutableStateOf(true) }
    var alignedSinceMs by remember { mutableLongStateOf(0L) }

    DisposableEffect(Unit) {
        tracker.start()
        onDispose { tracker.stop() }
    }

    val target = CAPTURE_TARGETS.getOrNull(files.size)
    val relativeYaw = startYaw?.let { AngleMath.clockwiseFrom(it, sample.yawDegrees) } ?: 0f
    val yawError = target?.let { AngleMath.angularDistance(relativeYaw, it.yawDegrees) } ?: 0f
    val pitchError = target?.let { kotlin.math.abs(sample.pitchDegrees - it.pitchDegrees) } ?: 0f
    val aligned = target != null && yawError <= YAW_TOLERANCE_DEGREES && pitchError <= PITCH_TOLERANCE_DEGREES
    val stable = sample.angularSpeedDegreesPerSecond <= MAX_CAPTURE_SPEED_DEGREES_PER_SECOND
    val ready = target != null && (files.isEmpty() || (aligned && stable)) && sample.sensorAvailable

    fun captureCurrentTarget() {
        val capture = imageCapture ?: return
        val currentTarget = target ?: return
        if (capturing) return
        if (startYaw == null) startYaw = sample.yawDegrees

        val index = files.size
        val file = File(captureFolder, "frame_${index.toString().padStart(2, '0')}.jpg")
        capturing = true
        alignedSinceMs = 0L
        capture.takePicture(
            ImageCapture.OutputFileOptions.Builder(file).build(),
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    files += file
                    frameMetadata += CapturedFrameMetadata(
                        fileName = file.name,
                        targetYawDegrees = currentTarget.yawDegrees,
                        targetPitchDegrees = currentTarget.pitchDegrees,
                        measuredYawDegrees = relativeYaw,
                        measuredPitchDegrees = sample.pitchDegrees,
                        angularSpeedDegreesPerSecond = sample.angularSpeedDegreesPerSecond,
                        capturedAtEpochMs = System.currentTimeMillis()
                    )
                    capturing = false
                    error = null
                }

                override fun onError(exception: ImageCaptureException) {
                    capturing = false
                    error = exception.message ?: "Camera capture failed"
                }
            }
        )
    }

    LaunchedEffect(ready, autoCapture, files.size, capturing) {
        if (!ready || !autoCapture || capturing || files.isEmpty()) {
            alignedSinceMs = 0L
            return@LaunchedEffect
        }
        alignedSinceMs = SystemClock.elapsedRealtime()
        while (ready && autoCapture && !capturing) {
            val elapsed = SystemClock.elapsedRealtime() - alignedSinceMs
            if (elapsed >= AUTO_CAPTURE_DWELL_MS) {
                captureCurrentTarget()
                break
            }
            delay(50)
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).also { previewView ->
                    previewView.scaleType = PreviewView.ScaleType.FILL_CENTER
                    val providerFuture = ProcessCameraProvider.getInstance(ctx)
                    providerFuture.addListener({
                        try {
                            val provider = providerFuture.get()
                            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                            val capture = ImageCapture.Builder()
                                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                                .setJpegQuality(95)
                                .setFlashMode(ImageCapture.FLASH_MODE_OFF)
                                .setTargetRotation(previewView.display?.rotation ?: android.view.Surface.ROTATION_0)
                                .build()
                            provider.unbindAll()
                            provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
                            imageCapture = capture
                        } catch (throwable: Throwable) {
                            error = throwable.message
                        }
                    }, ContextCompat.getMainExecutor(ctx))
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        TargetReticle(
            yawErrorDegrees = target?.let { AngleMath.normalizeSignedDegrees(it.yawDegrees - relativeYaw) } ?: 0f,
            pitchErrorDegrees = target?.let { it.pitchDegrees - sample.pitchDegrees } ?: 0f,
            ready = ready,
            modifier = Modifier.align(Alignment.Center).size(260.dp)
        )

        Column(
            Modifier.fillMaxSize().padding(18.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(onClick = onCancel) { Text("Cancel", color = Color.White) }
                Text("${files.size}/${CAPTURE_TARGETS.size}", color = Color.White, style = MaterialTheme.typography.titleLarge)
            }

            Column(
                Modifier.fillMaxWidth().background(Color(0xCC000000)).padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val instruction = when {
                    !sample.sensorAvailable -> "Orientation sensor unavailable — use manual panorama import"
                    target == null -> "Sphere complete"
                    files.isEmpty() -> "Hold the phone upright and capture the first view"
                    !aligned -> "Move the target into the centre ring"
                    !stable -> "Hold still"
                    else -> "Aligned — hold steady"
                }
                Text(target?.let { "${it.label}: ${it.yawDegrees.toInt()}° / ${it.pitchDegrees.toInt()}°" } ?: "Capture complete",
                    color = Color.White, style = MaterialTheme.typography.titleMedium)
                Text(instruction, color = if (ready) Color(0xFF82E6A1) else Color.White)
                if (target != null) {
                    Text(
                        "Yaw error ${DecimalFormat("0.0").format(yawError)}° • pitch error ${DecimalFormat("0.0").format(pitchError)}° • speed ${DecimalFormat("0").format(sample.angularSpeedDegreesPerSecond)}°/s",
                        color = Color.LightGray,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                error?.let { Text(it, color = Color(0xFFFF8A80)) }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Auto capture", color = Color.White)
                    Spacer(Modifier.weight(1f))
                    Switch(checked = autoCapture, onCheckedChange = { autoCapture = it })
                }
                Spacer(Modifier.height(6.dp))

                if (target != null) {
                    Button(
                        onClick = { captureCurrentTarget() },
                        enabled = !capturing && imageCapture != null && (files.isEmpty() || ready),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (capturing) CircularProgressIndicator(modifier = Modifier.height(20.dp))
                        else Text(if (files.isEmpty()) "Set reference and capture" else "Capture this direction")
                    }
                } else {
                    Button(
                        onClick = {
                            val manifest = File(captureFolder, "capture_manifest.json")
                            manifest.writeText(
                                GsonBuilder().setPrettyPrinting().create().toJson(
                                    mapOf(
                                        "schemaVersion" to 2,
                                        "roomId" to roomId,
                                        "capturePattern" to "PT360_SPHERE_26",
                                        "frameCount" to files.size,
                                        "frames" to frameMetadata.toList()
                                    )
                                )
                            )
                            onComplete(roomId, files.toList())
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Use complete 360° capture") }
                }

                if (files.isNotEmpty() && target != null) {
                    OutlinedButton(
                        onClick = {
                            files.removeLastOrNull()?.delete()
                            frameMetadata.removeLastOrNull()
                            if (files.isEmpty()) startYaw = null
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Retake previous", color = Color.White) }
                }
            }
        }
    }
}

@Composable
private fun TargetReticle(
    yawErrorDegrees: Float,
    pitchErrorDegrees: Float,
    ready: Boolean,
    modifier: Modifier = Modifier
) {
    Canvas(modifier) {
        val centre = Offset(size.width / 2f, size.height / 2f)
        val maxOffset = size.minDimension * 0.38f
        val x = (yawErrorDegrees / 45f).coerceIn(-1f, 1f) * maxOffset
        val y = (-pitchErrorDegrees / 45f).coerceIn(-1f, 1f) * maxOffset
        drawCircle(
            color = if (ready) Color(0xFF67E08B) else Color.White,
            radius = size.minDimension * 0.095f,
            center = centre,
            style = Stroke(width = 6f)
        )
        drawCircle(
            color = if (ready) Color(0xFF67E08B) else Color(0xFFFFC857),
            radius = size.minDimension * 0.045f,
            center = centre + Offset(x, y)
        )
    }
}