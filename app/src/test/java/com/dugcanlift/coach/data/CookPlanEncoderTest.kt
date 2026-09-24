package com.dugcanlift.coach.data

import com.dugcanlift.kit.PlanDecodeResult
import com.dugcanlift.kit.PlanLinkCodec
import com.dugcanlift.kit.RecipeNutrition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every assertion here runs the encoder's output through
 * [PlanLinkCodec.decode] -- the shipped decoder LIFT Android actually uses to
 * read a coach's link. Checking the JSON by hand would only prove this file
 * agrees with itself; decoding proves a client can read what a coach sends.
 */
class CookPlanEncoderTest {

    private val chili = Recipe(
        id = "r1", name = "Chili", servings = 4.0,
        rawIngredients = listOf("500 g beef mince", "2 tins chopped tomatoes"),
        steps = listOf("Brown the beef", "Simmer"),
        nutritionPerServing = RecipeNutrition(520.0, 38.0, 30.0, 24.0, 9.0)
    )
    private val oats = Recipe(id = "r2", name = "Oats", servings = 2.0,
                              rawIngredients = listOf("100 g rolled oats"))

    private fun decode(fragment: String, lifter: String = "c1") =
        PlanLinkCodec.decode(fragment, lifter)

    private fun success(fragment: String, lifter: String = "c1") =
        (decode(fragment, lifter) as PlanDecodeResult.Success).payload

    @Test fun `a week a coach sends is readable by the client's app`() {
        val meals = listOf(
            PlannedMeal(recipeId = "r1", clientId = "c1", dayKey = "2026-09-14",
                        meal = "dinner", servings = 2.0),
            PlannedMeal(recipeId = "r2", clientId = "c1", dayKey = "2026-09-15",
                        meal = "breakfast", servings = 1.0)
        )
        val payload = success(
            CookPlanEncoder.encode(meals, mapOf("r1" to chili, "r2" to oats), "c1", "Doug")
        )

        assertEquals("Doug", payload.coachName)
        assertEquals(2, payload.recipes.size)
        assertEquals(2, payload.meals.size)
        assertEquals("Chili", payload.recipes[payload.meals[0].recipeIndex].name)
        assertEquals("Oats", payload.recipes[payload.meals[1].recipeIndex].name)
    }

    @Test fun `ingredients steps and macros all make the trip`() {
        val meals = listOf(PlannedMeal(recipeId = "r1", dayKey = "2026-09-14"))
        val recipe = success(CookPlanEncoder.encode(meals, mapOf("r1" to chili), "c1", "Doug")).recipes.single()

        assertEquals(listOf("500 g beef mince", "2 tins chopped tomatoes"), recipe.ingredients)
        assertEquals(listOf("Brown the beef", "Simmer"), recipe.steps)
        assertEquals(520.0, recipe.nutritionPerServing!!.calories, 0.001)
        assertEquals(9.0, recipe.nutritionPerServing!!.fiberG, 0.001)
        assertEquals(4.0, recipe.servings, 0.001)
    }

    @Test fun `meal slots survive as the slots they were`() {
        val meals = listOf("breakfast", "lunch", "dinner", "snack").mapIndexed { i, slot ->
            PlannedMeal(recipeId = "r1", dayKey = "2026-09-1${i + 1}", meal = slot)
        }
        val decoded = success(CookPlanEncoder.encode(meals, mapOf("r1" to chili), "c1", "Doug"))
        assertEquals(listOf(0, 1, 2, 3), decoded.meals.map { it.mealSlot })
    }

    @Test fun `an unknown meal name lands on dinner rather than on breakfast`() {
        // The decoder's own default for a missing slot is 2. Falling back to 0
        // would silently move every unrecognised meal to breakfast.
        assertEquals(2, CookPlanEncoder.slot("brunch"))
        assertEquals(2, CookPlanEncoder.slot(""))
        assertEquals(0, CookPlanEncoder.slot("Breakfast"))
    }

    @Test fun `a meal whose recipe is missing is dropped, not mis-indexed`() {
        // `x` indexes into the inlined recipe list. Keeping the meal would point
        // it at whichever recipe happened to occupy that slot.
        val meals = listOf(
            PlannedMeal(recipeId = "gone", dayKey = "2026-09-14"),
            PlannedMeal(recipeId = "r1", dayKey = "2026-09-15")
        )
        val payload = success(CookPlanEncoder.encode(meals, mapOf("r1" to chili), "c1", "Doug"))
        assertEquals(1, payload.meals.size)
        assertEquals("Chili", payload.recipes[payload.meals.single().recipeIndex].name)
    }

    @Test fun `the same recipe twice in a week is inlined once`() {
        val meals = listOf(
            PlannedMeal(recipeId = "r1", dayKey = "2026-09-14"),
            PlannedMeal(recipeId = "r1", dayKey = "2026-09-16")
        )
        val payload = success(CookPlanEncoder.encode(meals, mapOf("r1" to chili), "c1", "Doug"))
        assertEquals(1, payload.recipes.size)
        assertEquals(2, payload.meals.size)
        assertTrue(payload.meals.all { it.recipeIndex == 0 })
    }

    @Test fun `a link addressed to one client is refused by another`() {
        val meals = listOf(PlannedMeal(recipeId = "r1", dayKey = "2026-09-14"))
        val fragment = CookPlanEncoder.encode(meals, mapOf("r1" to chili), "c1", "Doug")
        assertTrue(decode(fragment, "someone-else") is PlanDecodeResult.NotAddressedToYou)
    }

    @Test fun `an empty week still decodes, carrying no recipes or meals`() {
        // Absent, not []. A coach who plans only training sends no `r` or `m`,
        // and this must not become a malformed payload.
        val payload = success(CookPlanEncoder.encode(emptyList(), emptyMap(), "c1", "Doug"))
        assertEquals(emptyList<Any>(), payload.recipes)
        assertEquals(emptyList<Any>(), payload.meals)
    }

    /* ---------------- what a Send files ---------------- */

    @Test fun `the link and the record are one encode, not two`() {
        // The record must be of the plan that was actually sent. Two encodes is how a coach ends
        // up with a row describing a week they did not ship -- the reason [TrainPlanEncoder] was
        // split the same way.
        val meals = listOf(PlannedMeal(recipeId = "r1", clientId = "c1", dayKey = "2026-09-14",
                                       meal = "dinner", servings = 2.0))
        val payload = CookPlanEncoder.payload(meals, mapOf("r1" to chili), "c1", "Doug")
        val fragment = CookPlanEncoder.encode(meals, mapOf("r1" to chili), "c1", "Doug")
        assertEquals("the fragment is that payload and no other", PlanEnvelope.fragment(payload), fragment)
        assertEquals("Chili", success(fragment).recipes.single().name)
    }

    @Test fun `a filed food plan is a plan the Booked card can read`() {
        // Cook's Send files a row now, because the card compares meals. A payload with `m` and no
        // `w` books days all the same -- see PlanLog -- so this is what makes those rows reachable
        // on this app at all.
        val meals = listOf(
            PlannedMeal(recipeId = "r1", clientId = "c1", dayKey = "2026-09-14",
                        meal = "dinner", servings = 2.0),
            PlannedMeal(recipeId = "r2", clientId = "c1", dayKey = "2026-09-15",
                        meal = "breakfast", servings = 1.0)
        )
        val payload = CookPlanEncoder.payload(meals, mapOf("r1" to chili, "r2" to oats), "c1", "Doug")
        val row = SentPlan("p1", "c1", 1_700_000_000L, SentPlans.hash(payload), payload.toString())
        val bookings = PlanLog.bookingsIn(row.payload())
        assertEquals(listOf("2026-09-14", "2026-09-15"), bookings.map { it.date })
        assertEquals(
            listOf("Dinner · Chili · 2 servings", "Breakfast · Oats · 1 serving"),
            bookings.flatMap { day -> day.meals.map { it.title } }
        )
        assertTrue("a food plan books no training", bookings.none { it.workout })
    }

    @Test fun `Cook's Send files what it sent`() {
        // The call site, checked as source, for the reason Coach web checks its own view code: a
        // screen that builds the link and forgets the row leaves the card permanently empty on
        // this app, and no unit test of PlanLog would ever notice.
        val source = java.io.File("src/main/java/com/dugcanlift/coach/ui/CookScreen.kt").readText()
        assertTrue("CookScreen moved; re-point this test", source.contains("CookPlanEncoder.payload("))
        assertTrue("the fragment still goes to the chooser", source.contains("PlanEnvelope.fragment(payload)"))
        assertTrue("and the payload is filed", source.contains("SentPlanRepository(context.filesDir).record("))
    }

    @Test fun `the fragment is compressed`() {
        val meals = List(12) { PlannedMeal(recipeId = "r1", dayKey = "2026-09-14") }
        val fragment = CookPlanEncoder.encode(meals, mapOf("r1" to chili), "c1", "Doug")
        assertTrue("deflate-encoded fragments start 1z", fragment.startsWith("1z"))
        assertTrue("and must still decode", decode(fragment) is PlanDecodeResult.Success)
    }
}
