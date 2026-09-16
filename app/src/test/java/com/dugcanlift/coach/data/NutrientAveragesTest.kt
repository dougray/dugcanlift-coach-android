package com.dugcanlift.coach.data

import org.junit.Assert.*
import org.junit.Test

class NutrientAveragesTest {
    private fun day(key: String, totals: DayNutrientTotals?) =
        TrainingDay(key, null, null, null, null, null, null, null, null, null, emptyList(), emptyList(), nutrientTotals = totals)

    private fun client(vararg days: TrainingDay) = Client("c", "Doug", "lb", null, 0, null, days.toList())

    @Test fun `averages count only days that recorded the nutrient`() {
        val c = client(
            day("2026-09-14", DayNutrientTotals(10.0, null, 2000.0, 4, 4, 0, 4)),
            day("2026-09-13", DayNutrientTotals(null, 30.0, 1000.0, 3, 0, 3, 2)),
            day("2026-09-12", null) // logged nothing of the three: not a zero-sodium day
        )
        val averages = Stats.nutrientAverages(c, 7, "2026-09-14").associateBy { it.nutrient }
        val sodium = averages.getValue(Nutrient.SODIUM)
        assertEquals(1500.0, sodium.perDay, 1e-9)
        assertEquals(2, sodium.days)
        assertEquals(1, sodium.partialDays) // 2 of 3 foods on the 13th
        assertEquals(10.0, averages.getValue(Nutrient.SATURATED_FAT).perDay, 1e-9)
        assertEquals(1, averages.getValue(Nutrient.SATURATED_FAT).days)
        assertEquals(30.0, averages.getValue(Nutrient.SUGAR).perDay, 1e-9)
    }

    @Test fun `days outside the window are left out, and the end day is inside it`() {
        val c = client(
            day("2026-09-14", DayNutrientTotals(null, null, 1000.0, 1, 0, 0, 1)),
            day("2026-09-08", DayNutrientTotals(null, null, 3000.0, 1, 0, 0, 1)),
            day("2026-09-07", DayNutrientTotals(null, null, 9000.0, 1, 0, 0, 1)),
            day("2026-09-15", DayNutrientTotals(null, null, 9000.0, 1, 0, 0, 1))
        )
        val week = Stats.nutrientAverages(c, 7, "2026-09-14").single()
        assertEquals(2000.0, week.perDay, 1e-9)
        assertEquals(2, week.days)
        assertEquals(3, Stats.nutrientAverages(c, 28, "2026-09-14").single().days)
    }

    @Test fun `nothing recorded is no average at all`() {
        assertTrue(Stats.nutrientAverages(client(day("2026-09-14", null)), 7, "2026-09-14").isEmpty())
        assertTrue(Stats.nutrientAverages(client(), 28, "2026-09-14").isEmpty())
    }
}
