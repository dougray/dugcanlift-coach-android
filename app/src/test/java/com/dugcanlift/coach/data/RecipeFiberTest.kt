package com.dugcanlift.coach.data

import com.dugcanlift.kit.RecipeNutrition
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A recipe's fiber is unknown when nobody stated it, never 0 g -- Coach iOS's `NutritionFacts.fiberG`
 * rule, which this build could not hold before kit-android 1.10.0 made [RecipeNutrition.fiberG]
 * nullable. Stated zeros, including every zero an earlier build wrote, stay zeros.
 */
class RecipeFiberTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun recipeJson(nutrition: String) =
        JSONObject("""{"id":"r1","name":"Oats","servings":2,"ingredients":[],"nutritionPerServing":$nutrition}""")

    @Test fun `a file with no fiber key reads as unknown and writes no key back`() {
        // The shape Coach iOS writes for a nil fiberG: the key is simply absent.
        val r = recipeFromJson(recipeJson("""{"calories":320,"proteinG":12,"carbsG":52,"fatG":8}"""))
        assertNull(r.nutritionPerServing!!.fiberG)
        assertFalse(r.toJson().getJSONObject("nutritionPerServing").has("fiberG"))
    }

    @Test fun `an explicit null is unknown too`() {
        val r = recipeFromJson(recipeJson("""{"calories":320,"proteinG":12,"carbsG":52,"fatG":8,"fiberG":null}"""))
        assertNull(r.nutritionPerServing!!.fiberG)
    }

    @Test fun `a stored zero stays zero`() {
        // Every recipe an earlier build saved has "fiberG": 0.0 when the coach left it blank. Nobody
        // can tell now which of those were unknown, so they keep meaning what they said.
        val r = recipeFromJson(recipeJson("""{"calories":320,"proteinG":12,"carbsG":52,"fatG":8,"fiberG":0.0}"""))
        assertEquals(0.0, r.nutritionPerServing!!.fiberG!!, 0.0)
        assertEquals(0.0, r.toJson().getJSONObject("nutritionPerServing").getDouble("fiberG"), 0.0)
    }

    @Test fun `a library saved by an earlier build keeps its zeros through a load and save`() {
        val repo = CookRepository(tmp.root)
        java.io.File(tmp.root, "cook-library.json").writeText(
            JSONObject().put("recipes", JSONArray().put(recipeJson("""{"calories":320,"proteinG":12,"carbsG":52,"fatG":8,"fiberG":0.0}""")))
                .put("meals", JSONArray()).toString()
        )
        repo.upsertRecipe(repo.load().recipes.single().copy(name = "Overnight oats"))
        assertEquals(0.0, repo.load().recipes.single().nutritionPerServing!!.fiberG!!, 0.0)
    }

    @Test fun `fiber that is not a number is carried rather than read as zero`() {
        val r = recipeFromJson(recipeJson("""{"calories":320,"proteinG":12,"carbsG":52,"fatG":8,"fiberG":"7 g"}"""))
        assertNull(r.nutritionPerServing!!.fiberG)
        assertEquals("7 g", r.toJson().getJSONObject("nutritionPerServing").getString("fiberG"))
    }

    @Test fun `unknown fiber survives a backup round trip as no key`() {
        val recipe = Recipe(id = "r1", name = "Oats", servings = 2.0,
            nutritionPerServing = RecipeNutrition(calories = 320.0, proteinG = 12.0, carbsG = 52.0, fatG = 8.0))
        val meal = PlannedMeal(recipeId = "r1", dayKey = "2026-09-14", snapshotNutrition = recipe.nutritionPerServing)
        val restored = BackupCodec.restore(
            BackupCodec.export(emptyList(), null, listOf(recipe), listOf(meal), emptyList(), emptyList(), emptyMap(), emptyList())
        )
        assertNull(restored.recipes.single().nutritionPerServing!!.fiberG)
        assertNull(restored.meals.single().snapshotNutrition!!.fiberG)
    }

    @Test fun `fiber alone is still macros, unknown fiber is not`() {
        assertTrue(RecipeNutrition(fiberG = 7.0).hasMacros)
        assertFalse(RecipeNutrition(sodiumMg = 140.0).hasMacros)
        assertTrue(RecipeNutrition(calories = 320.0).hasMacros)
    }

    @Test fun `the plan link sends unknown fiber as 0, the only number u can hold`() {
        // `u` is five plain numbers; Coach iOS and LIFT iOS decode it as [Double] and would reject the
        // whole plan over a null. So unknown travels as 0, exactly as Coach iOS writes `fiberG ?? 0`.
        val recipe = Recipe(id = "r1", name = "Oats", servings = 2.0,
            nutritionPerServing = RecipeNutrition(calories = 320.0, proteinG = 12.0, carbsG = 52.0, fatG = 8.0))
        val json = CookPlanEncoder.payload(listOf(PlannedMeal(recipeId = "r1", dayKey = "2026-09-14")),
            mapOf("r1" to recipe), "c1", "Doug")
        val u = json.getJSONArray("r").getJSONObject(0).getJSONArray("u")
        assertEquals(5, u.length())
        assertEquals(0.0, u.getDouble(4), 0.0)
    }
}
