package com.propertytour360.capture

import com.propertytour360.capture.model.RoomDraft
import com.propertytour360.capture.util.DesignModelBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DesignModelBuilderTest {
    @Test fun rectangularRoomHasFourWallsAndUnplacedOpeningProposals() {
        val room = RoomDraft(
            serverId = "living",
            name = "Living room",
            sortOrder = 0,
            lengthM = 4.0,
            widthM = 3.0,
            heightM = 2.8,
            doorWidthM = 0.9,
            doorHeightM = 2.1,
            windowWidthM = 1.2,
            windowHeightM = 1.2
        )
        val model = DesignModelBuilder.buildRoomModel(room)
        val walls = model["walls"] as List<*>
        assertEquals(4, walls.size)
        assertEquals(4, (model["floorPolygon"] as List<*>).size)
    }
}
