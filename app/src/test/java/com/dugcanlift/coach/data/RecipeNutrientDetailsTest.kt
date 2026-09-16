package com.dugcanlift.coach.data

import com.dugcanlift.kit.CompactEncoding
import com.dugcanlift.kit.PlanDecodeResult
import com.dugcanlift.kit.PlanLinkCodec
import com.dugcanlift.kit.RecipeNutrition
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Saturated fat, sugar and sodium on a recipe: promoted from the unknown-key bag to real fields,
 * carried in backups (BACKUP-FORMAT `nutritionPerServing`) and sent as PLAN-FORMAT's `ux`.
 */
class RecipeNutrientDetailsTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun recipeJson(nutrition: String) =
        JSONObject("""{"id":"r1","name":"Oats","servings":2,"ingredients":[],"nutritionPerServing":$nutrition}""")

    // ---- migration from nutritionUnknownKeys ----

    @Test fun `values an earlier build parked in the unknown bag become fields`() {
        val r = recipeFromJson(recipeJson("""{"calories":320,"proteinG":12,"carbsG":52,"fatG":8,"fiberG":7,"saturatedFatG":1.5,"sugarG":9,"sodiumMg":140,"potassiumMg":300}"""))
        val n = r.nutritionPerServing!!
        assertEquals(1.5, n.saturatedFatG!!, 0.0)
        assertEquals(9.0, n.sugarG!!, 0.0)
        assertEquals(140.0, n.sodiumMg!!, 0.0)
        // No longer duplicated into the bag, while a key still unmodelled stays there.
        val bag = r.nutritionUnknownKeys!!
        assertFalse(bag.has("sugarG")); assertFalse(bag.has("sodiumMg")); assertFalse(bag.has("saturatedFatG"))
        assertEquals(300, bag.getInt("potassiumMg"))
    }

    @Test fun `a cook library stored by an earlier build loses nothing on the next save`() {
        // What the previous build wrote: the three sat in nutritionPerServing only because the
        // unknown bag was copied back in. Loading and saving it here must keep every value.
        val repo = CookRepository(tmp.root)
        java.io.File(tmp.root, "cook-library.json").writeText(
            JSONObject().put("recipes", JSONArray().put(recipeJson("""{"calories":320,"proteinG":12,"carbsG":52,"fatG":8,"fiberG":7,"sugarG":9,"sodiumMg":140}""")))
                .put("meals", JSONArray()).toString()
        )
        val loaded = repo.load().recipes.single()
        repo.upsertRecipe(loaded.copy(name = "Overnight oats"))
        val n = repo.load().recipes.single().nutritionPerServing!!
        assertEquals(9.0, n.sugarG!!, 0.0)
        assertEquals(140.0, n.sodiumMg!!, 0.0)
        assertNull(n.saturatedFatG)
    }

    @Test fun `a value that is not a number stays carried rather than being dropped`() {
        val r = recipeFromJson(recipeJson("""{"calories":320,"proteinG":12,"carbsG":52,"fatG":8,"fiberG":7,"sodiumMg":"140 mg"}"""))
        assertNull(r.nutritionPerServing!!.sodiumMg)
        assertEquals("140 mg", r.toJson().getJSONObject("nutritionPerServing").getString("sodiumMg"))
    }

    @Test fun `unknown stays absent on the way out, never zero`() {
        val out = recipeFromJson(recipeJson("""{"calories":320,"proteinG":12,"carbsG":52,"fatG":8,"fiberG":7,"sugarG":9}"""))
            .toJson().getJSONObject("nutritionPerServing")
        assertEquals(9.0, out.getDouble("sugarG"), 0.0)
        assertFalse(out.has("saturatedFatG")); assertFalse(out.has("sodiumMg"))
    }

    @Test fun `a planned meal's snapshot carries the three too`() {
        val meal = plannedMealFromJson(JSONObject("""{"id":"m1","recipeID":"r1","dayKey":"2026-09-10","snapshotNutrition":{"calories":320,"proteinG":12,"carbsG":52,"fatG":8,"fiberG":7,"sodiumMg":140}}"""))
        assertEquals(140.0, meal.snapshotNutrition!!.sodiumMg!!, 0.0)
        assertEquals(140.0, meal.toJson().getJSONObject("snapshotNutrition").getDouble("sodiumMg"), 0.0)
    }

    // ---- backups ----

    @Test fun `the iOS fixture's sugar and sodium are fields now and survive a backup round trip`() {
        val file = javaClass.getResourceAsStream("/fixtures/coach-ios-backup-v2.json")!!.bufferedReader().readText()
        val r = BackupCodec.restore(file)
        assertEquals(9.0, r.recipes.single().nutritionPerServing!!.sugarG!!, 0.0)
        assertEquals(140.0, r.meals.single().snapshotNutrition!!.sodiumMg!!, 0.0)
        // Written before day-level details existed: loads with them unknown.
        assertNull(r.clients.single().days.single().nutrientTotals)
        assertNull(r.clients.single().days.single().foodEntries.single().sodiumMg)

        val again = BackupCodec.restore(BackupCodec.export(r.clients, r.preservedLibrary, r.recipes, r.meals, r.routines, r.sessions))
        // Nothing in this recipe is unmodelled any more, so the data classes compare whole.
        assertNull(r.recipes.single().nutritionUnknownKeys)
        assertEquals(r.recipes, again.recipes)
        assertEquals(140.0, again.recipes.single().nutritionPerServing!!.sodiumMg!!, 0.0)
    }

    @Test fun `day totals and itemised details round trip through a backup under their documented names`() {
        val day = TrainingDay("2026-09-14", null, null, null, null, 2100.0, 150.0, 70.0, 220.0, 30.0, emptyList(),
            listOf(ClientFoodEntry("Oats", 2.0, 380.0, 13.0, 6.6, 68.0, 10.0, 0, saturatedFatG = 1.2, sugarG = null, sodiumMg = 4.0)),
            nutrientTotals = DayNutrientTotals(1.2, null, 4.0, 1, 1, 0, 1))
        val client = Client("c1", "Doug", "lb", "android", 1_758_307_200_000, null, listOf(day))
        val json = JSONObject(BackupCodec.export(listOf(client), null, emptyList(), emptyList(), emptyList(), emptyList()))

        val d = json.getJSONArray("clients").getJSONObject(0).getJSONArray("days").getJSONObject(0)
        val t = d.getJSONObject("nutrientTotals")
        assertEquals(setOf("saturatedFatG", "sugarG", "sodiumMg", "foods", "withSaturatedFat", "withSugar", "withSodium"), t.keys().asSequence().toSet())
        assertTrue(t.isNull("sugarG"))
        val f = d.getJSONArray("foodEntries").getJSONObject(0)
        assertEquals(1.2, f.getDouble("saturatedFatG"), 0.0)
        assertEquals(4.0, f.getDouble("sodiumMg"), 0.0)
        assertFalse(f.has("sugarG"))

        assertEquals(listOf(day), BackupCodec.restore(json.toString()).clients.single().days)
    }

    // ---- ux ----

    private fun planJson(fragment: String): JSONObject {
        assertTrue(fragment.startsWith("1z"))
        return JSONObject(String(CompactEncoding.inflateRaw(CompactEncoding.base64UrlDecode(fragment.substring(2)))))
    }

    private fun encode(recipe: Recipe) =
        CookPlanEncoder.encode(listOf(PlannedMeal(recipeId = recipe.id, clientId = "c1", dayKey = "2026-09-14")), mapOf(recipe.id to recipe), "c1", "Doug")

    @Test fun `ux carries the three per serving, rounded, and the client's decoder reads it`() {
        val recipe = Recipe(id = "r1", name = "Chili", servings = 4.0,
            nutritionPerServing = RecipeNutrition(520.0, 38.0, 30.0, 24.0, 9.0, saturatedFatG = 8.26, sugarG = 6.04, sodiumMg = 812.5))
        val fragment = encode(recipe)
        val r = planJson(fragment).getJSONArray("r").getJSONObject(0)
        assertEquals("[8.3,6,813]", r.getJSONArray("ux").toString())
        assertEquals(5, r.getJSONArray("u").length())

        val decoded = (PlanLinkCodec.decode(fragment, "c1") as PlanDecodeResult.Success).payload.recipes.single()
        val ux = decoded.nutrientDetailsPerServing!!
        assertEquals(8.3, ux.saturatedFatG!!, 1e-9); assertEquals(6.0, ux.sugarG!!, 1e-9); assertEquals(813.0, ux.sodiumMg!!, 1e-9)
    }

    @Test fun `only trailing nulls are trimmed from ux`() {
        val sugarOnly = Recipe(id = "r1", name = "Jam", nutritionPerServing = RecipeNutrition(50.0, 0.0, 12.0, 0.0, 0.0, sugarG = 12.0))
        assertEquals("[null,12]", planJson(encode(sugarOnly)).getJSONArray("r").getJSONObject(0).getJSONArray("ux").toString())
        val satFatOnly = Recipe(id = "r1", name = "Butter", nutritionPerServing = RecipeNutrition(100.0, 0.0, 0.0, 11.0, 0.0, saturatedFatG = 7.0))
        assertEquals("[7]", planJson(encode(satFatOnly)).getJSONArray("r").getJSONObject(0).getJSONArray("ux").toString())
    }

    @Test fun `ux is omitted when none of the three is known`() {
        val recipe = Recipe(id = "r1", name = "Chili", nutritionPerServing = RecipeNutrition(520.0, 38.0, 30.0, 24.0, 9.0))
        assertFalse(planJson(encode(recipe)).getJSONArray("r").getJSONObject(0).has("ux"))
        assertFalse(planJson(encode(Recipe(id = "r1", name = "Plain"))).getJSONArray("r").getJSONObject(0).has("ux"))
    }

    @Test fun `details without macros send ux and no zero u`() {
        val recipe = Recipe(id = "r1", name = "Broth", nutritionPerServing = RecipeNutrition(sodiumMg = 900.0))
        val fragment = encode(recipe)
        val r = planJson(fragment).getJSONArray("r").getJSONObject(0)
        assertFalse(r.has("u"))
        assertEquals("[null,null,900]", r.getJSONArray("ux").toString())
        val decoded = (PlanLinkCodec.decode(fragment, "c1") as PlanDecodeResult.Success).payload.recipes.single()
        assertNull(decoded.nutritionPerServing)
        assertEquals(900.0, decoded.nutrientDetailsPerServing!!.sodiumMg!!, 0.0)
    }
}
