package com.propertytour360.capture.camera

import android.Manifest
import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
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
import com.propertytour360.capture.model.PanoramaCapturePattern
import com.propertytour360.capture.model.PanoramaCaptureResult
import com.propertytour360.capture.model.PanoramaFrameMetadata
import com.propertytour360.capture.util.AngleMath
import kotlinx.coroutines.delay
import java.io.File
import java.text.DecimalFormat

// Tighter than the old 7°: the server-side feature matcher needs neighbouring
// frames to genuinely overlap, and two adjacent frames each 7° off in opposite
// directions used to erase the overlap entirely. 3.5° with the denser ring keeps
// worst-case overlap above one third of a frame.
private const val YAW_TOLERANCE_DEGREES = 3.5f
private const val PITCH_TOLERANCE_DEGREES = 3.5f
private const val MAX_CAPTURE_SPEED_DEGREES_PER_SECOND = 15f
private const val AUTO_CAPTURE_DWELL_MS = 600L
private const val UPPER_RING_PITCH_DEGREES = -30f
private const val LOWER_RING_PITCH_DEGREES = 30f

private data class CaptureTarget(
    val yawDegrees: Float,
    val pitchDegrees: Float,
    val label: String
)

private fun buildCaptureTargets(
    pattern: PanoramaCapturePattern,
    ringFrameCount: Int
): List<CaptureTarget> {
    val step = 360f / ringFrameCount
    return when (pattern) {
        PanoramaCapturePattern.QUICK_CENTRAL_RING -> List(ringFrameCount) { index ->
            CaptureTarget(index * step, 0f, "Central ring")
        }

        PanoramaCapturePattern.FULL_TWO_RINGS_WITH_CAPS -> buildList {
            List(ringFrameCount) { index -> index * step }.forEach {
                add(CaptureTarget(it, UPPER_RING_PITCH_DEGREES, "Upper ring"))
            }
            // Offset the second ring so vertical seams do not all meet at the same longitudes.
            List(ringFrameCount) { index -> (index * step + step / 2f) % 360f }.forEach {
                add(CaptureTarget(it, LOWER_RING_PITCH_DEGREES, "Lower ring"))
            }
            add(CaptureTarget(0f, -82f, "Ceiling"))
            add(CaptureTarget(180f, 82f, "Floor"))
        }
    }
}

/**
 * Lock or unlock auto-exposure and auto-white-balance.
 *
 * Every frame of a panorama should be metered identically: if CameraX re-meters
 * per shot, a bright window in one frame darkens that frame's walls, and the
 * server can only correct per-channel gain, not the colour shift. Locking after
 * the first (reference) frame makes all subsequent frames photometrically
 * consistent, which also makes the server's seam finder converge on cleaner cuts.
 */
@OptIn(ExperimentalCamera2Interop::class)
private fun setExposureLock(camera: Camera?, locked: Boolean) {
    val control = camera?.cameraControl ?: return
    runCatching {
        Camera2CameraControl.from(control).setCaptureRequestOptions(
            CaptureRequestOptions.Builder()
                .setCaptureRequestOption(android.hardware.camera2.CaptureRequest.CONTROL_AE_LOCK, locked)
                .setCaptureRequestOption(android.hardware.camera2.CaptureRequest.CONTROL_AWB_LOCK, locked)
                .build()
        )
    }
}

@Composable
fun PanoramaCaptureScreen(
    roomId: String,
    pattern: PanoramaCapturePattern,
    onCancel: () -> Unit,
    onComplete: (PanoramaCaptureResult) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val activity = context as? Activity
    val fov = remember { CameraFovEstimator.estimateNormalBackCameraPortrait(context) }
    val ringFrameCount = remember(fov.horizontalDegrees) {
        CameraFovEstimator.recommendedRingFrameCount(fov.horizontalDegrees)
    }
    val captureTargets = remember(pattern, ringFrameCount) {
        buildCaptureTargets(pattern, ringFrameCount)
    }
    val quickPitchLimit = remember(fov.verticalDegrees) {
        (fov.verticalDegrees / 2f - 4f).coerceIn(25f, 42f)
    }
    val minPitchDegrees = if (pattern.fullSphere) -90f else -quickPitchLimit
    val maxPitchDegrees = if (pattern.fullSphere) 90f else quickPitchLimit

    DisposableEffect(activity) {
        val oldOrientation = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        onDispose {
            if (oldOrientation != null) activity?.requestedOrientation = oldOrientation
        }
    }

    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
    }
    LaunchedEffect(Unit) {
        if (!granted) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    if (!granted) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Camera permission is required")
                Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                    Text("Grant camera")
                }
                OutlinedButton(onClick = onCancel) { Text("Cancel") }
            }
        }
        return
    }

    val tracker = remember { OrientationTracker(context) }
    val sample by tracker.sample.collectAsStateWithLifecycle()
    val files = remember { mutableStateListOf<File>() }
    val frameMetadata = remember { mutableStateListOf<PanoramaFrameMetadata>() }
    val captureFolder = remember(roomId, pattern) {
        File(
            context.filesDir,
            "captures/panorama-${System.currentTimeMillis()}-${pattern.apiValue.lowercase()}-$roomId"
        ).apply { mkdirs() }
    }
    var startYaw by remember { mutableStateOf<Float?>(null) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var boundCamera by remember { mutableStateOf<Camera?>(null) }
    var capturing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var autoCapture by remember { mutableStateOf(true) }
    var alignedSinceMs by remember { mutableLongStateOf(0L) }

    DisposableEffect(Unit) {
        tracker.start()
        onDispose { tracker.stop() }
    }

    val target = captureTargets.getOrNull(files.size)
    val relativeYaw = startYaw?.let { AngleMath.clockwiseFrom(it, sample.yawDegrees) } ?: 0f
    val yawError = target?.let { AngleMath.angularDistance(relativeYaw, it.yawDegrees) } ?: 0f
    val pitchError = target?.let { kotlin.math.abs(sample.pitchDegrees - it.pitchDegrees) } ?: 0f
    val aligned = target != null &&
        yawError <= YAW_TOLERANCE_DEGREES &&
        pitchError <= PITCH_TOLERANCE_DEGREES
    val stable = sample.angularSpeedDegreesPerSecond <= MAX_CAPTURE_SPEED_DEGREES_PER_SECOND
    val ready = target != null &&
        sample.sensorAvailable &&
        if (files.isEmpty()) pitchError <= PITCH_TOLERANCE_DEGREES && stable else aligned && stable

    fun captureCurrentTarget() {
        val capture = imageCapture ?: return
        val currentTarget = target ?: return
        if (capturing || !sample.sensorAvailable) return

        val orientationAtShutter = sample
        val referenceYaw = startYaw ?: orientationAtShutter.yawDegrees.also { startYaw = it }
        val measuredRelativeYaw = AngleMath.clockwiseFrom(referenceYaw, orientationAtShutter.yawDegrees)
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
                    if (files.size == 1) {
                        // The first frame is the photometric reference; freeze
                        // exposure and white balance for the rest of the sweep.
                        setExposureLock(boundCamera, true)
                    }
                    frameMetadata += PanoramaFrameMetadata(
                        fileName = file.name,
                        targetYawDegrees = currentTarget.yawDegrees,
                        targetPitchDegrees = currentTarget.pitchDegrees,
                        measuredYawDegrees = measuredRelativeYaw,
                        measuredPitchDegrees = orientationAtShutter.pitchDegrees,
                        measuredRollDegrees = orientationAtShutter.rollDegrees,
                        angularSpeedDegreesPerSecond = orientationAtShutter.angularSpeedDegreesPerSecond,
                        sensorTimestampNs = orientationAtShutter.timestampNs,
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
            if (SystemClock.elapsedRealtime() - alignedSinceMs >= AUTO_CAPTURE_DWELL_MS) {
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
                            val preview = Preview.Builder().build().also {
                                it.setSurfaceProvider(previewView.surfaceProvider)
                            }
                            val capture = ImageCapture.Builder()
                                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                                .setJpegQuality(95)
                                .setFlashMode(ImageCapture.FLASH_MODE_OFF)
                                .setTargetRotation(
                                    previewView.display?.rotation ?: android.view.Surface.ROTATION_0
                                )
                                .build()
                            provider.unbindAll()
                            val camera = provider.bindToLifecycle(
                                lifecycleOwner,
                                CameraSelector.DEFAULT_BACK_CAMERA,
                                preview,
                                capture
                            )
                            boundCamera = camera
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
            yawErrorDegrees = target?.let {
                AngleMath.normalizeSignedDegrees(it.yawDegrees - relativeYaw)
            } ?: 0f,
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
                Column(horizontalAlignment = Alignment.End) {
                    Text(pattern.title, color = Color.White, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${files.size}/${captureTargets.size}",
                        color = Color.White,
                        style = MaterialTheme.typography.titleLarge
                    )
                }
            }

            Column(
                Modifier.fillMaxWidth().background(Color(0xCC000000)).padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val instruction = when {
                    !sample.sensorAvailable -> "Orientation sensor unavailable — import a panorama instead"
                    target == null -> if (pattern.fullSphere) "Full sphere complete" else "Horizontal 360° complete"
                    files.isEmpty() -> "Stand near the room centre and capture the first view"
                    !aligned -> when (target.label) {
                        "Upper ring" -> "Tilt slightly upward and move the target into the centre"
                        "Lower ring" -> "Tilt slightly downward and move the target into the centre"
                        "Ceiling" -> "Point directly at the ceiling"
                        "Floor" -> "Point directly at the floor"
                        else -> "Keep the phone level and move the target into the centre"
                    }
                    !stable -> "Hold still"
                    else -> "Aligned — hold steady"
                }

                Text(
                    target?.let {
                        "${it.label}: ${it.yawDegrees.toInt()}° / ${it.pitchDegrees.toInt()}°"
                    } ?: "Capture complete",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(instruction, color = if (ready) Color(0xFF82E6A1) else Color.White)
                if (target != null) {
                    Text(
                        "Yaw ${DecimalFormat("0.0").format(yawError)}° • " +
                            "pitch ${DecimalFormat("0.0").format(pitchError)}° • " +
                            "speed ${DecimalFormat("0").format(sample.angularSpeedDegreesPerSecond)}°/s",
                        color = Color.LightGray,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Text(
                    "$ringFrameCount positions per ring • normal 1× camera • portrait",
                    color = Color.LightGray,
                    style = MaterialTheme.typography.bodySmall
                )
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
                        enabled = !capturing &&
                            imageCapture != null &&
                            sample.sensorAvailable &&
                            (files.isEmpty() || ready),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (capturing) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Text(if (files.isEmpty()) "Set reference and capture" else "Capture this direction")
                        }
                    }
                } else {
                    Button(
                        onClick = {
                            val manifest = File(captureFolder, "capture_manifest.json")
                            val manifestData = mapOf(
                                "schemaVersion" to 3,
                                "roomId" to roomId,
                                "capturePattern" to pattern.apiValue,
                                "ringFrameCount" to ringFrameCount,
                                "frameCount" to files.size,
                                "horizontalFovDegrees" to fov.horizontalDegrees,
                                "verticalFovDegrees" to fov.verticalDegrees,
                                "minPitchDegrees" to minPitchDegrees,
                                "maxPitchDegrees" to maxPitchDegrees,
                                "frames" to frameMetadata.toList()
                            )
                            manifest.writeText(
                                GsonBuilder().setPrettyPrinting().create().toJson(manifestData)
                            )
                            onComplete(
                                PanoramaCaptureResult(
                                    files = files.toList(),
                                    manifestFile = manifest,
                                    pattern = pattern,
                                    frames = frameMetadata.toList(),
                                    horizontalFovDegrees = fov.horizontalDegrees,
                                    verticalFovDegrees = fov.verticalDegrees,
                                    minPitchDegrees = minPitchDegrees,
                                    maxPitchDegrees = maxPitchDegrees
                                )
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (pattern.fullSphere) "Use full room sphere" else "Use quick room view")
                    }
                }

                if (files.isNotEmpty() && target != null) {
                    OutlinedButton(
                        onClick = {
                            files.removeLastOrNull()?.delete()
                            frameMetadata.removeLastOrNull()
                            if (files.isEmpty()) {
                                startYaw = null
                                // Back to no reference frame: let the camera
                                // re-meter for the fresh first shot.
                                setExposureLock(boundCamera, false)
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Retake previous", color = Color.White)
                    }
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
        // 20° full-scale (was 45°): with the tighter 3.5° tolerance the dot must
        // visibly respond to small corrections or aligning feels like guesswork.
        val x = (yawErrorDegrees / 20f).coerceIn(-1f, 1f) * maxOffset
        val y = (-pitchErrorDegrees / 20f).coerceIn(-1f, 1f) * maxOffset
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
