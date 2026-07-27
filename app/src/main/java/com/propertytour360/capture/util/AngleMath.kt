package com.propertytour360.capture.util

import kotlin.math.abs

object AngleMath {
    fun normalizeDegrees(value: Float): Float {
        var result = value % 360f
        if (result < 0f) result += 360f
        return result
    }

    fun normalizeSignedDegrees(value: Float): Float {
        val normalized = normalizeDegrees(value)
        return if (normalized > 180f) normalized - 360f else normalized
    }

    fun clockwiseFrom(start: Float, current: Float): Float = normalizeDegrees(current - start)

    fun angularDistance(a: Float, b: Float): Float {
        val diff = abs(normalizeDegrees(a) - normalizeDegrees(b))
        return minOf(diff, 360f - diff)
    }

    fun interpolateDegrees(from: Float, to: Float, fraction: Float): Float {
        val delta = normalizeSignedDegrees(to - from)
        return normalizeDegrees(from + delta * fraction.coerceIn(0f, 1f))
    }
}
