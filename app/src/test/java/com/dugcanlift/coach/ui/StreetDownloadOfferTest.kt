package com.dugcanlift.coach.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreetDownloadOfferTest {
    @Test fun `a two-point route drawn as the line is offered the download`() =
        assertTrue(offersStreetDownload(2, hasBounds = true, drawnAsLine = true, writing = false))

    @Test fun `a one-point route is not offered a zero-area download`() =
        assertFalse(offersStreetDownload(1, hasBounds = true, drawnAsLine = true, writing = false))

    @Test fun `no points, a covered route or a write in progress is not offered it`() {
        assertFalse(offersStreetDownload(0, hasBounds = false, drawnAsLine = true, writing = false))
        assertFalse(offersStreetDownload(5, hasBounds = true, drawnAsLine = false, writing = false))
        assertFalse(offersStreetDownload(5, hasBounds = true, drawnAsLine = true, writing = true))
        assertFalse(offersStreetDownload(5, hasBounds = false, drawnAsLine = true, writing = false))
    }
}
