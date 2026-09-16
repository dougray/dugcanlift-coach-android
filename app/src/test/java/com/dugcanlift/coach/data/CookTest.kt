package com.dugcanlift.coach.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coach iOS and Coach web write different backup files, and both decode here
 * because `BackupCodec.restore` only requires a `clients` array, which both
 * carry. Until COOK existed that did not matter -- the library was opaque
 * cargo. Modelling recipes is what makes the difference load-bearing, and
 * these pin it.
 */
class CookTest {

    private val iosRecipe = """
        {
          "id": "8E1C4C2A-0000-0000-0000-000000000001",
          "name": "Chili",
          "servings": 4,
          "steps": ["Brown the beef", "Simmer"],
          "ingredients": ["500 g beef mince", "2 tins chopped tomatoes", "a pinch of salt"],
          "nutritionPerServing": { "calories": 520, "proteinG": 38, "carbsG": 30, "fatG": 24, "fiberG": 9 }
        }
    """.trimIndent()

    private val webRecipe = """
        {
          "id": "r_7f3a",
          "name": "Chili",
          "servings": 4,
          "steps": ["Brown the beef"],
          "ingredients": [
            { "rawText": "500 g beef mince" },
            { "rawText": "2 tins chopped tomatoes" }
          ],
          "nutritionPerServing": { "calories": 520, "proteinG": 38, "carbsG": 30, "fatG": 24, "fiberG": 9 }
        }
    """.trimIndent()

    // MARK: - Both spellings of an ingredient list

    @Test fun `an iOS recipe keeps its ingredient lines`() {
        val recipe = recipeFromJson(JSONObject(iosRecipe))
        assertEquals(listOf("500 g beef mince", "2 tins chopped tomatoes", "a pinch of salt"),
                     recipe.rawIngredients)
    }

    @Test fun `a web recipe keeps its ingredient lines too`() {
        // The failure this guards: reading only the iOS spelling empties every
        // ingredient list in a web coach's library on first restore.
        val recipe = recipeFromJson(JSONObject(webRecipe))
        assertEquals(listOf("500 g beef mince", "2 tins chopped tomatoes"), recipe.rawIngredients)
    }

    @Test fun `an unreadable ingredient element is skipped, not rendered as null`() {
        val o = JSONObject("""{ "name": "Odd", "ingredients": [12, null, "1 egg"] }""")
        assertEquals(listOf("1 egg"), recipeFromJson(o).rawIngredients)
    }

    @Test fun `a recipe with no ingredients decodes rather than throwing`() {
        val recipe = recipeFromJson(JSONObject("""{ "name": "Empty" }"""))
        assertEquals(emptyList<String>(), recipe.rawIngredients)
        assertEquals(1.0, recipe.servings, 0.0)
    }

    // MARK: - Keys this app has no model for

    @Test fun `a field a newer Coach iOS adds survives the round trip`() {
        val o = JSONObject(iosRecipe).put("sourceTranscript", "from a video")
            .put("totalWeightGrams", 1200)
        val out = recipeFromJson(o).toJson()
        assertEquals("from a video", out.getString("sourceTranscript"))
        assertEquals(1200, out.getInt("totalWeightGrams"))
    }

    // MARK: - Weight

    @Test fun `a weighed recipe decodes its weight`() {
        val recipe = recipeFromJson(JSONObject(iosRecipe).put("totalWeightGrams", 1200))
        assertEquals(1200.0, recipe.totalWeightGrams!!, 1e-9)
        assertEquals(300.0, recipe.gramsPerServing!!, 1e-9)
    }

    /** Modelled now, so it must be written once from the field -- not again
     *  from the unknown bag, which is where it used to survive. */
    @Test fun `the weight is not also kept in the unknown bag`() {
        val recipe = recipeFromJson(JSONObject(iosRecipe).put("totalWeightGrams", 1200))
        assertNull(recipe.unknownKeys?.opt("totalWeightGrams"))
        assertEquals(1200.0, recipe.toJson().getDouble("totalWeightGrams"), 1e-9)
    }

    @Test fun `an unweighed recipe writes no weight at all`() {
        val out = recipeFromJson(JSONObject(iosRecipe)).toJson()
        assertTrue(!out.has("totalWeightGrams"))
    }

    /** A zero or negative weight is "not weighed", never a dish that weighs
     *  nothing -- a per-serving weight would otherwise be zero grams. */
    @Test fun `a zero or negative weight reads as unweighed`() {
        for (bad in listOf(0, -5)) {
            assertNull(recipeFromJson(JSONObject(iosRecipe).put("totalWeightGrams", bad)).totalWeightGrams)
        }
    }

    @Test fun `a modelled field is not duplicated into the unknown bag`() {
        val recipe = recipeFromJson(JSONObject(iosRecipe))
        assertNull("name is decoded, so it must not also be preserved raw",
                   recipe.unknownKeys?.opt("name"))
    }

    @Test fun `a recipe with nothing unusual carries no unknown bag at all`() {
        assertNull(recipeFromJson(JSONObject(iosRecipe)).unknownKeys)
    }

    @Test fun `an estimated flag is not laundered into a stated fact`() {
        val o = JSONObject(iosRecipe)
        o.getJSONObject("nutritionPerServing").put("estimated", true)
        val out = recipeFromJson(o).toJson()
        assertTrue(out.getJSONObject("nutritionPerServing").getBoolean("estimated"))
    }

    // MARK: - A web library normalises to the spelling this app writes

    @Test fun `a web recipe writes back in the iOS spelling, losing nothing`() {
        val out = recipeFromJson(JSONObject(webRecipe)).toJson()
        val ingredients = out.getJSONArray("ingredients")
        assertEquals(2, ingredients.length())
        assertEquals("rawText was the only field a web ingredient object carried",
                     "500 g beef mince", ingredients.getString(0))
    }

    // MARK: - Planned meals, in both spellings

    @Test fun `an iOS meal decodes`() {
        val meal = plannedMealFromJson(JSONObject("""
            { "id": "m1", "recipeID": "r1", "recipeName": "Chili", "dayKey": "2026-09-14",
              "meal": "dinner", "servings": 2, "clientID": "c1" }
        """.trimIndent()))
        assertEquals("r1", meal.recipeId)
        assertEquals("c1", meal.clientId)
        assertEquals("2026-09-14", meal.dayKey)
    }

    @Test fun `a web meal spells the same fields differently and still decodes`() {
        val meal = plannedMealFromJson(JSONObject("""
            { "id": "m1", "recipeId": "r1", "date": "2026-09-14", "meal": "lunch",
              "servings": 1, "clientId": "c1" }
        """.trimIndent()))
        assertEquals("r1", meal.recipeId)
        assertEquals("c1", meal.clientId)
        assertEquals("2026-09-14", meal.dayKey)
    }

    @Test fun `a meal belonging to nobody stays belonging to nobody`() {
        // Coach iOS's PlannedMeal carries no client field; ownership is stored
        // separately there. Inventing an owner here would put a stranger's
        // dinner in whichever client happened to be on screen.
        val meal = plannedMealFromJson(JSONObject("""{ "recipeID": "r1", "dayKey": "2026-09-14" }"""))
        assertNull(meal.clientId)
    }

    // MARK: - The shopping list

    private fun recipe(id: String, servings: Double, vararg lines: String) =
        Recipe(id = id, name = id, servings = servings, rawIngredients = lines.toList())

    @Test fun `ingredients scale by the servings planned against the servings made`() {
        val recipes = mapOf("r1" to recipe("r1", 4.0, "500 g beef mince"))
        val meals = listOf(PlannedMeal(recipeId = "r1", dayKey = "2026-09-14", servings = 2.0))
        val line = CoachShoppingList.build(meals, recipes).single()
        // Half the recipe: 500 g made for 4, planned as 2.
        assertEquals(250.0, line.amounts.values.single(), 0.001)
    }

    @Test fun `the same ingredient across two days is one line`() {
        val recipes = mapOf("r1" to recipe("r1", 1.0, "100 g oats"))
        val meals = listOf(
            PlannedMeal(recipeId = "r1", dayKey = "2026-09-14", servings = 1.0),
            PlannedMeal(recipeId = "r1", dayKey = "2026-09-15", servings = 1.0)
        )
        val lines = CoachShoppingList.build(meals, recipes)
        assertEquals(1, lines.size)
        assertEquals(200.0, lines.single().amounts.values.single(), 0.001)
    }

    @Test fun `cups and grams of the same thing are not added together`() {
        val recipes = mapOf(
            "r1" to recipe("r1", 1.0, "100 g flour"),
            "r2" to recipe("r2", 1.0, "2 cups flour")
        )
        val meals = listOf(
            PlannedMeal(recipeId = "r1", dayKey = "2026-09-14"),
            PlannedMeal(recipeId = "r2", dayKey = "2026-09-14")
        )
        val line = CoachShoppingList.build(meals, recipes).single { it.name == "flour" }
        assertEquals("two units, kept apart", 2, line.amounts.size)
    }

    @Test fun `an ingredient with no readable quantity still appears`() {
        val recipes = mapOf("r1" to recipe("r1", 1.0, "a pinch of salt"))
        val meals = listOf(PlannedMeal(recipeId = "r1", dayKey = "2026-09-14"))
        val line = CoachShoppingList.build(meals, recipes).single()
        assertTrue("it must be on the list even unquantified", line.unquantified > 0)
    }

    @Test fun `a meal whose recipe was deleted does not break the list`() {
        val meals = listOf(PlannedMeal(recipeId = "gone", dayKey = "2026-09-14"))
        assertEquals(emptyList<ShoppingLine>(), CoachShoppingList.build(meals, emptyMap()))
    }

    @Test fun `a recipe claiming zero servings does not divide by zero`() {
        val recipes = mapOf("r1" to recipe("r1", 0.0, "100 g oats"))
        val meals = listOf(PlannedMeal(recipeId = "r1", dayKey = "2026-09-14", servings = 1.0))
        val amount = CoachShoppingList.build(meals, recipes).single().amounts.values.single()
        assertTrue("finite, not NaN or infinity", amount.isFinite())
    }
}
