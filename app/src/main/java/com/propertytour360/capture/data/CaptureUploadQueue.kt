package com.propertytour360.capture.data

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.google.gson.Gson
import com.propertytour360.capture.model.RoomDraft
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

object CaptureUploadQueue {
    const val TASK_MODE_A_PANORAMA = "MODE_A_PANORAMA"
    const val TASK_MODE_A_STITCH = "MODE_A_STITCH"
    const val TASK_MODE_B_PACKAGE = "MODE_B_PACKAGE"
    const val KEY_TASK_ID = "taskId"
    private val gson = Gson()

    fun enqueueModeA(context: Context, captureId: String, room: RoomDraft): String {
        val taskId = UUID.randomUUID().toString()
        val dir = taskDirectory(context, taskId).apply { mkdirs() }
        val task = if (room.panoramaFile != null) {
            val panorama = stage(room.panoramaFile, File(dir, "panorama-${room.panoramaFile.name}"))
            QueuedCaptureUploadTask(taskId, TASK_MODE_A_PANORAMA, captureId, room.serverId, room.name, panoramaPath = panorama.absolutePath)
        } else {
            require(room.localPhotos.size >= 3) { "No guided panorama capture to queue" }
            val photos = room.localPhotos.mapIndexed { index, file ->
                stage(file, File(dir, "photo-${index.toString().padStart(3, '0')}-${file.name}"))
            }
            // Worker uses frame file names to match staged photos, so rewrite metadata fileName.
            val stagedFrames = room.panoramaFrames.mapIndexed { index, frame ->
                QueuedPanoramaFrame.from(frame).copy(fileName = photos[index].name)
            }
            val manifest = room.panoramaManifestFile?.takeIf { it.exists() }?.let { stage(it, File(dir, "manifest.json")) }
            QueuedCaptureUploadTask(
                id = taskId,
                type = TASK_MODE_A_STITCH,
                captureId = captureId,
                roomId = room.serverId,
                roomName = room.name,
                photoPaths = photos.map { it.absolutePath },
                manifestPath = manifest?.absolutePath,
                capturePattern = room.panoramaCapturePattern?.apiValue,
                frames = stagedFrames,
                horizontalFovDegrees = room.panoramaHorizontalFovDegrees,
                verticalFovDegrees = room.panoramaVerticalFovDegrees,
                minPitchDegrees = room.panoramaMinPitchDegrees,
                maxPitchDegrees = room.panoramaMaxPitchDegrees
            )
        }
        writeTask(dir, task)
        schedule(context, taskId)
        return taskId
    }

    fun enqueueModeBPackage(
        context: Context,
        captureId: String,
        roomId: String,
        roomName: String,
        manifestFile: File,
        archiveFile: File
    ): String {
        val taskId = UUID.randomUUID().toString()
        val dir = taskDirectory(context, taskId).apply { mkdirs() }
        val manifest = stage(manifestFile, File(dir, "manifest.json"))
        val archive = stage(archiveFile, File(dir, "capture_package_v2.zip"))
        val task = QueuedCaptureUploadTask(
            taskId, TASK_MODE_B_PACKAGE, captureId, roomId, roomName,
            manifestPath = manifest.absolutePath,
            archivePath = archive.absolutePath
        )
        writeTask(dir, task)
        schedule(context, taskId)
        return taskId
    }

    fun read(context: Context, taskId: String): QueuedCaptureUploadTask? {
        val file = File(taskDirectory(context, taskId), "task.json")
        return if (file.exists()) gson.fromJson(file.readText(), QueuedCaptureUploadTask::class.java) else null
    }

    fun delete(context: Context, taskId: String) {
        taskDirectory(context, taskId).deleteRecursively()
    }

    private fun taskDirectory(context: Context, taskId: String) = File(context.filesDir, "progression_upload_queue/$taskId")

    private fun stage(source: File, target: File): File {
        target.parentFile?.mkdirs()
        source.inputStream().buffered().use { input -> target.outputStream().buffered().use { output -> input.copyTo(output) } }
        return target
    }

    private fun writeTask(dir: File, task: QueuedCaptureUploadTask) {
        File(dir, "task.json").writeText(gson.toJson(task))
    }

    private fun schedule(context: Context, taskId: String) {
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val request = OneTimeWorkRequestBuilder<ReliableCaptureUploadWorker>()
            .setInputData(Data.Builder().putString(KEY_TASK_ID, taskId).build())
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
            .addTag("progression-upload")
            .addTag("progression-upload-$taskId")
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("progression-upload-$taskId", ExistingWorkPolicy.KEEP, request)
    }
}
