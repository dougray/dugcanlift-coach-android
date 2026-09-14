package com.dugcanlift.coach.data

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * What one read of the cook library found.
 *
 * [unreadable] is deliberately NOT collapsed into an empty library, for the
 * same reason [PreservedLibraryStore.LibraryCache.Unreadable] is not collapsed
 * into `Absent`: "this coach has written no recipes" and "this coach's recipes
 * are on disk but unreadable" have opposite correct responses. The first may
 * be exported as an empty library; the second must refuse to export at all,
 * because writing that backup is what makes the loss permanent.
 */
data class StoredCookLibrary(
    val recipes: List<Recipe>,
    val meals: List<PlannedMeal>,
    val unreadable: Throwable? = null
) {
    val isUnreadable: Boolean get() = unreadable != null
}

/**
 * The coach's own recipes and the weeks they have planned for clients.
 *
 * One file, not one per recipe as [ClientRepository] does per client: a recipe
 * is small, the whole library is read on every Cook screen, and unlike a
 * client there is no import path that writes a single one in isolation. The
 * atomic write ([writeTextAtomically]) matters for the same reason it does
 * there -- a truncated write here is a coach's whole recipe book.
 *
 * This library is the coach's, and separate from whatever recipes they keep in
 * LIFT for themselves. The browser build already draws that line by storing
 * them under `coach.recipes` rather than `lift.recipes` on a shared origin,
 * and on iOS they are different apps entirely.
 */
class CookRepository(root: File) {

    private val file = File(root.apply { mkdirs() }, FILE_NAME)

    fun load(): StoredCookLibrary {
        if (!file.exists()) return StoredCookLibrary(emptyList(), emptyList())
        return try {
            val root = JSONObject(file.readText())
            StoredCookLibrary(
                recipes = root.optJSONArray("recipes").mapObjects(::recipeFromJson),
                meals = root.optJSONArray("meals").mapObjects(::plannedMealFromJson)
            )
        } catch (t: Throwable) {
            // Not an empty library. See StoredCookLibrary.unreadable.
            StoredCookLibrary(emptyList(), emptyList(), unreadable = t)
        }
    }

    fun save(recipes: List<Recipe>, meals: List<PlannedMeal>) {
        val root = JSONObject()
            .put("recipes", JSONArray(recipes.map { it.toJson() }))
            .put("meals", JSONArray(meals.map { it.toJson() }))
        writeTextAtomically(file, root.toString())
    }

    /** Adds or replaces one recipe, leaving the rest of the library alone. */
    fun upsertRecipe(recipe: Recipe) {
        val current = load()
        if (current.isUnreadable) throw current.unreadable!!
        val recipes = current.recipes.filterNot { it.id == recipe.id } + recipe
        save(recipes, current.meals)
    }

    /**
     * Removes a recipe and every meal planned from it.
     *
     * Meals go too because a planned meal whose recipe is gone contributes
     * nothing to a shopping list and cannot be sent -- [CoachShoppingList.build]
     * already skips it. Leaving it would put a row in a client's week that
     * renders as a blank line forever.
     */
    fun deleteRecipe(id: String) {
        val current = load()
        if (current.isUnreadable) throw current.unreadable!!
        save(
            current.recipes.filterNot { it.id == id },
            current.meals.filterNot { it.recipeId == id }
        )
    }

    fun upsertMeal(meal: PlannedMeal) {
        val current = load()
        if (current.isUnreadable) throw current.unreadable!!
        save(current.recipes, current.meals.filterNot { it.id == meal.id } + meal)
    }

    fun deleteMeal(id: String) {
        val current = load()
        if (current.isUnreadable) throw current.unreadable!!
        save(current.recipes, current.meals.filterNot { it.id == id })
    }

    /** Replaces the whole library, as a restore does. */
    fun replaceAll(recipes: List<Recipe>, meals: List<PlannedMeal>) = save(recipes, meals)

    companion object {
        private const val FILE_NAME = "cook-library.json"
    }
}

/** Meals for one client, oldest day first. A meal owned by nobody belongs to no client's week. */
fun List<PlannedMeal>.forClient(clientId: String): List<PlannedMeal> =
    filter { it.clientId == clientId }.sortedBy { it.dayKey }

private fun <T> JSONArray?.mapObjects(decode: (JSONObject) -> T): List<T> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { i -> (opt(i) as? JSONObject)?.let(decode) }
}
