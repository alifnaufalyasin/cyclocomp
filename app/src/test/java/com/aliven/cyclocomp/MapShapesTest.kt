package com.aliven.cyclocomp

import com.aliven.cyclocomp.core.LatLon
import com.aliven.cyclocomp.core.RouteShape
import com.aliven.cyclocomp.core.TileTemplate
import com.aliven.cyclocomp.core.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapShapesTest {
    private fun pt(i: Int) = TrackPoint(timeMs = i * 1000L, lat = i * 0.001, lon = 108.0)

    @Test
    fun routeKeepsEveryPointWhenShortAndFlattensSegments() {
        val pts = RouteShape.points(listOf(listOf(pt(0), pt(1)), listOf(pt(2))))
        assertEquals(listOf(LatLon(0.0, 108.0), LatLon(0.001, 108.0), LatLon(0.002, 108.0)), pts)
    }

    @Test
    fun longRoutesAreThinnedButKeepFirstAndLast() {
        val seg = List(5000) { pt(it) }
        val pts = RouteShape.points(listOf(seg), maxPoints = 800)
        assertEquals(800, pts.size)
        assertEquals(LatLon(0.0, 108.0), pts.first())
        assertEquals(LatLon(4999 * 0.001, 108.0), pts.last())
    }

    @Test
    fun downsampleLeavesSmallListsAndTinyLimitsAlone() {
        val items = listOf(1, 2, 3)
        assertEquals(items, RouteShape.downsample(items, 10))
        assertEquals(items, RouteShape.downsample(items, 1))
        assertTrue(RouteShape.points(emptyList()).isEmpty())
    }

    @Test
    fun tileTemplateNeedsHttpsAndAllThreePlaceholders() {
        assertTrue(TileTemplate.isValid("https://tiles.example.org/{z}/{x}/{y}.png"))
        assertTrue(TileTemplate.isValid("  https://t.example.org/osm/{z}/{x}/{y}.png  "))
        assertFalse(TileTemplate.isValid("http://tiles.example.org/{z}/{x}/{y}.png")) // cleartext is blocked on Android
        assertFalse(TileTemplate.isValid("https://tiles.example.org/{z}/{x}.png"))
        assertFalse(TileTemplate.isValid("https://"))
        assertFalse(TileTemplate.isValid("https://t.example.org/a b/{z}/{x}/{y}.png"))
        assertFalse(TileTemplate.isValid(""))
    }

    @Test
    fun tileTemplateExpandsPlaceholders() {
        assertEquals(
            "https://tiles.example.org/15/26000/17000.png",
            TileTemplate.expand(" https://tiles.example.org/{z}/{x}/{y}.png ", 15, 26000, 17000),
        )
    }
}
