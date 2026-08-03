package com.propertytour360.capture

import com.propertytour360.capture.util.DesignModelBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DesignModelBuilderTest {
    @Test fun rectangularRoomHasFourWallsAndUnplacedOpeningProposals() {
        val model = DesignModelBuilder.buildRoomModel(
            id = "living",
            name = "Living room",
            length = 4.0,
            width = 3.0,
            height = 2.8,
            doorWidth = 0.9,
            doorHeight = 2.1,
            windowWidth = 1.2,
            windowHeight = 1.2
        )
        val walls = model["walls"] as List<*>
        assertEquals(4, walls.size)
        assertTrue((model["floorPolygon"] as List<*>).size == 4)
        assertEquals(2, (model["unplacedOpenings"] as List<*>).size)
    }
}
