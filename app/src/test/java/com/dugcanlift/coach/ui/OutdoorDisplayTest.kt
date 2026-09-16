package com.dugcanlift.coach.ui
import com.dugcanlift.coach.data.OutdoorActivity
import com.dugcanlift.coach.data.OutdoorBest
import com.dugcanlift.coach.data.TrainingDay
import java.time.ZoneOffset
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

/** The strings Coach web's route.js shows, which are LIFT's own. */
class OutdoorDisplayTest {
    @Test fun `miles for a pounds client, kilometres for a kilograms one`() {
        assertEquals("mi", distanceUnitFor("lb")); assertEquals("km", distanceUnitFor("kg"))
    }
    @Test fun `durations read as minutes and seconds, with hours only past one`() {
        assertEquals("28:40", formatActivityDuration(1720)); assertEquals("1:02:10", formatActivityDuration(3730))
        assertEquals("0:05", formatActivityDuration(5)); assertEquals("50:00", formatActivityDuration(3000))
    }
    @Test fun `distances carry two decimals and the unit`() {
        assertEquals("3.11 mi", formatActivityDistance(5012, "mi")); assertEquals("5.01 km", formatActivityDistance(5012, "km"))
        assertEquals("6.21 mi", formatActivityDistance(10001, "mi"))
    }
    @Test fun `pace converts seconds per kilometre into the client's unit`() {
        assertEquals("8:03 /mi", formatPace(300.0, "mi")); assertEquals("5:00 /km", formatPace(300.0, "km"))
    }
    @Test fun `an activity's pace needs at least a kilometre and some time`() {
        assertEquals("16:30 /mi", activityPace(2795, 1720, "mi"))
        assertEquals("10:15 /km", activityPace(2795, 1720, "km"))
        assertNull(activityPace(999, 600, "mi")); assertNull(activityPace(5000, 0, "km"))
    }
    @Test fun `best headings count activities in the singular and plural`() {
        assertEquals("Run · 2 activities", bestHeading(OutdoorBest(0, 2, 10001, 3000, 300)))
        assertEquals("Walk · 1 activity", bestHeading(OutdoorBest(1, 1, 300, 240, null)))
        assertEquals("Hike", outdoorTypeLabel(2)); assertNull(outdoorTypeLabel(3))
    }
    @Test fun `a null best is a dash, never zero`() {
        assertEquals(listOf("Farthest" to "0.19 mi", "Longest" to "4:00", "Fastest pace" to "—"), bestStats(OutdoorBest(1, 1, 300, 240, null), "mi"))
        assertEquals(listOf("Farthest" to "—", "Longest" to "—", "Fastest pace" to "—"), bestStats(OutdoorBest(2, 1, null, null, null), "km"))
    }
    @Test fun `dates and summaries`() {
        assertEquals("Sun, Sep 13", formatRouteDate(1789259200, ZoneOffset.UTC, Locale.US))
        assertEquals("Sep 13", formatShortDay("2026-09-13", Locale.US)); assertEquals("2026-9-3", formatShortDay("2026-9-3", Locale.US))
        assertEquals("1.74 mi · 28:40", activitySummary(OutdoorActivity(0, 1720, 2795, 37), "mi"))
    }
    @Test fun `recent is outdoor days only, newest first, capped`() {
        fun day(key: String, outdoor: Boolean) = TrainingDay(key, null, null, null, null, null, null, null, null, null, emptyList(), emptyList(),
            if (outdoor) listOf(OutdoorActivity(0, 60, 100, 0)) else emptyList())
        val days = listOf(day("2026-09-01", true), day("2026-09-03", false), day("2026-09-05", true), day("2026-09-04", true))
        assertEquals(listOf("2026-09-05", "2026-09-04"), recentOutdoorDays(days, limit = 2).map { it.dayKey })
    }
}
