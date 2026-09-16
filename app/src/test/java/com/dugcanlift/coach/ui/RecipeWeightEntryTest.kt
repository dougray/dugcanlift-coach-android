package com.dugcanlift.coach.ui

import com.dugcanlift.coach.data.RecipeWeightUnit.GRAMS
import com.dugcanlift.coach.data.RecipeWeightUnit.OUNCES
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pins the weight field's rules: grams on the model, and a unit switch that
 *  converts rather than relabels. */
class RecipeWeightEntryTest {

    @Test fun `grams are stored as typed`() {
        assertEquals(1200.0, enteredWeightGrams("1200", GRAMS)!!, 1e-9)
    }

    @Test fun `ounces are stored as grams`() {
        assertEquals(28.3495, enteredWeightGrams("1", OUNCES)!!, 1e-9)
    }

    @Test fun `blank, zero and junk are not a weight`() {
        for (text in listOf("", "  ", "0", "-3", "heavy")) assertNull(text, enteredWeightGrams(text, GRAMS))
    }

    /** The bug this rule exists for, caught on iOS first: switching units must
     *  keep the mass, not turn 1200 g into 1200 oz. */
    @Test fun `switching units converts rather than relabels`() {
        assertEquals("42.3", reweigh("1200", GRAMS, OUNCES))
        assertEquals("34019.4", reweigh("1200", OUNCES, GRAMS))
    }

    @Test fun `switching with nothing typed leaves it blank`() {
        assertEquals("", reweigh("", GRAMS, OUNCES))
    }

    @Test fun `the per-serving weight is shown in the chosen unit`() {
        assertEquals("4 servings · 300 g each", perServingWeight("1200", "4", GRAMS))
        assertEquals("1 serving · 12 oz each", perServingWeight("12", "1", OUNCES))
    }

    @Test fun `no per-serving weight without both numbers`() {
        assertNull(perServingWeight("", "4", GRAMS))
        assertNull(perServingWeight("1200", "", GRAMS))
    }
}
