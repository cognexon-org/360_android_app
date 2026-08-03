package com.propertytour360.capture.ar

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.Gravity
import android.view.Surface
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableException
import java.io.File

class ArScanActivity : ComponentActivity() {
    private var session: Session? = null
    private lateinit var surfaceView: GLSurfaceView
    private lateinit var statusView: TextView
    private lateinit var recorder: ArEvidenceRecorder
    private var installRequested = false
    private var depthSupported = false
    private var finishingNormally = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val roomName = intent.getStringExtra(EXTRA_ROOM_NAME) ?: "Room"
        val output = File(filesDir, "captures/ar-${System.currentTimeMillis()}-${roomName.replace(' ', '_')}")
        recorder = ArEvidenceRecorder(output, roomName)

        surfaceView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(3)
            preserveEGLContextOnPause = true
        }
        statusView = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(0x99000000.toInt())
            setPadding(24, 20, 24, 20)
            text = "Starting ARCore…"
        }
        fun markupButton(title: String, type: String) = Button(this).apply {
            text = title
            setOnClickListener {
                recorder.addOperatorMarkup(type)
                statusView.text = "$title proposal recorded at the centre reticle"
            }
        }
        fun markupRow(vararg actions: Pair<String, String>) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            actions.forEach { (title, type) ->
                addView(markupButton(title, type), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
        }
        val markupControls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(markupRow("Corner" to "WALL_CORNER", "Door" to "DOOR", "Window" to "WINDOW", "Passage" to "OPEN_PASSAGE"))
            addView(markupRow("Floor" to "FLOOR_POINT", "Ceiling" to "CEILING_POINT", "Stair" to "STAIR", "Level" to "LEVEL_CHANGE"))
            addView(markupRow("Measure A" to "MEASUREMENT_START", "Measure B" to "MEASUREMENT_END"))
        }
        val finishButton = Button(this).apply {
            text = "Finish room scan"
            setOnClickListener { finishScan() }
        }
        val notice = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(0x99000000.toInt())
            setPadding(20, 12, 20, 12)
            text = "Keep the feature inside the centre reticle. Walk the perimeter slowly, mark corners/openings, capture floor and ceiling boundaries, and use Measure A/B for trusted AR endpoint proposals. Tape or laser confirmation is still recommended."
        }
        val overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(notice, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(statusView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(markupControls, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(finishButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(20, 16, 20, 20)
            })
        }
        val reticle = TextView(this).apply {
            text = "+"
            textSize = 34f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setShadowLayer(6f, 0f, 0f, Color.BLACK)
        }
        val root = FrameLayout(this).apply {
            addView(surfaceView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(reticle, FrameLayout.LayoutParams(84, 84, Gravity.CENTER))
            addView(overlay, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        }
        setContentView(root)

        surfaceView.setRenderer(
            ArCaptureRenderer(
                context = this,
                sessionProvider = { session },
                displayRotationProvider = { currentRotation() },
                recorder = recorder,
                onStatus = { text -> runOnUiThread { statusView.text = text } },
                onFatalError = { text -> runOnUiThread { statusView.text = text } }
            )
        )
        surfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

        if (!hasCameraPermission()) requestCameraPermission()
    }

    override fun onResume() {
        super.onResume()
        if (!hasCameraPermission()) return
        if (session == null) {
            try {
                when (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
                    ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                        installRequested = true
                        statusView.text = "Install or update Google Play Services for AR, then return"
                        return
                    }
                    ArCoreApk.InstallStatus.INSTALLED -> Unit
                }
                session = Session(this).also { arSession ->
                    val config = Config(arSession).apply {
                        planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                        focusMode = Config.FocusMode.AUTO
                        updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                        if (arSession.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                            depthMode = Config.DepthMode.AUTOMATIC
                            depthSupported = true
                        } else if (arSession.isDepthModeSupported(Config.DepthMode.RAW_DEPTH_ONLY)) {
                            depthMode = Config.DepthMode.RAW_DEPTH_ONLY
                            depthSupported = true
                        } else {
                            depthMode = Config.DepthMode.DISABLED
                        }
                    }
                    arSession.configure(config)
                }
            } catch (error: UnavailableException) {
                statusView.text = "ARCore is unavailable on this phone. Use manual measurements."
                return
            } catch (error: Throwable) {
                statusView.text = "Unable to start ARCore: ${error.message}"
                return
            }
        }
        try {
            session?.resume()
            surfaceView.onResume()
        } catch (error: CameraNotAvailableException) {
            statusView.text = "Camera unavailable: ${error.message}"
        }
    }

    override fun onPause() {
        super.onPause()
        surfaceView.onPause()
        session?.pause()
    }

    override fun onDestroy() {
        if (!finishingNormally) recorder.close()
        session?.close()
        session = null
        super.onDestroy()
    }

    private fun finishScan() {
        finishingNormally = true
        recorder.close()
        val result = Intent()
            .putExtra(EXTRA_OUTPUT_DIR, recorder.outputDirectory.absolutePath)
            .putExtra(EXTRA_DEPTH_SUPPORTED, depthSupported)
        setResult(Activity.RESULT_OK, result)
        finish()
    }

    private fun hasCameraPermission() = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun requestCameraPermission() {
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_CODE)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_CODE && grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
            statusView.text = "Camera permission denied"
        }
    }

    @Suppress("DEPRECATION")
    private fun currentRotation(): Int = if (android.os.Build.VERSION.SDK_INT >= 30) {
        display?.rotation ?: Surface.ROTATION_0
    } else {
        windowManager.defaultDisplay.rotation
    }

    companion object {
        const val EXTRA_ROOM_NAME = "room_name"
        const val EXTRA_OUTPUT_DIR = "output_dir"
        const val EXTRA_DEPTH_SUPPORTED = "depth_supported"
        private const val CAMERA_PERMISSION_CODE = 4102
    }
}
