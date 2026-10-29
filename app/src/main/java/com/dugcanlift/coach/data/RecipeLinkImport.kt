package com.dugcanlift.coach.data

import com.dugcanlift.kit.ImportedRecipe
import com.dugcanlift.kit.RecipeJsonLd
import org.json.JSONObject

/**
 * What a "Send to Coach" link becomes: a recipe for the editor to show, or the
 * plain sentence that says why there is none. Nothing here saves -- the coach's
 * Save in the recipe editor is the only write, the kit README's "review, then
 * save".
 *
 * The blocks go to the kit's [RecipeJsonLd], the reader LIFT Android also uses,
 * never to a copy of it: a second reader is what the kit exists to prevent.
 */
sealed interface RecipeLinkReview {
    /**
     * [recipe] is a new library recipe under [id], not yet stored. [servingsStated] is false when
     * the page published no yield, in which case `recipe.servings` is a placeholder the editor
     * never shows: the coach sets the count before Save is offered.
     */
    data class Ready(val recipe: Recipe, val servingsStated: Boolean) : RecipeLinkReview

    data class Refused(val message: String) : RecipeLinkReview
}

object RecipeLinkImport {

    // Both sentences are the wording every build shares for this feature, word for word.

    /** The link could not be unwrapped at all: a refusal in [RecipeLink.decode]. */
    const val UNREADABLE = "That recipe link could not be read, so nothing was added. Open the " +
        "recipe page and tap Send to Coach again."

    /** The link was fine, but no block in it is a recipe [RecipeJsonLd] can read. */
    const val NO_RECIPE = "That page has no recipe Coach can read. Copy the recipe text and use " +
        "Paste a recipe instead."

    fun read(link: String, id: String): RecipeLinkReview {
        val decoded = RecipeLink.decode(link) ?: return RecipeLinkReview.Refused(UNREADABLE)
        // The first block that holds a Recipe, not merely the first block: the bookmarklet
        // sends every block mentioning "recipe", and a breadcrumb trail named "Recipes"
        // often comes first.
        val imported = decoded.jsonld.firstNotNullOfOrNull { RecipeJsonLd.recipeFromJson(it) }
            ?: return RecipeLinkReview.Refused(NO_RECIPE)
        return RecipeLinkReview.Ready(recipe(imported, decoded.url, id), imported.servings != null)
    }

    /**
     * The import as a library [Recipe]. Ingredient lines go in raw for the kit's
     * `IngredientParser`, as a pasted or catalogue recipe's do. The page's
     * nutrition, when it has one, is per serving and arrives flagged estimated
     * (the publisher's claim); with none the macros stay unknown, never zero.
     *
     * The page address is the recipe's source, under BACKUP-FORMAT's
     * `sourceUrl`, beside `sourceAuthor`, `prepMinutes` and `cookMinutes` --
     * the catalogue's bag ([Recipe.unknownKeys]), because [Recipe] has no
     * fields for them. It is never fetched.
     */
    internal fun recipe(imported: ImportedRecipe, url: String, id: String): Recipe {
        val extras = JSONObject()
        extras.put("sourceUrl", url)
        imported.author?.takeIf { it.isNotBlank() }?.let { extras.put("sourceAuthor", it) }
        imported.prepMinutes?.let { extras.put("prepMinutes", it) }
        imported.cookMinutes?.let { extras.put("cookMinutes", it) }
        return Recipe(
            id = id,
            name = imported.name,
            servings = imported.servings ?: 1.0,
            rawIngredients = imported.ingredientLines,
            steps = imported.steps,
            nutritionPerServing = imported.nutritionPerServing,
            unknownKeys = extras
        )
    }
}
