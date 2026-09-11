package com.propertytour360.capture.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraFovEstimatorTest {

    @Test
    fun testCalculateEffectiveFovAt1x() {
        val baseFov = CameraFovEstimator.PortraitFov(52f, 68f)
        val effective = CameraFovEstimator.calculateEffectiveFov(baseFov, 1.0f)
        assertEquals(52f, effective.horizontalDegrees, 0.01f)
        assertEquals(68f, effective.verticalDegrees, 0.01f)
    }

    @Test
    fun testCalculateEffectiveFovAt0_6x() {
        val baseFov = CameraFovEstimator.PortraitFov(52f, 68f)
        val effective = CameraFovEstimator.calculateEffectiveFov(baseFov, 0.6f)
        assertTrue(effective.horizontalDegrees > 75f && effective.horizontalDegrees < 82f)
        assertTrue(effective.verticalDegrees > 94f && effective.verticalDegrees < 100f)
    }

    @Test
    fun testCalculateEffectiveFovAt0_5x() {
        val baseFov = CameraFovEstimator.PortraitFov(52f, 68f)
        val effective = CameraFovEstimator.calculateEffectiveFov(baseFov, 0.5f)
        assertTrue(effective.horizontalDegrees > 85f && effective.horizontalDegrees < 92f)
        assertTrue(effective.verticalDegrees > 104f && effective.verticalDegrees < 110f)
    }

    @Test
    fun testRecommendedRingFrameCount() {
        // 1x (52° HFOV) -> 12 frames
        assertEquals(12, CameraFovEstimator.recommendedRingFrameCount(52f))
        // 0.6x (~78° HFOV) -> 8 frames
        assertEquals(8, CameraFovEstimator.recommendedRingFrameCount(78.2f))
        // 0.5x (~88.6° HFOV) -> 7 frames
        assertEquals(7, CameraFovEstimator.recommendedRingFrameCount(88.6f))
    }
}
