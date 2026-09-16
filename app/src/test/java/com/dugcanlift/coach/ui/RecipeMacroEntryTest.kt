package com.dugcanlift.coach.ui

import com.dugcanlift.kit.RecipeNutrition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the rule the recipe editor's new macro section holds.
 *
 * Coach Android had no macro fields at all until this existed, so a recipe
 * created here carried no nutrition and a coach could not correct one that
 * arrived with it. The risk in adding the fields is the opposite failure: an
 * untouched form writing zeros, which becomes a zero-calorie dinner in a
 * client's day total.
 */
class RecipeMacroEntryTest {

    private fun entered(
        calories: String = "", protein: String = "", carbs: String = "",
        fat: String = "", fiber: String = "", existing: RecipeNutrition? = null
    ) = enteredMacros(calories, protein, carbs, fat, fiber, existing)

    @Test
    fun `an untouched form writes nothing`() {
        assertNull(entered())
    }

    @Test
    fun `whitespace is not a value`() {
        assertNull(entered(calories = "   ", protein = "  "))
    }

    @Test
    fun `anything typed makes a figure`() {
        val macros = entered(calories = "420")
        assertEquals(420.0, macros!!.calories, 1e-9)
        assertEquals(0.0, macros.proteinG, 1e-9)
    }

    @Test
    fun `all five values are read`() {
        val macros = entered("420", "30", "40", "12", "7")!!
        assertEquals(420.0, macros.calories, 1e-9)
        assertEquals(30.0, macros.proteinG, 1e-9)
        assertEquals(40.0, macros.carbsG, 1e-9)
        assertEquals(12.0, macros.fatG, 1e-9)
        assertEquals(7.0, macros.fiberG, 1e-9)
    }

    /** Fibre alone is content: the form is not untouched. */
    @Test
    fun `fibre alone counts as entered`() {
        val macros = entered(fiber = "7")
        assertEquals(7.0, macros!!.fiberG, 1e-9)
        assertEquals(0.0, macros.calories, 1e-9)
    }

    /**
     * A coach correcting one number on an imported recipe has not turned it
     * into a measurement, so the flag is carried rather than reset.
     */
    @Test
    fun `estimated is carried from the existing figure`() {
        val imported = RecipeNutrition(calories = 400.0, estimated = true)
        assertTrue(entered(calories = "420", existing = imported)!!.estimated)
    }

    @Test
    fun `a hand-entered figure is not estimated`() {
        assertFalse(entered(calories = "420")!!.estimated)
    }

    /**
     * Clearing every field clears the macros rather than writing five zeros --
     * the same reason an untouched form writes nothing.
     */
    @Test
    fun `clearing every field removes the macros`() {
        val existing = RecipeNutrition(calories = 400.0, proteinG = 30.0)
        assertNull(entered(existing = existing))
    }
}
