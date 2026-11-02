package com.dugcanlift.coach.ui

import com.dugcanlift.coach.data.ROAD_FOOD_ASSET
import com.dugcanlift.coach.data.parseRoadFood
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * A Road Food chain's two dates on its place card. A port of Coach iPhone's
 * `RoadPicksTests` date cases, string for string, so the three Coach builds
 * print one line from one file.
 *
 * en-US is pinned only so a string can be asserted: on a device the locale is
 * the reader's. [EN_US_MONTH_YEAR] is what `getBestDateTimePattern(en_US,
 * "yMMM")` returns, which `RoadFoodDatesSkeletonTest` checks against Android.
 */
class RoadFoodDatesTest {

    private val enUS = Locale.US

    private fun line(published: String?, checked: String?) =
        RoadFoodDates.line(published, checked, enUS, EN_US_MONTH_YEAR)

    private fun doc(key: String?) = RoadFoodDates.documentText(key, enUS, EN_US_MONTH_YEAR)

    @Test fun `a place says what the chain published and when it was checked`() {
        assertEquals("Published Mar 29, 2021 · checked Sep 23, 2026", line("2021-03-29", "2026-09-23"))
        assertEquals("Published Nov 2022 · checked Sep 23, 2026", line("2022-11", "2026-09-23"))
        // Blank stays blank: a chain whose document states no date reads
        // exactly as it would without the field -- no empty clause.
        assertEquals("Checked Sep 20, 2026", line(null, "2026-09-20"))
        assertEquals("Checked Sep 20, 2026", line("  ", "2026-09-20"))
        assertEquals("Published Nov 2022", line("2022-11", null))
        assertNull(line(null, null))
    }

    @Test fun `a date is written the way a person writes one`() {
        assertEquals("Sep 20, 2026", RoadFoodDates.dayText("2026-09-20", enUS))
        assertEquals("Mar 29, 2021", doc("2021-03-29"))
    }

    /** No day is invented for the reader: Burger King's chart says "NOVEMBER 2022" and never the 1st. */
    @Test fun `a month-only document date stays a month`() {
        assertEquals("Nov 2022", doc("2022-11"))
        assertEquals("Jan 2022", doc("2022-01"))
        // The day someone read a chart is a whole day, so a month is not a check date.
        assertNull(RoadFoodDates.dayText("2022-11", enUS))
    }

    @Test fun `a missing or unreadable date says nothing at all`() {
        assertNull(RoadFoodDates.dayText(null, enUS))
        assertNull(doc(null))
        assertNull(RoadFoodDates.dayText("", enUS))
        assertNull(doc("   "))
        for (junk in listOf("2026", "2026-13-01", "2026-09-32", "2026-13", "2026-00", "20 Sept", "2026-9-8",
            "2026-09-20T00:00:00Z", "2026-02-30", "٢٠٢٦-٠٩-٢٠")) {
            assertNull(junk, RoadFoodDates.dayText(junk, enUS))
            assertNull(junk, doc(junk))
        }
        // The clause goes with it.
        assertNull(line("20 Sept", null))
        assertEquals("Checked Sep 20, 2026", line("20 Sept", "2026-09-20"))
        assertEquals("Published Nov 2022", line("2022-11", "Sept 20"))
    }

    /** A day key is a calendar day, not an instant: the edges of a year stay where the file put them. */
    @Test fun `a day key stays on its own day at the edges of a year`() {
        assertEquals("Jan 1, 2026", RoadFoodDates.dayText("2026-01-01", enUS))
        assertEquals("Dec 31, 2026", RoadFoodDates.dayText("2026-12-31", enUS))
        assertEquals("Jan 2026", doc("2026-01"))
        assertEquals("Dec 2026", doc("2026-12"))
    }

    /** Nothing pins en-US on a device: a coach in Berlin reads a Berlin date. */
    @Test fun `the date is written in the reader's own locale`() {
        assertNotEquals(
            RoadFoodDates.dayText("2026-09-20", Locale.GERMANY),
            RoadFoodDates.dayText("2026-09-20", enUS)
        )
        assertEquals("20.09.2026", RoadFoodDates.dayText("2026-09-20", Locale.GERMANY))
    }

    /** Coach never warns about an old chart: nothing but the two facts reaches the line. */
    @Test fun `the line states dates and judges nothing`() {
        val text = line("2021-03-29", "2026-09-23")!!.lowercase()
        for (word in listOf("old", "stale", "out of date", "outdated", "warning", "check again", "may have changed")) {
            assertFalse("\"$word\" in \"$text\"", word in text)
        }
    }

    @Test fun `the bundled file carries each chain's own document date where its document states one`() {
        val data = parseRoadFood(File("src/main/assets/$ROAD_FOOD_ASSET").readText())
        val by = data.chains.associateBy { it.id }
        assertEquals("2022-11", by.getValue("burgerking").publishedOn)
        assertEquals("2021-03-29", by.getValue("whataburger").publishedOn)
        assertEquals("2024-10", by.getValue("chipotle").publishedOn)
        // A chain whose document states no date has no key at all.
        assertNull(by.getValue("sonic").publishedOn)
        // Every date the file holds reads: none would silently lose its clause.
        data.chains.forEach { c ->
            assertNotNull("${c.id} checkedOn ${c.checkedOn}", RoadFoodDates.dayText(c.checkedOn, enUS))
            c.publishedOn?.let { assertNotNull("${c.id} publishedOn $it", doc(it)) }
            assertNotNull(c.id, line(c.publishedOn, c.checkedOn))
        }
    }

    companion object {
        /** Android's pattern for "yMMM" in en-US; see `RoadFoodDatesSkeletonTest`. */
        const val EN_US_MONTH_YEAR = "MMM y"
    }
}
