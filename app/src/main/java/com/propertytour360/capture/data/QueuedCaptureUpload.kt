package com.propertytour360.capture.data

import com.propertytour360.capture.model.PanoramaFrameMetadata

data class QueuedPanoramaFrame(
    val fileName: String,
    val targetYawDegrees: Float,
    val targetPitchDegrees: Float,
    val measuredYawDegrees: Float,
    val measuredPitchDegrees: Float,
    val measuredRollDegrees: Float,
    val angularSpeedDegreesPerSecond: Float,
    val sensorTimestampNs: Long,
    val capturedAtEpochMs: Long
) {
    fun toModel() = PanoramaFrameMetadata(
        fileName, targetYawDegrees, targetPitchDegrees, measuredYawDegrees, measuredPitchDegrees,
        measuredRollDegrees, angularSpeedDegreesPerSecond, sensorTimestampNs, capturedAtEpochMs
    )

    companion object {
        fun from(value: PanoramaFrameMetadata) = QueuedPanoramaFrame(
            value.fileName, value.targetYawDegrees, value.targetPitchDegrees,
            value.measuredYawDegrees, value.measuredPitchDegrees, value.measuredRollDegrees,
            value.angularSpeedDegreesPerSecond, value.sensorTimestampNs, value.capturedAtEpochMs
        )
    }
}

data class QueuedCaptureUploadTask(
    val id: String,
    val type: String,
    val captureId: String,
    val roomId: String,
    val roomName: String,
    val panoramaPath: String? = null,
    val photoPaths: List<String> = emptyList(),
    val manifestPath: String? = null,
    val archivePath: String? = null,
    val capturePattern: String? = null,
    val frames: List<QueuedPanoramaFrame> = emptyList(),
    val horizontalFovDegrees: Float? = null,
    val verticalFovDegrees: Float? = null,
    val minPitchDegrees: Float? = null,
    val maxPitchDegrees: Float? = null,
    val createdAtEpochMs: Long = System.currentTimeMillis()
)
