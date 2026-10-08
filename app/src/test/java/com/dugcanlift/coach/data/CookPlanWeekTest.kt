package com.dugcanlift.coach.data

import com.dugcanlift.kit.PlanDecodeResult
import com.dugcanlift.kit.PlanLinkCodec
import com.dugcanlift.kit.RecipeNutrition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cook → Plan books a week, not "today, as dinner, one serving".
 *
 * The wire assertions decode with the kit's [PlanLinkCodec], the decoder LIFT
 * Android ships, as [CookPlanEncoderTest] and [TrainPlanSendTest] do.
 */
class CookPlanWeekTest {

    private val week = PlanWeek("2026-09-14")
    private val chili = Recipe(
        id = "r1", name = "Chili", servings = 4.0,
        rawIngredients = listOf("500 g beef mince"),
        nutritionPerServing = RecipeNutrition(520.0, 38.0, 30.0, 24.0, 9.0)
    )

    @Test fun `a booking lands on the day, slot and servings the coach chose`() {
        val meal = CookPlanWeek.book(chili, "c1", "2026-09-16", "lunch", servings = 2.0)
        assertEquals("2026-09-16", meal.dayKey)
        assertEquals("lunch", meal.meal)
        assertEquals(2.0, meal.servings, 0.0)
        assertEquals("c1", meal.clientId)
        assertEquals("Chili", meal.recipeName)
        assertEquals(520.0, meal.snapshotNutrition!!.calories, 0.0)
    }

    @Test fun `a booked week goes out as that week, slot for slot`() {
        val meals = listOf(
            CookPlanWeek.book(chili, "c1", "2026-09-14", "breakfast"),
            CookPlanWeek.book(chili, "c1", "2026-09-16", "lunch").copy(servings = 1.5),
            CookPlanWeek.book(chili, "c1", "2026-09-20", "snack")
        )
        val fragment = CookPlanEncoder.encode(
            CookPlanWeek.inWeek(meals, week), mapOf("r1" to chili), "c1", "Doug"
        )
        val payload = (PlanLinkCodec.decode(fragment, "c1") as PlanDecodeResult.Success).payload
        assertEquals(listOf("2026-09-14", "2026-09-16", "2026-09-20"), payload.meals.map { it.date })
        assertEquals(listOf(0, 1, 3), payload.meals.map { it.mealSlot })
        assertEquals(listOf(1.0, 1.5, 1.0), payload.meals.map { it.servings })
    }

    @Test fun `only the shown week is sent and shopped for`() {
        val meals = listOf(
            CookPlanWeek.book(chili, "c1", "2026-09-13", "dinner"),
            CookPlanWeek.book(chili, "c1", "2026-09-14", "dinner"),
            CookPlanWeek.book(chili, "c1", "2026-09-20", "dinner"),
            CookPlanWeek.book(chili, "c1", "2026-09-21", "dinner")
        )
        assertEquals(listOf("2026-09-14", "2026-09-20"), CookPlanWeek.inWeek(meals, week).map { it.dayKey })
        assertEquals(listOf("2026-09-21"), CookPlanWeek.inWeek(meals, week.advanced(1)).map { it.dayKey })
    }

    @Test fun `meals are found by day and slot, and an unknown slot name reads as dinner`() {
        val meals = listOf(
            CookPlanWeek.book(chili, "c1", "2026-09-14", "lunch"),
            PlannedMeal(recipeId = "r1", clientId = "c1", dayKey = "2026-09-14", meal = "Supper")
        )
        assertEquals(1, CookPlanWeek.at(meals, "2026-09-14", "lunch").size)
        assertEquals("Supper", CookPlanWeek.at(meals, "2026-09-14", "dinner").single().meal)
        assertTrue(CookPlanWeek.at(meals, "2026-09-15", "lunch").isEmpty())
    }

    @Test fun `servings read as a coach would say them`() {
        assertEquals("1 serving", CookPlanWeek.servingsLabel(1.0))
        assertEquals("1.5 servings", CookPlanWeek.servingsLabel(1.5))
        assertEquals("0.5 servings", CookPlanWeek.servingsLabel(0.5))
        assertEquals("4 servings", CookPlanWeek.servingsLabel(4.0))
    }
}
