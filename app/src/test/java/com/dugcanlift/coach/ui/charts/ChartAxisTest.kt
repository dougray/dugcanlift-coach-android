package com.dugcanlift.coach.ui.charts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.Locale
import java.util.TimeZone

/**
 * The client charts' axes: readable dates in the reader's locale, a few labels
 * where they fit, and a bodyweight axis fitted to the weights. Display only --
 * nothing here takes or returns a stored value.
 *
 * en-US is pinned only so a string can be asserted. The patterns are what
 * Android's `getBestDateTimePattern` returns for en-US, which
 * `ChartAxisSkeletonTest` checks against Android itself.
 */
class ChartAxisTest {

    private val enUS = Locale.US

    /** Consecutive local day keys from [start], made with calendar arithmetic. */
    private fun days(start: String, count: Int): List<String> =
        List(count) { LocalDate.parse(start).plusDays(it.toLong()).toString() }

    /* ---------------- labels ---------------- */

    @Test fun `a day key reads as a short date in the reader's locale`() {
        assertEquals("Sep 27", ChartAxis.dayLabel("2026-09-27", enUS, EN_US_DAY))
        assertEquals("Oct 11", ChartAxis.dayLabel("2026-10-11", enUS, EN_US_DAY))
        assertEquals("Sep 27, 2025", ChartAxis.dayLabel("2025-09-27", enUS, EN_US_DAY_YEAR))
        // Nothing pins en-US on a device.
        assertNotEquals(
            ChartAxis.dayLabel("2026-09-27", enUS, EN_US_DAY),
            ChartAxis.dayLabel("2026-09-27", Locale.GERMANY, "d. MMM")
        )
    }

    @Test fun `a key that does not parse is printed as it is, never thrown inside composition`() {
        assertEquals("2026-9-3", ChartAxis.dayLabel("2026-9-3", enUS, EN_US_DAY))
        assertEquals("", ChartAxis.dayLabel("", enUS, EN_US_DAY))
    }

    @Test fun `the year is shown only when the chart runs across more than one`() {
        assertEquals(ChartAxis.DAY_SKELETON, ChartAxis.skeleton(days("2026-09-01", 60)))
        assertEquals(ChartAxis.DAY_YEAR_SKELETON, ChartAxis.skeleton(days("2025-12-20", 20)))
        assertEquals(ChartAxis.DAY_SKELETON, ChartAxis.skeleton(emptyList()))
    }

    /**
     * A day key is a local calendar date. Parsed as one, a key stays on its own day in any zone,
     * and a run of keys across the autumn clock change is four days, not three with one repeated
     * -- what `now - days * 86400` would print.
     */
    @Test fun `day keys are local dates and survive a clock change in any zone`() {
        val saved = TimeZone.getDefault()
        try {
            for (zone in listOf("America/New_York", "Pacific/Honolulu", "Pacific/Kiritimati", "UTC")) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone))
                val keys = days("2026-10-31", 4)
                assertEquals(listOf("2026-10-31", "2026-11-01", "2026-11-02", "2026-11-03"), keys)
                assertEquals(
                    zone,
                    listOf("Oct 31", "Nov 1", "Nov 2", "Nov 3"),
                    keys.map { ChartAxis.dayLabel(it, enUS, EN_US_DAY) }
                )
                assertEquals(zone, "Jan 1, 2027", ChartAxis.dayLabel("2027-01-01", enUS, EN_US_DAY_YEAR))
            }
        } finally {
            TimeZone.setDefault(saved)
        }
    }

    /* ---------------- where points sit ---------------- */

    @Test fun `points sit where the charts draw them`() {
        assertEquals(listOf(0f, 100f, 200f, 300f, 400f), ChartAxis.linePositions(5, 400f))
        assertEquals(listOf(200f), ChartAxis.linePositions(1, 400f))
        assertEquals(emptyList<Float>(), ChartAxis.linePositions(0, 400f))
        assertEquals(listOf(50f, 150f, 250f, 350f), ChartAxis.barPositions(4, 400f))
    }

    @Test fun `even indices always take the first and the last`() {
        assertEquals(listOf(0, 2, 4, 6, 8), ChartAxis.evenIndices(9, 5))
        assertEquals(listOf(0, 3, 6, 9), ChartAxis.evenIndices(10, 4))
        assertEquals(listOf(0, 1, 2, 3, 4), ChartAxis.evenIndices(5, 10))
        assertEquals(listOf(0), ChartAxis.evenIndices(1, 3))
        assertEquals(listOf(6), ChartAxis.evenIndices(7, 1))
        assertEquals(emptyList<Int>(), ChartAxis.evenIndices(0, 3))
    }

    /* ---------------- which points get a label ---------------- */

    @Test fun `a phone-width month gets a few labels, first and last among them`() {
        // 29 days across 336 px with 48 px labels: four would crowd, three fit with room.
        val ticks = ChartAxis.ticks(ChartAxis.linePositions(29, 336f), 336f, labelWidth = 48f, gap = 48f)
        assertEquals(listOf(0, 14, 28), ticks.map { it.index })
        // Centred under their points, and the two ends kept inside the chart.
        assertEquals(listOf(0f, 144f, 288f), ticks.map { it.left })
    }

    @Test fun `a wide scrolling chart gets more labels, evenly spaced, none touching`() {
        val width = 104 * 28f
        val ticks = ChartAxis.ticks(ChartAxis.barPositions(104, width), width, labelWidth = 48f, gap = 48f)
        assertTrue("${ticks.size} labels", ticks.size > 10)
        assertEquals(0, ticks.first().index)
        assertEquals(103, ticks.last().index)
        val steps = ticks.zipWithNext { a, b -> b.index - a.index }
        assertTrue("uneven: $steps", steps.max() - steps.min() <= 1)
        ticks.zipWithNext { a, b -> assertTrue("$a and $b touch", b.left - a.left >= 96f) }
        ticks.forEach { assertTrue("$it outside", it.left >= 0f && it.left + 48f <= width) }
    }

    @Test fun `labels never overlap, at any width`() {
        for (count in listOf(2, 3, 5, 8, 13, 29, 60, 365)) {
            for (width in listOf(120f, 200f, 336f, 600f, 1200f)) {
                for (positions in listOf(ChartAxis.linePositions(count, width), ChartAxis.barPositions(count, width))) {
                    val ticks = ChartAxis.ticks(positions, width, labelWidth = 52f, gap = 52f)
                    assertTrue("$count in $width", ticks.isNotEmpty())
                    ticks.zipWithNext { a, b ->
                        assertTrue("$count in $width: $a and $b", b.left - a.left >= 104f)
                    }
                    ticks.forEach { assertTrue("$count in $width: $it", it.left >= 0f && it.left + 52f <= width) }
                    assertEquals(ticks.map { it.index }.sorted().distinct(), ticks.map { it.index })
                }
            }
        }
    }

    @Test fun `when two will not fit, the newest is the one shown`() {
        val ticks = ChartAxis.ticks(ChartAxis.linePositions(2, 60f), 60f, labelWidth = 48f, gap = 48f)
        assertEquals(listOf(ChartAxis.Tick(1, 12f)), ticks)
    }

    @Test fun `one point gets its one label, centred, and none gets none`() {
        assertEquals(
            listOf(ChartAxis.Tick(0, 126f)),
            ChartAxis.ticks(ChartAxis.linePositions(1, 300f), 300f, labelWidth = 48f, gap = 48f)
        )
        assertEquals(emptyList<ChartAxis.Tick>(), ChartAxis.ticks(emptyList(), 300f, 48f, 48f))
    }

    /* ---------------- the bodyweight axis ---------------- */

    @Test fun `bodyweight is fitted to the weights, not drawn from zero`() {
        val b = ChartAxis.fittedBounds(listOf(176.0, 178.0, 183.0))!!
        assertEquals(170.0, b.bottom, 0.0)
        assertEquals(190.0, b.top, 0.0)
        assertEquals(listOf(170.0, 180.0, 190.0), b.guides)
    }

    @Test fun `kilograms fit the same way, on round guides`() {
        val b = ChartAxis.fittedBounds(listOf(80.3, 81.0, 82.1))!!
        assertEquals(listOf(77.5, 80.0, 82.5), b.guides)
        assertEquals(listOf("77.5", "80", "82.5"), b.guides.map(ChartAxis::guideLabel))
    }

    @Test fun `a flat bodyweight sits in the middle of its axis`() {
        val b = ChartAxis.fittedBounds(listOf(180.0, 180.0, 180.0))!!
        assertEquals(listOf(178.0, 180.0, 182.0), b.guides)
    }

    @Test fun `every weight is inside the axis, which never goes below zero`() {
        val series = listOf(
            listOf(120.0, 200.0), listOf(95.0), listOf(0.2), listOf(250.0, 251.5, 249.0),
            listOf(59.9, 60.1), listOf(1000.0, 1004.0), listOf(140.0, 180.0, 160.0)
        )
        series.forEach { values ->
            val b = ChartAxis.fittedBounds(values)!!
            assertTrue("$values in $b", b.bottom >= 0.0)
            assertTrue("$values in $b", values.all { it >= b.bottom && it <= b.top })
            assertTrue("$values in $b", b.top > b.bottom)
        }
        assertEquals(listOf(100.0, 150.0, 200.0), ChartAxis.fittedBounds(listOf(120.0, 200.0))!!.guides)
    }

    @Test fun `no weights is no axis`() {
        assertNull(ChartAxis.fittedBounds(emptyList()))
        assertNull(ChartAxis.fittedBounds(listOf(Double.NaN)))
    }

    @Test fun `a guide prints as a whole number when it is one`() {
        assertEquals("180", ChartAxis.guideLabel(180.0))
        assertEquals("177.5", ChartAxis.guideLabel(177.5))
        assertEquals("0", ChartAxis.guideLabel(0.0))
    }

    companion object {
        /** Android's en-US patterns for [ChartAxis.DAY_SKELETON] and [ChartAxis.DAY_YEAR_SKELETON]. */
        const val EN_US_DAY = "MMM d"
        const val EN_US_DAY_YEAR = "MMM d, y"
    }
}
