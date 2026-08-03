package com.propertytour360.capture.ar

import android.content.Context
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class ArCaptureRenderer(
    private val context: Context,
    private val sessionProvider: () -> Session?,
    private val displayRotationProvider: () -> Int,
    private val recorder: ArEvidenceRecorder,
    private val onStatus: (String) -> Unit,
    private val onFatalError: (String) -> Unit
) : GLSurfaceView.Renderer {
    private val background = CameraBackgroundRenderer()
    private var width = 1
    private var height = 1
    private var textureBoundToSession = false

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        background.create(context)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        this.width = width
        this.height = height
        GLES30.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        val session = sessionProvider() ?: return
        try {
            if (!textureBoundToSession) {
                session.setCameraTextureName(background.textureId)
                textureBoundToSession = true
            }
            val displayRotation = displayRotationProvider()
            session.setDisplayGeometry(displayRotation, width, height)
            val frame = session.update()
            background.draw(frame)
            val camera = frame.camera
            val depthSupported = session.isDepthModeSupported(Config.DepthMode.AUTOMATIC) || session.isDepthModeSupported(Config.DepthMode.RAW_DEPTH_ONLY)
            recorder.record(frame, camera, depthSupported, displayRotation, width, height)
            val progress = recorder.progress()
            val guidance = when {
                progress.keyframes < 8 -> "Walk slowly around the perimeter"
                progress.verticalPlanes < 4 -> "Point at each wall and wall corner"
                progress.floorPlaneObservations == 0 -> "Tilt down to capture the floor boundary"
                progress.ceilingPlaneObservations == 0 -> "Tilt up to capture the ceiling boundary"
                progress.cornerMarkups < 3 -> "Mark the visible room corners"
                !progress.hasCenterHit -> "Aim the centre reticle at a detected surface"
                else -> "Coverage looks usable; capture every opening before finishing"
            }
            val status = when (camera.trackingState) {
                TrackingState.TRACKING -> if (depthSupported) {
                    "RGB-D ${progress.keyframes} • walls ${progress.verticalPlanes} • corners ${progress.cornerMarkups} • openings ${progress.openingMarkups}\n$guidance"
                } else {
                    "RGB/AR ${progress.keyframes} • walls ${progress.verticalPlanes}\n$guidance"
                }
                TrackingState.PAUSED -> trackingMessage(camera.trackingFailureReason)
                TrackingState.STOPPED -> "Tracking stopped"
                else -> "Initializing"
            }
            onStatus(status)
        } catch (error: CameraNotAvailableException) {
            onFatalError("Camera unavailable: ${error.message}")
        } catch (error: Throwable) {
            onStatus("Scan warning: ${error.message}")
        }
    }

    private fun trackingMessage(reason: TrackingFailureReason): String = when (reason) {
        TrackingFailureReason.NONE -> "Move the phone slowly"
        TrackingFailureReason.BAD_STATE -> "ARCore state error"
        TrackingFailureReason.INSUFFICIENT_LIGHT -> "Too dark — turn on lights"
        TrackingFailureReason.EXCESSIVE_MOTION -> "Move slower"
        TrackingFailureReason.INSUFFICIENT_FEATURES -> "Point at textured walls/corners"
        TrackingFailureReason.CAMERA_UNAVAILABLE -> "Camera unavailable"
        else -> "Tracking paused"
    }
}
