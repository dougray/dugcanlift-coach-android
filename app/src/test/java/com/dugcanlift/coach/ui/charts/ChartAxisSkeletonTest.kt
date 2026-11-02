package com.dugcanlift.coach.ui.charts

import android.text.format.DateFormat
import com.dugcanlift.coach.ui.RoadFoodDates
import com.dugcanlift.coach.ui.RoadFoodDatesTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * The locale patterns the app really asks Android for. The strings are pinned
 * in `ChartAxisTest` and `RoadFoodDatesTest` under those patterns; this checks
 * they are what a US reader gets, so the pinned words are real ones.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ChartAxisSkeletonTest {

    @Test fun `the chart skeletons read as the tests pin them`() {
        assertEquals(ChartAxisTest.EN_US_DAY, DateFormat.getBestDateTimePattern(Locale.US, ChartAxis.DAY_SKELETON))
        assertEquals(
            ChartAxisTest.EN_US_DAY_YEAR,
            DateFormat.getBestDateTimePattern(Locale.US, ChartAxis.DAY_YEAR_SKELETON)
        )
    }

    @Test fun `the road food month skeleton reads as the tests pin it`() {
        val pattern = DateFormat.getBestDateTimePattern(Locale.US, RoadFoodDates.MONTH_YEAR_SKELETON)
        assertEquals(RoadFoodDatesTest.EN_US_MONTH_YEAR, pattern)
        assertEquals(
            "Published Nov 2022 · checked Sep 20, 2026",
            RoadFoodDates.line("2022-11", "2026-09-20", Locale.US, pattern)
        )
    }

    /** A German reader gets the day before the month, as Germans write it. */
    @Test fun `another locale gets its own order`() {
        val pattern = DateFormat.getBestDateTimePattern(Locale.GERMANY, ChartAxis.DAY_SKELETON)
        assertEquals("27. Sept.", ChartAxis.dayLabel("2026-09-27", Locale.GERMANY, pattern))
    }
}
