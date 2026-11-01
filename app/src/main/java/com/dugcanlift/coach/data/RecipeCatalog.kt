package com.dugcanlift.coach.data

import android.content.Context
import com.dugcanlift.kit.RecipeNutrition
import com.dugcanlift.kit.trimZeros
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale
import java.util.UUID

/**
 * The bundled recipe catalogue: 622 recipes a coach can browse and copy into
 * their library, with no connection.
 *
 * A port of Coach iPhone's `RecipeCatalogView` and the kit's
 * `LiftReference/RecipeCatalog.swift`, which read the same recipes out of
 * `recipes.db`. Android cannot open that database, so it bundles
 * `recipes.json` -- the kit's export of it, under the database's own column
 * names -- and pins `recipes.sha256` beside it, exactly as it pins
 * `road-food.json`. Copy both from `dugcanlift-kit/data/` together; never
 * edit either here.
 *
 * **Nothing is re-costed.** The catalogue's macros and its coverage note were
 * decided when the kit built it, by the same weight-only rules this app would
 * apply. A copy takes the figures exactly as the file states them.
 *
 * **A null `servings` is a source that stated no yield.** Those rows carry
 * whole-dish totals instead of per-serving figures, and the coach picks how
 * many the dish serves on import (default 4): per serving is the totals
 * divided by that count, at that moment, and never a guess made for them.
 *
 * **Attribution does not travel on the plan wire**, as on iOS:
 * `CookPlanEncoder` sends name, servings, macros, ingredients and steps, and
 * PLAN-FORMAT has no field for a credit. A CC BY-SA recipe sent to a client
 * therefore arrives uncredited, which that licence asks for on
 * redistribution. Adding one to the library is fine; sending one onward is
 * the open question, and it needs a wire-format decision, not a patch here.
 */
data class CatalogRecipe(
    val id: String,
    val name: String,
    val summary: String? = null,
    /** ISO country code, for the world recipes. Null for the heritage book. */
    val country: String? = null,
    val category: String? = null,
    /** How many the source says it feeds. **Null when the source never said.** */
    val servings: Double? = null,
    val prepMinutes: Int? = null,
    val cookMinutes: Int? = null,
    val caloriesPerServing: Double? = null,
    val proteinPerServing: Double? = null,
    val carbsPerServing: Double? = null,
    val fatPerServing: Double? = null,
    /** The whole finished dish, filled in only when [servings] is null. */
    val caloriesTotal: Double? = null,
    val proteinTotal: Double? = null,
    val carbsTotal: Double? = null,
    val fatTotal: Double? = null,
    val nutritionIsEstimated: Boolean = false,
    /** Plain-English coverage: what could be costed, or why there are no macros. */
    val nutritionNote: String? = null,
    val steps: List<String> = emptyList(),
    val source: String = "",
    val license: String = "",
    val attribution: String = "",
    val sourceUrl: String? = null,
    /** Raw lines in the recipe's own order. Parsed on display and on copy, never stored parsed. */
    val ingredients: List<String> = emptyList()
) {
    /**
     * True when the macros describe the whole dish because the source stated no
     * yield. The coach supplies a serving count on add.
     */
    val needsServings: Boolean get() = servings == null && caloriesTotal != null

    /**
     * True whenever the source stated no yield, with macros or without. The
     * stepper shows for every such recipe, because the count the coach picks
     * becomes the copy's yield either way. A rule all the Coach builds share
     * (2026-10-28). [needsServings] is the narrower question of whether the
     * macros are whole-dish.
     */
    val asksServings: Boolean get() = servings == null

    /**
     * Per serving at [count]. A recipe that published per-serving figures
     * ignores the count: those were measured per serving already, and rescaling
     * them would be a second opinion about a number the publisher measured. A
     * whole-dish recipe divides. Null when the catalogue has no macros for it.
     *
     * Fiber is null, unknown: the catalogue carries none, and it is not zero.
     */
    fun nutrition(count: Double): RecipeNutrition? {
        caloriesPerServing?.let { calories ->
            return RecipeNutrition(
                calories = calories,
                proteinG = proteinPerServing ?: 0.0,
                carbsG = carbsPerServing ?: 0.0,
                fatG = fatPerServing ?: 0.0,
                estimated = nutritionIsEstimated
            )
        }
        val total = caloriesTotal ?: return null
        val divisor = if (count.isFinite() && count > 0) count else 1.0
        return RecipeNutrition(
            calories = total / divisor,
            proteinG = (proteinTotal ?: 0.0) / divisor,
            carbsG = (carbsTotal ?: 0.0) / divisor,
            fatG = (fatTotal ?: 0.0) / divisor,
            estimated = nutritionIsEstimated
        )
    }

    /**
     * This recipe as an ordinary library [Recipe], ready for
     * `CookRepository.upsertRecipe` -- the same save the recipe editor uses.
     *
     * [chosenServings] is used only when the source stated no yield; a stated
     * yield always wins, as on iOS (`entry.recipe.servings ?? servings`).
     * Ingredient lines go in as raw text, and the kit's `IngredientParser`
     * reads them wherever the library reads a recipe, as for a pasted one.
     *
     * The credit and the costing caveat travel with the copy, as iOS's
     * `makeRecipe` keeps them on its model. Coach Android's [Recipe] has no
     * field for either, so they ride in [Recipe.unknownKeys] -- the bag a
     * recipe's unmodelled fields already round-trip in -- under BACKUP-FORMAT's
     * own recipe names (`sourceUrl`, `sourceAuthor`, `sourceTranscript`,
     * `prepMinutes`, `cookMinutes`). `estimated` carries over as iOS's
     * `nutritionIsEstimated` does.
     */
    fun makeRecipe(chosenServings: Double, id: String = UUID.randomUUID().toString()): Recipe {
        val count = servings ?: chosenServings
        val extras = JSONObject()
        sourceUrl?.let { extras.put("sourceUrl", it) }
        if (attribution.isNotEmpty()) extras.put("sourceAuthor", attribution)
        val transcript = listOfNotNull(
            attribution.takeIf { it.isNotEmpty() },
            license.takeIf { it.isNotEmpty() },
            nutritionNote
        ).joinToString("\n")
        if (transcript.isNotEmpty()) extras.put("sourceTranscript", transcript)
        prepMinutes?.let { extras.put("prepMinutes", it) }
        cookMinutes?.let { extras.put("cookMinutes", it) }
        return Recipe(
            id = id,
            name = name,
            servings = count,
            rawIngredients = ingredients,
            steps = steps,
            nutritionPerServing = nutrition(count),
            unknownKeys = extras.takeIf { it.length() > 0 }
        )
    }
}

/** Where the bundled copy lives. Copy it from `dugcanlift-kit/data/`, never edit it here. */
const val RECIPE_CATALOGUE_ASSET = "recipes.json"

/**
 * Reads `recipes.json`. Lenient per row, as the other bundled readers are: a
 * row without an id or a name is dropped, a non-number is unknown, never zero.
 * Throws only when the file is not a catalogue at all (no `recipes`).
 */
fun parseRecipeCatalog(json: String): List<CatalogRecipe> {
    val root = JSONObject(json)
    val rows = root.optJSONArray("recipes") ?: throw IllegalArgumentException("not a recipe catalogue")
    return (0 until rows.length()).mapNotNull { i ->
        val o = rows.opt(i) as? JSONObject ?: return@mapNotNull null
        val id = o.catalogText("id") ?: return@mapNotNull null
        val name = o.catalogText("name") ?: return@mapNotNull null
        CatalogRecipe(
            id = id,
            name = name,
            summary = o.catalogText("summary"),
            country = o.catalogText("country"),
            category = o.catalogText("category"),
            servings = o.catalogNumber("servings")?.takeIf { it > 0 },
            prepMinutes = o.catalogNumber("prepMinutes")?.toInt(),
            cookMinutes = o.catalogNumber("cookMinutes")?.toInt(),
            caloriesPerServing = o.catalogNumber("caloriesPerServing"),
            proteinPerServing = o.catalogNumber("proteinPerServing"),
            carbsPerServing = o.catalogNumber("carbsPerServing"),
            fatPerServing = o.catalogNumber("fatPerServing"),
            caloriesTotal = o.catalogNumber("caloriesTotal"),
            proteinTotal = o.catalogNumber("proteinTotal"),
            carbsTotal = o.catalogNumber("carbsTotal"),
            fatTotal = o.catalogNumber("fatTotal"),
            nutritionIsEstimated = o.optBoolean("nutritionIsEstimated", false),
            nutritionNote = o.catalogText("nutritionNote"),
            steps = o.optJSONArray("steps").catalogLines(),
            source = o.catalogText("source").orEmpty(),
            license = o.catalogText("license").orEmpty(),
            attribution = o.catalogText("attribution").orEmpty(),
            sourceUrl = o.catalogText("sourceURL"),
            ingredients = o.optJSONArray("ingredients").catalogLines()
        )
    }
}

private fun JSONObject.catalogText(key: String): String? =
    (opt(key) as? String)?.trim()?.takeIf { it.isNotEmpty() }

private fun JSONObject.catalogNumber(key: String): Double? =
    (opt(key) as? Number)?.toDouble()?.takeIf { it.isFinite() }

private fun JSONArray?.catalogLines(): List<String> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { (opt(it) as? String)?.takeIf { s -> s.isNotBlank() } }
}

/**
 * The catalogue's rules, with no Compose in them: what the list shows before
 * anyone types, what a search finds, and the words on a row.
 */
class RecipeCatalog(val recipes: List<CatalogRecipe>) {

    /**
     * The words a search can match, per recipe: its name, summary and country,
     * the three columns iOS's `recipes_fts` indexes. Category is not among
     * them, on either build.
     */
    private val index: List<Pair<CatalogRecipe, Words>> = recipes.map { r ->
        r to Words(name = words(r.name), rest = words(listOfNotNull(r.summary, r.country).joinToString(" ")))
    }

    private class Words(val name: List<String>, val rest: List<String>)

    /**
     * The opening page: **every** recipe, by name. A rule all the Coach builds
     * share (2026-10-28), replacing the first 60 that iOS's `browseRecipes()`
     * returned, which left most of the catalogue reachable only by search. The
     * list that shows it is lazy, so 622 rows cost nothing until they scroll
     * into view. Ordered by name, not by source, so the two collections
     * interleave rather than the heritage book reading as an afterthought.
     * Plain code-unit order, as SQLite's `ORDER BY name` is.
     */
    private val byName: List<CatalogRecipe> = recipes.sortedBy { it.name }

    fun browse(): List<CatalogRecipe> = byName

    /**
     * Every word of [text] must begin some word of a recipe's name, summary or
     * country -- iOS's FTS5 all-prefixes match. Case and accents are ignored,
     * as `unicode61` ignores them. Fewer than [MIN_QUERY] characters finds
     * nothing.
     *
     * Ranked by how many of the words hit the name, then by the shorter name,
     * so a short near-exact match beats a long one that merely contains the
     * word -- the order bm25-then-length gives on iOS for searches like these.
     */
    fun search(text: String, limit: Int = SEARCH_LIMIT): List<CatalogRecipe> {
        val trimmed = text.trim()
        if (trimmed.length < MIN_QUERY) return emptyList()
        val terms = words(trimmed)
        if (terms.isEmpty()) return emptyList()
        return index.mapNotNull { (recipe, w) ->
            var inName = 0
            for (term in terms) {
                when {
                    w.name.any { it.startsWith(term) } -> inName++
                    w.rest.any { it.startsWith(term) } -> Unit
                    else -> return@mapNotNull null
                }
            }
            recipe to inName
        }
            .sortedWith(compareByDescending<Pair<CatalogRecipe, Int>> { it.second }
                .thenBy { it.first.name.length }
                .thenBy { it.first.name })
            .take(limit)
            .map { it.first }
    }

    /**
     * Every distinct credit, "attribution — license", for the list's Credits.
     * CC BY-SA requires the credit be given, so it sits on the screen that
     * shows the data rather than in a settings page.
     */
    fun credits(): List<String> =
        recipes.map { "${it.attribution} — ${it.license}" }.distinct().sorted()

    companion object {
        const val SEARCH_LIMIT = 40
        const val MIN_QUERY = 2
        const val DEFAULT_SERVINGS = 4.0
        const val MIN_SERVINGS = 1.0
        const val MAX_SERVINGS = 48.0

        private val COMBINING = Regex("\\p{Mn}+")
        private val SEPARATORS = Regex("[^\\p{L}\\p{N}]+")

        /** Lower case, accents off, split on anything that is not a letter or digit. */
        internal fun words(text: String): List<String> =
            Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
                .replace(COMBINING, "")
                .split(SEPARATORS)
                .filter { it.isNotEmpty() }

        /** iOS's `CookFormat.servingsLabel`. */
        fun servingsLabel(value: Double): String =
            if (value == 1.0) "1 serving" else "${value.trimZeros()} servings"

        /**
         * The second line of a list row. Said in the list, not only the detail,
         * so a browsing eye never reads a whole-pot figure as a portion.
         */
        fun rowSummary(recipe: CatalogRecipe): String {
            val macros = when {
                recipe.caloriesPerServing != null -> "${whole(recipe.caloriesPerServing)} kcal per serving"
                recipe.needsServings -> "Macros for the whole dish"
                else -> "No macros"
            }
            return "$macros · ${recipe.ingredients.size} ingredients"
        }

        /**
         * A macro as a whole number, for display: **rounded half away from
         * zero, never truncated**, so 224.525 kcal reads 225. A rule all the
         * Coach builds share (2026-10-28). Display only: the stored value stays
         * the raw quotient. Not `kotlin.math.round`, which rounds half to even.
         */
        fun whole(value: Double): Long =
            if (value < 0) -Math.floor(-value + 0.5).toLong() else Math.floor(value + 0.5).toLong()

        /** "690 kcal · P 32 · C 62 · F 34", each figure through [whole]. */
        fun macroLine(facts: RecipeNutrition): String =
            "${whole(facts.calories)} kcal · P ${whole(facts.proteinG)} · " +
                "C ${whole(facts.carbsG)} · F ${whole(facts.fatG)}"

        /** What the Macros section says the figure is per. */
        fun macroBasis(recipe: CatalogRecipe, servings: Double): String =
            if (recipe.needsServings) "Per serving at ${servingsLabel(servings)}."
            else "Per serving, as published."

        /**
         * The line under the stepper. iOS's sentence where the macros are
         * whole-dish; for a no-yield recipe with no macros at all, the same
         * sentence without the clause about macros it does not have.
         */
        fun noYieldNote(recipe: CatalogRecipe): String =
            if (recipe.needsServings)
                "This book states no yield, so the macros are for the whole dish. " +
                    "Set how many it serves before sending it to anyone."
            else "This book states no yield. Set how many it serves before sending it to anyone."

        /** The count a recipe opens at: its own yield, or the default for the coach to change. */
        fun openingServings(recipe: CatalogRecipe): Double = recipe.servings ?: DEFAULT_SERVINGS
    }
}

/**
 * The bundled catalogue, parsed once for the life of the process. Read the
 * first time the catalogue opens rather than at launch, the way
 * [RoadFoodStore] reads its file, and a failure is remembered as well as a
 * success. Offline by construction: it is an app asset.
 */
object RecipeCatalogStore {

    @Volatile private var cached: RecipeCatalog? = null
    @Volatile private var failure: String? = null

    /** The catalogue, or null with [lastError] set when it could not load. */
    suspend fun load(context: Context): RecipeCatalog? = withContext(Dispatchers.IO) {
        cached?.let { return@withContext it }
        failure?.let { return@withContext null }
        try {
            val json = context.applicationContext.assets
                .open(RECIPE_CATALOGUE_ASSET)
                .bufferedReader()
                .use { it.readText() }
            val parsed = parseRecipeCatalog(json)
            if (parsed.isEmpty()) {
                failure = "the file is empty"
                null
            } else {
                RecipeCatalog(parsed).also { cached = it }
            }
        } catch (e: Exception) {
            failure = e.message ?: e.javaClass.simpleName
            null
        }
    }

    val lastError: String? get() = failure
}
