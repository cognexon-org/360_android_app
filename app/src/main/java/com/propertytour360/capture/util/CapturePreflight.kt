package com.propertytour360.capture.util

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.StatFs

/** Lightweight, local checks that protect a field operator before capture starts. */
data class CapturePreflightReport(
    val score: Int,
    val status: String,
    val blockers: List<String>,
    val warnings: List<String>,
    val batteryPercent: Int,
    val charging: Boolean,
    val freeStorageMb: Long,
    val networkConnected: Boolean,
    val networkMetered: Boolean,
    val networkTransport: String,
    val memoryClassMb: Int,
    val manufacturer: String,
    val model: String,
    val sdkInt: Int
) {
    val canStart: Boolean get() = blockers.isEmpty()

    fun reportMap(): Map<String, Any> = mapOf(
        "score" to score,
        "status" to status,
        "blockers" to blockers,
        "warnings" to warnings,
        "batteryPercent" to batteryPercent,
        "charging" to charging,
        "freeStorageMb" to freeStorageMb,
        "networkConnected" to networkConnected,
        "networkMetered" to networkMetered,
        "networkTransport" to networkTransport,
        "memoryClassMb" to memoryClassMb
    )

    fun deviceTelemetry(): Map<String, Any> = mapOf(
        "manufacturer" to manufacturer,
        "model" to model,
        "sdk" to sdkInt,
        "memoryClassMb" to memoryClassMb,
        "freeStorageMbAtCaptureStart" to freeStorageMb,
        "batteryPercentAtCaptureStart" to batteryPercent,
        "networkTransportAtCaptureStart" to networkTransport,
        "networkMeteredAtCaptureStart" to networkMetered,
        "offlineUploadQueue" to true,
        "resumableUploadProtocol" to "CHUNK_V1"
    )
}

object CapturePreflight {
    fun evaluate(context: Context): CapturePreflightReport {
        val battery = context.getSystemService(BatteryManager::class.java)
        val batteryPercent = battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.coerceIn(0, 100) ?: -1
        val charging = battery?.isCharging ?: false
        val stat = StatFs(context.filesDir.absolutePath)
        val freeStorageMb = stat.availableBytes / (1024L * 1024L)
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val network = connectivity?.activeNetwork
        val caps = network?.let { connectivity.getNetworkCapabilities(it) }
        val connected = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        val transport = when {
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "WIFI"
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "CELLULAR"
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "ETHERNET"
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true -> "VPN"
            connected -> "OTHER"
            else -> "OFFLINE"
        }
        val memoryClassMb = (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.memoryClass ?: 0
        val blockers = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        if (freeStorageMb < 512) blockers += "Less than 512 MB free storage"
        else if (freeStorageMb < 1536) warnings += "Low free storage; long Mode B captures may need more space"
        if (batteryPercent in 0..14 && !charging) blockers += "Battery below 15%"
        else if (batteryPercent in 15..29 && !charging) warnings += "Battery below 30%"
        if (!connected) warnings += "Offline: capture can continue; upload will queue until network returns"
        if (memoryClassMb in 1..255) warnings += "Low-memory device; use shorter captures and lower preview quality"
        if (Build.VERSION.SDK_INT < 26) warnings += "Older Android version; background execution may be less reliable"
        var score = 100 - blockers.size * 45 - warnings.size * 10
        score = score.coerceIn(0, 100)
        val status = when {
            blockers.isNotEmpty() -> "BLOCKED"
            warnings.isNotEmpty() -> "READY_WITH_WARNINGS"
            else -> "READY"
        }
        return CapturePreflightReport(
            score, status, blockers, warnings, batteryPercent, charging, freeStorageMb,
            connected, connectivity?.isActiveNetworkMetered ?: false, transport, memoryClassMb,
            Build.MANUFACTURER, Build.MODEL, Build.VERSION.SDK_INT
        )
    }
}
