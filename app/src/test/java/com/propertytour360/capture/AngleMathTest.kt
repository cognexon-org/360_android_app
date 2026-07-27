package com.propertytour360.capture

import com.propertytour360.capture.util.AngleMath
import org.junit.Assert.assertEquals
import org.junit.Test

class AngleMathTest {
    @Test fun wrapsAngles() {
        assertEquals(350f, AngleMath.normalizeDegrees(-10f), 0.001f)
        assertEquals(10f, AngleMath.normalizeDegrees(370f), 0.001f)
    }

    @Test fun choosesShortestDistance() {
        assertEquals(20f, AngleMath.angularDistance(350f, 10f), 0.001f)
    }
}
