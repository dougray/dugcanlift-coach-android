package com.dugcanlift.coach.ui

import com.dugcanlift.coach.data.DayNutrientTotals
import com.dugcanlift.coach.data.Nutrient
import com.dugcanlift.coach.data.NutrientAverage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NutrientDisplayTest {

    @Test fun `partial coverage says how many foods the total is from`() {
        val lines = dayNutrientLines(DayNutrientTotals(21.5, 48.0, 1840.0, 5, 5, 4, 3))
        assertEquals(
            listOf("Saturated fat 21.5 g", "Sugar 48 g · from 4 of 5 foods", "Sodium 1,840 mg · from 3 of 5 foods"),
            lines
        )
    }

    @Test fun `an unrecorded nutrient has no line, and no totals has no lines`() {
        assertEquals(listOf("Sodium 2,310 mg"), dayNutrientLines(DayNutrientTotals(null, null, 2310.0, 6, 0, 0, 6)))
        assertTrue(dayNutrientLines(null).isEmpty())
    }

    @Test fun `amounts round grams to one decimal and sodium to whole grouped milligrams`() {
        assertEquals("0.1 g", formatNutrientAmount(0.05, Nutrient.SUGAR))
        assertEquals("12 g", formatNutrientAmount(11.96, Nutrient.SATURATED_FAT))
        assertEquals("1,000 mg", formatNutrientAmount(999.5, Nutrient.SODIUM))
        assertEquals("0 mg", formatNutrientAmount(0.0, Nutrient.SODIUM))
    }

    @Test fun `an average always says how many days it is over`() {
        assertEquals("Sodium 2,105 mg a day · 4 days", nutrientAverageLine(NutrientAverage(Nutrient.SODIUM, 2105.25, 4, 0)))
        assertEquals("Sugar 30.5 g a day · 1 day", nutrientAverageLine(NutrientAverage(Nutrient.SUGAR, 30.5, 1, 0)))
        assertEquals(
            "Saturated fat 18.3 g a day · 3 days, 1 from only some foods",
            nutrientAverageLine(NutrientAverage(Nutrient.SATURATED_FAT, 18.26, 3, 1))
        )
    }
}
