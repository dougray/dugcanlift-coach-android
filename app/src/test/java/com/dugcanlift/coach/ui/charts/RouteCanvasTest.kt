package com.dugcanlift.coach.ui.charts
import org.junit.Assert.*
import org.junit.Test

/**
 * [projectRoutePoints], ported from LIFT Android's own `RoutePolylineCanvasTest` shapes: that
 * projection once shipped a sign error that only stayed in bounds for a route whose two extents
 * happened to match, so every shape is checked in a 2:1 box like the Last route card's.
 */
class RouteCanvasTest {
    private val width = 1000f
    private val height = 500f

    private fun assertInBox(points: List<Pair<Double, Double>>) {
        projectRoutePoints(points, width, height).forEach {
            assertTrue("x=${it.x} out of [0, $width]", it.x in 0f..width)
            assertTrue("y=${it.y} out of [0, $height]", it.y in 0f..height)
        }
    }

    @Test fun `east-west, north-south and square routes all stay inside a wide box`() {
        assertInBox(listOf(40.0 to -74.0, 40.0001 to -74.05, 40.0 to -74.1, 40.0001 to -74.05))
        assertInBox(listOf(40.0 to -74.0, 40.05 to -74.0001, 40.1 to -74.0, 40.2 to -74.0))
        assertInBox(listOf(40.0 to -74.0, 40.0 to -74.01, 40.01 to -74.01, 40.01 to -74.0))
        // Nearly stationary: the minimum span keeps it from dividing by nothing.
        assertInBox(listOf(40.0 to -74.0, 40.0 to -74.0))
    }

    @Test fun `north is up, and a tall route is centred rather than stretched`() {
        val o = projectRoutePoints(listOf(40.0 to -74.0, 40.1 to -74.0), width, height)
        assertTrue("north is up", o[1].y < o[0].y)
        assertEquals(width / 2, o[0].x, 0.5f); assertEquals(width / 2, o[1].x, 0.5f)
    }

    @Test fun `east is right, and a square keeps equal sides once longitude is scaled`() {
        val lonSpan = 0.01 / kotlin.math.cos(Math.toRadians(40.005))
        val o = projectRoutePoints(listOf(40.0 to -74.0, 40.0 to -74.0 + lonSpan, 40.01 to -74.0 + lonSpan), width, height)
        assertTrue("east is right", o[1].x > o[0].x)
        assertEquals(o[1].x - o[0].x, o[1].y - o[2].y, 1f)
    }

    @Test fun `nothing to draw projects to nothing`() = assertTrue(projectRoutePoints(emptyList(), width, height).isEmpty())
}
