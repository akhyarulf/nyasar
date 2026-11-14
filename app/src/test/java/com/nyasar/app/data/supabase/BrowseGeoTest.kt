package com.nyasar.app.data.supabase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowseGeoTest {

    @Test
    fun `around centres the box on the given point`() {
        val box = BrowseBbox.around(-7.0, 107.0, 25.0)
        assertEquals(-7.0, (box.minLat + box.maxLat) / 2, 0.0001)
        assertEquals(107.0, (box.minLng + box.maxLng) / 2, 0.0001)
        assertTrue(box.isUsable)
    }

    @Test
    fun `25 km is about a quarter degree of latitude`() {
        val box = BrowseBbox.around(-7.0, 107.0, 25.0)
        assertEquals(50.0 / BrowseBbox.KM_PER_DEGREE_LAT, box.latSpanDegrees, 0.001)
    }

    @Test
    fun `longitude span widens away from the equator`() {
        val equator = BrowseBbox.around(0.0, 0.0, 25.0)
        val java = BrowseBbox.around(-8.0, 110.0, 25.0)
        assertTrue(java.lngSpanDegrees > equator.lngSpanDegrees)
    }

    @Test
    fun `radius is capped so a huge request stays a usable box`() {
        val box = BrowseBbox.around(0.0, 0.0, 5_000.0)
        assertTrue(box.latSpanDegrees <= BrowseBbox.MAX_SPAN_DEGREES + 0.0001)
        assertTrue(box.isUsable)
    }

    @Test
    fun `normalized swaps inverted corners`() {
        val box = BrowseBbox(minLat = 5.0, minLng = 100.0, maxLat = 1.0, maxLng = 99.0).normalized()
        assertEquals(1.0, box.minLat, 0.0)
        assertEquals(5.0, box.maxLat, 0.0)
        assertEquals(99.0, box.minLng, 0.0)
        assertEquals(100.0, box.maxLng, 0.0)
    }

    @Test
    fun `oversized box is rejected`() {
        val box = BrowseBbox(minLat = -10.0, minLng = 90.0, maxLat = 10.0, maxLng = 130.0)
        assertFalse(box.isUsable)
    }

    @Test
    fun `empty box is rejected`() {
        assertFalse(BrowseBbox(1.0, 1.0, 1.0, 1.0).isUsable)
    }

    @Test
    fun `page range is inclusive on both ends`() {
        assertEquals(0L..49L, BrowsePaging.page(offset = 0, limit = 50))
        assertEquals(50L..99L, BrowsePaging.page(offset = 50, limit = 50))
    }

    @Test
    fun `page clamps limit and offset`() {
        assertEquals(0L..0L, BrowsePaging.page(offset = -5, limit = 0))
        assertEquals(0L..99L, BrowsePaging.page(offset = 0, limit = 5_000))
    }
}
