package com.dugcanlift.coach.ui

import com.dugcanlift.coach.data.Recipe
import com.dugcanlift.coach.data.RecipeCatalog
import com.dugcanlift.coach.data.parseRecipeCatalog
import com.dugcanlift.kit.RecipeNutrition
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * The library card's "N kcal/serving" rounds half away from zero through the
 * catalogue's own helper, so a recipe reads the same number in the catalogue
 * and in the library.
 */
class RecipeCardLineTest {

    private fun recipe(calories: Double) = Recipe(
        id = "r", name = "R", servings = 4.0, rawIngredients = listOf("1 egg"),
        nutritionPerServing = RecipeNutrition(calories = calories, proteinG = 1.0)
    )

    @Test fun `calories round half away from zero, never truncate`() {
        assertEquals("Serves 4 · 1 ingredient(s) · 225 kcal/serving", recipeCardLine(recipe(224.525)))
        assertEquals("Serves 4 · 1 ingredient(s) · 224 kcal/serving", recipeCardLine(recipe(224.49)))
        assertEquals("Serves 4 · 1 ingredient(s) · 150 kcal/serving", recipeCardLine(recipe(149.68333333333334)))
        assertEquals("half away from zero, not half to even", "Serves 4 · 1 ingredient(s) · 3 kcal/serving", recipeCardLine(recipe(2.5)))
    }

    @Test fun `a catalogue copy reads the same number on its card as in the catalogue`() {
        val potPie = parseRecipeCatalog(File("src/main/assets/recipes.json").readText())
            .first { it.id == "gutenberg-26558:beef-pot-pie" }
        val copied = potPie.makeRecipe(chosenServings = 4.0)
        val shown = RecipeCatalog.macroLine(potPie.nutrition(4.0)!!).substringBefore(" kcal")
        assertEquals("225", shown)
        assertEquals("Serves 4 · 6 ingredient(s) · 225 kcal/serving", recipeCardLine(copied))
    }

    @Test fun `no macros, or only placeholder zeros, adds no calorie clause`() {
        assertEquals("Serves 4 · 1 ingredient(s)", recipeCardLine(recipe(224.525).copy(nutritionPerServing = null)))
        assertEquals(
            "Serves 4 · 1 ingredient(s)",
            recipeCardLine(recipe(0.0).copy(nutritionPerServing = RecipeNutrition(sodiumMg = 300.0)))
        )
    }
}
