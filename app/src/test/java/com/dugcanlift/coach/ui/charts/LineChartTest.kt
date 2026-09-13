package com.dugcanlift.coach.ui.charts

import org.junit.Assert.assertEquals
import org.junit.Test

class LineChartTest {
    private fun assertRange(loBound: Double, hiBound: Double, range: ClosedFloatingPointRange<Double>, delta: Double = 1e-9) {
        assertEquals(loBound, range.start, delta)
        assertEquals(hiBound, range.endInclusive, delta)
    }

    @Test fun `an empty series yields a degenerate zero range rather than crashing`() =
        assertRange(0.0, 0.0, yAxisBounds(emptyList()))

    @Test fun `an all-zero series yields a degenerate zero range`() =
        assertRange(0.0, 0.0, yAxisBounds(listOf(0.0, 0.0)))

    @Test fun `genuinely varying data keeps the original zero-anchored range unchanged`() =
        // span (200-100=100) is well over 10% of the top (200), so this is the untouched old formula.
        assertRange(0.0, 200.0, yAxisBounds(listOf(100.0, 150.0, 200.0)))

    @Test fun `data whose span sits right at the minimum threshold is left zero-anchored`() =
        // top=200, minSpan=20; a natural span of exactly 20 (180..200) must not be re-centered.
        assertRange(0.0, 200.0, yAxisBounds(listOf(180.0, 200.0)))

    @Test fun `a nearly-flat series is widened to a minimum span centered on its own midpoint`() {
        // top=101, dataMin=100, naturalSpan=1 is far below minSpan=10.1 -> centered on 100.5, +-5.05.
        assertRange(95.45, 105.55, yAxisBounds(listOf(100.0, 101.0)))
    }

    @Test fun `a perfectly flat series -- every point identical -- centers on that value`() =
        // top=dataMin=500, naturalSpan=0 -> centered on 500, +-25 (10% of 500 halved).
        assertRange(475.0, 525.0, yAxisBounds(listOf(500.0, 500.0, 500.0)))

    @Test fun `a goal above all values raises the top but never the bottom`() {
        // top = max(510, goal 600) = 600; dataMin stays 500 (goal never lowers/raises the floor).
        // naturalSpan = 100, minSpan = 60 -> still wide enough, zero-anchored.
        assertRange(0.0, 600.0, yAxisBounds(listOf(500.0, 510.0), goal = 600.0))
    }

    @Test fun `a goal that makes an otherwise-tight series read as flat still gets a centered minimum span`() {
        // top = max(502, goal 505) = 505; dataMin = 500; naturalSpan = 5, minSpan = 50.5 -> centered.
        assertRange(477.25, 527.75, yAxisBounds(listOf(500.0, 502.0), goal = 505.0))
    }
}
