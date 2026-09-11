package com.propertytour360.capture.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.propertytour360.capture.PropertyTourApplication
import com.propertytour360.capture.model.PanoramaCapturePattern
import com.propertytour360.capture.model.RoomDraft
import kotlinx.coroutines.flow.first
import java.io.File
import java.io.IOException

class ReliableCaptureUploadWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val taskId = inputData.getString(CaptureUploadQueue.KEY_TASK_ID) ?: return Result.failure()
        val task = CaptureUploadQueue.read(applicationContext, taskId) ?: return Result.failure()
        val app = applicationContext as? PropertyTourApplication ?: return Result.failure()
        val baseUrl = app.container.preferences.backendUrl.first()
        val token = app.container.preferences.token.first() ?: return Result.failure()
        val repository = app.container.backendRepository
        return try {
            when (task.type) {
                CaptureUploadQueue.TASK_MODE_A_PANORAMA -> {
                    val file = task.panoramaPath?.let(::File) ?: return Result.failure()
                    val asset = repository.uploadPanorama(baseUrl, token, task.captureId, task.roomId, file, "image/jpeg")
                    repository.waitForAsset(baseUrl, token, task.captureId, asset.id)
                }
                CaptureUploadQueue.TASK_MODE_A_STITCH -> {
                    val pattern = task.capturePattern?.let { raw -> PanoramaCapturePattern.entries.firstOrNull { it.apiValue == raw } }
                        ?: return Result.failure()
                    val room = RoomDraft(
                        serverId = task.roomId,
                        name = task.roomName,
                        sortOrder = 0,
                        localPhotos = task.photoPaths.map(::File),
                        panoramaCapturePattern = pattern,
                        panoramaManifestFile = task.manifestPath?.let(::File),
                        panoramaFrames = task.frames.map { it.toModel() },
                        panoramaHorizontalFovDegrees = task.horizontalFovDegrees,
                        panoramaVerticalFovDegrees = task.verticalFovDegrees,
                        panoramaMinPitchDegrees = task.minPitchDegrees,
                        panoramaMaxPitchDegrees = task.maxPitchDegrees
                    )
                    val jobId = repository.uploadRoomPhotosAndStitch(baseUrl, token, task.captureId, room) { _, _ -> }
                    val job = repository.waitForJob(baseUrl, token, jobId)
                    if (job.status == "FAILED") throw IllegalStateException(job.error ?: "Panorama stitching failed")
                }
                CaptureUploadQueue.TASK_MODE_B_PACKAGE -> {
                    val manifest = task.manifestPath?.let(::File) ?: return Result.failure()
                    val archive = task.archivePath?.let(::File) ?: return Result.failure()
                    val manifestAsset = repository.uploadFile(baseUrl, token, task.captureId, task.roomId, "CAPTURE_MANIFEST", manifest, "application/json")
                    val archiveAsset = repository.uploadFile(baseUrl, token, task.captureId, task.roomId, "MODEL_EVIDENCE", archive, "application/zip")
                    repository.finalizeCapturePackages(
                        baseUrl, token, task.captureId,
                        listOf(FinalizePackageRoom(task.roomId, manifestAsset.id, archiveAsset.id))
                    )
                }
                else -> return Result.failure()
            }
            CaptureUploadQueue.delete(applicationContext, taskId)
            Result.success()
        } catch (error: IOException) {
            if (runAttemptCount >= 5) Result.failure() else Result.retry()
        } catch (_: Throwable) {
            Result.failure()
        }
    }
}
