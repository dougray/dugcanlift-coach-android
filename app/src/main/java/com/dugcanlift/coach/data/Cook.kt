package com.dugcanlift.coach.data

import com.dugcanlift.kit.IngredientParser
import com.dugcanlift.kit.RecipeIngredient
import com.dugcanlift.kit.RecipeNutrition
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * COOK for Coach -- the recipes a coach writes, the week they build for a
 * client, and the shopping list derived from it.
 *
 * Ported from LIFT Android's `data/Recipe.kt` rather than written fresh: the
 * two apps must agree on what a recipe is, because a coach's week ends up in a
 * client's LIFT via `PlanLinkCodec`. The differences are deliberate and small
 * -- a planned meal here belongs to a *client*, and none of LIFT's
 * log-to-my-own-diary machinery (`toFoodEntry`, `amountGrams`,
 * `loggedFoodEntryId`) exists, because a coach plans food they do not eat.
 *
 * ## Two spellings of the same recipe
 *
 * Coach iOS and Coach web write different backup files, and both decode here
 * because `BackupCodec.restore` only requires a `clients` array, which both
 * carry:
 *
 * ```
 * iOS:  "ingredients": [ "2 cups flour" ]
 * web:  "ingredients": [ { "rawText": "2 cups flour" } ]
 * ```
 *
 * Until this file existed, neither shape mattered: the whole library was
 * opaque cargo that `BackupCodec` carried byte-for-byte without looking
 * inside. Modelling recipes is what makes the difference load-bearing, so
 * [recipeFromJson] accepts both. Reading only the iOS spelling would silently
 * empty every ingredient list in a web coach's library on first restore --
 * the same class of loss the opaque-cargo design exists to prevent, reached
 * one level further down.
 *
 * ## Keys this app does not model
 *
 * [Recipe.unknownKeys] and [PlannedMeal.unknownKeys] hold every field the file
 * carried that is not decoded above, written back out untouched by
 * [Recipe.toJson]. `BackupCodec` already does this for whole top-level keys
 * (`ENVELOPE_KEYS`, preservation by exclusion rather than enumeration); this
 * is the same rule inside the object, so a field a newer Coach iOS adds
 * survives a round trip through this app rather than being dropped on the
 * floor by the next Save Backup.
 */

data class Recipe(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val servings: Double = 1.0,
    /** Verbatim ingredient lines. The source of truth -- [parsedIngredients]
     *  derives from these, never the other way round, because the parser is
     *  lossy by design and a re-save must not launder "a pinch" into null. */
    val rawIngredients: List<String> = emptyList(),
    val steps: List<String> = emptyList(),
    /** Per serving. Null means unknown -- not zero. */
    val nutritionPerServing: RecipeNutrition? = null,
    /** The whole finished dish, in grams. Null until someone weighs it; a recipe
     *  without it plans by servings exactly as before. Canonical grams whatever
     *  unit the coach typed -- a converted value never reaches the model. Same
     *  field and spelling as `Recipe.totalWeightGrams` in LiftCore, so it round
     *  trips through iOS and web backups unchanged. */
    val totalWeightGrams: Double? = null,
    /** Keys inside `nutritionPerServing` that [RecipeNutrition] has no field for.
     *  Kept separately for the same reason [unknownKeys] is: the rule is
     *  preservation by exclusion, and a nested object is not an exception to it.
     *  `saturatedFatG`, `sugarG` and `sodiumMg` used to land here; they are real
     *  fields now, and a file that parked them here is promoted on read -- see
     *  [nutritionFromJson]. */
    val nutritionUnknownKeys: JSONObject? = null,
    /** Fields this app has no model for, preserved for the round trip. */
    val unknownKeys: JSONObject? = null
) {
    /** Servings can never be zero; a shopping list divides by it. */
    val safeServings: Double get() = if (servings > 0) servings else 1.0

    val parsedIngredients: List<RecipeIngredient>
        get() = rawIngredients.map { IngredientParser.parse(it) }

    val totalNutrition: RecipeNutrition? get() = nutritionPerServing?.scaled(servings)

    /** Grams in one serving, when the dish has been weighed. What a client puts
     *  on a scale; a count of servings never tells them that. */
    val gramsPerServing: Double? get() = totalWeightGrams?.let { it / safeServings }
}

/**
 * Whether any of the five macros was entered. A [RecipeNutrition] can exist
 * with none of them -- a coach who knows a dish's sodium but not its calories --
 * and then carries zeros in the five non-null macro fields, the same shape Coach
 * iOS's `NutritionFacts` holds. Those zeros are "not entered", never a measured
 * zero-calorie dish: PLAN-FORMAT omits `u` for them, and no screen shows them.
 */
val RecipeNutrition.hasMacros: Boolean
    get() = listOf(calories, proteinG, carbsG, fatG, fiberG).any { it != 0.0 }

/**
 * A recipe placed on a day, for one client. Not something eaten -- the client
 * logs that themselves in LIFT, from the plan link this becomes.
 *
 * `clientId` is nullable because Coach iOS's own file allows it: its
 * `PlannedMeal` carries no client field, and ownership is recorded separately
 * (see coach-ios `BackupCodec.BackupMeal.clientID`). A meal that arrives
 * without one belongs to nobody and is shown in no client's week, rather than
 * being silently attached to whoever is on screen.
 */
data class PlannedMeal(
    val id: String = UUID.randomUUID().toString(),
    val recipeId: String,
    val clientId: String? = null,
    /** "yyyy-MM-dd". */
    val dayKey: String,
    val meal: String = "dinner",
    val servings: Double = 1.0,
    /** Snapshot, per serving, taken when the meal was planned. A later edit to
     *  the recipe must not rewrite a week already sent to a client. */
    val recipeName: String = "",
    val snapshotNutrition: RecipeNutrition? = null,
    /** See [Recipe.nutritionUnknownKeys]. */
    val snapshotNutritionUnknownKeys: JSONObject? = null,
    val unknownKeys: JSONObject? = null
)

/** One line of a shopping list: an ingredient, and how much of it across the week. */
data class ShoppingLine(
    val name: String,
    /** Keyed by unit, because "2 cups flour" and "100 g flour" cannot be added. */
    val amounts: Map<String, Double>,
    /** Lines the parser could not read a quantity from, kept so they still appear. */
    val unquantified: Int = 0
)

object CoachShoppingList {

    /**
     * Ingredients for [meals], scaled by servings and summed per unit.
     *
     * Scaling is `meal.servings / recipe.safeServings`: a recipe that serves 4,
     * planned as 2 servings, contributes half its ingredients. A meal whose
     * recipe is missing contributes nothing rather than throwing -- a deleted
     * recipe must not make the whole list un-renderable.
     */
    fun build(meals: List<PlannedMeal>, recipes: Map<String, Recipe>): List<ShoppingLine> {
        val byName = LinkedHashMap<String, MutableMap<String, Double>>()
        val unreadable = LinkedHashMap<String, Int>()

        for (meal in meals) {
            val recipe = recipes[meal.recipeId] ?: continue
            val factor = meal.servings / recipe.safeServings
            for (ingredient in recipe.parsedIngredients) {
                // `item` is the parsed name; when the parse failed it is null and
                // the raw line is all there is. Keyed lowercase so "Flour" and
                // "flour" are one line, which is the point of a shopping list.
                val name = (ingredient.item ?: ingredient.rawText).trim().lowercase()
                if (name.isEmpty()) continue
                val quantity = ingredient.qty
                if (quantity == null) {
                    unreadable[name] = (unreadable[name] ?: 0) + 1
                    byName.getOrPut(name) { linkedMapOf() }
                    continue
                }
                val unit = ingredient.unit.orEmpty()
                val bucket = byName.getOrPut(name) { linkedMapOf() }
                bucket[unit] = (bucket[unit] ?: 0.0) + quantity * factor
            }
        }

        return byName.map { (name, amounts) ->
            ShoppingLine(name = name, amounts = amounts, unquantified = unreadable[name] ?: 0)
        }
    }
}

/* ---------------- JSON ---------------- */

private val RECIPE_KEYS = setOf(
    "id", "name", "servings", "ingredients", "steps", "nutritionPerServing", "totalWeightGrams"
)
private val MEAL_KEYS = setOf(
    "id", "recipeID", "recipeId", "recipeName", "dayKey", "date", "meal",
    "servings", "snapshotNutrition", "clientID", "clientId"
)

/** Every key not in [known], or null when there are none. */
private fun JSONObject.keysOutside(known: Set<String>): JSONObject? {
    var extra: JSONObject? = null
    for (key in keys()) {
        if (key in known) continue
        val target = extra ?: JSONObject().also { extra = it }
        target.put(key, get(key))
    }
    return extra
}

private fun JSONObject.copyInto(target: JSONObject) {
    for (key in keys()) target.put(key, get(key))
}

/** The fields [RecipeNutrition] models. Anything else is preserved, not dropped. */
private val NUTRITION_KEYS = setOf("calories", "proteinG", "carbsG", "fatG", "fiberG", "estimated")

/**
 * Saturated fat, sugar and sodium, per serving (BACKUP-FORMAT `nutritionPerServing`).
 * Modelled only when the value is a finite number: anything else -- `"540 mg"`
 * from a hand-edited file -- stays in the unknown bag, so it is carried rather
 * than read as unknown and then written away.
 */
private val DETAIL_KEYS = listOf("saturatedFatG", "sugarG", "sodiumMg")

/** Every key [nutritionFromJson] turned into a field, for this object. */
private fun JSONObject.modelledNutritionKeys(): Set<String> =
    NUTRITION_KEYS + DETAIL_KEYS.filter { optFiniteOrNull(it) != null }

/**
 * Promotion rather than a migration step: the stored file is still the source,
 * and a value an earlier build parked in `nutritionUnknownKeys` was written back
 * at the same place under the same name. Reading it as a field here is all it
 * takes, and the next save writes it from the field.
 */
private fun nutritionFromJson(o: JSONObject?): RecipeNutrition? {
    if (o == null) return null
    return RecipeNutrition(
        calories = o.optDouble("calories", 0.0),
        proteinG = o.optDouble("proteinG", 0.0),
        carbsG = o.optDouble("carbsG", 0.0),
        fatG = o.optDouble("fatG", 0.0),
        fiberG = o.optDouble("fiberG", 0.0),
        // Whether these macros were an LLM's guess rather than a database
        // lookup. Round-tripped rather than defaulted: dropping it turns an
        // estimate into a stated fact on the next Save Backup.
        estimated = o.optBoolean("estimated", false),
        saturatedFatG = o.optFiniteOrNull("saturatedFatG"),
        sugarG = o.optFiniteOrNull("sugarG"),
        sodiumMg = o.optFiniteOrNull("sodiumMg")
    )
}

private fun RecipeNutrition.toJson(extras: JSONObject?): JSONObject {
    val o = JSONObject()
    extras?.copyInto(o)
    o.put("calories", calories)
    o.put("proteinG", proteinG)
    o.put("carbsG", carbsG)
    o.put("fatG", fatG)
    o.put("fiberG", fiberG)
    // Only when true. Coach iOS's NutritionFacts has no such field, so writing
    // `"estimated": false` would inject a key into every recipe of a file that
    // never had one -- harmless to iOS's decoder, which ignores what it does not
    // model, but it makes a round trip stop being a no-op and that is worth
    // more than stating a default.
    if (estimated) o.put("estimated", true)
    // Only when known. Written after the extras, so a field wins over any stale
    // copy of the same key; absent is null, never zero.
    saturatedFatG?.let { o.put("saturatedFatG", it) }
    sugarG?.let { o.put("sugarG", it) }
    sodiumMg?.let { o.put("sodiumMg", it) }
    return o
}

/**
 * Accepts both ingredient spellings -- see this file's header. A bare string is
 * iOS's; an object with `rawText` is the web's. Anything else is skipped rather
 * than rendered as "null", which is what `JSONArray.optString` would give.
 */
internal fun rawIngredientsFromJson(array: JSONArray?): List<String> {
    if (array == null) return emptyList()
    val out = ArrayList<String>(array.length())
    for (i in 0 until array.length()) {
        when (val element = array.opt(i)) {
            is String -> out += element
            is JSONObject -> element.optStringOrNull("rawText")?.let { out += it }
            else -> Unit
        }
    }
    return out
}

private fun stringsFromJson(array: JSONArray?): List<String> {
    if (array == null) return emptyList()
    return (0 until array.length()).mapNotNull { array.opt(it) as? String }
}

fun recipeFromJson(o: JSONObject): Recipe = Recipe(
    id = o.optStringOrNull("id") ?: UUID.randomUUID().toString(),
    name = o.optStringOrNull("name").orEmpty(),
    servings = o.optDouble("servings", 1.0),
    rawIngredients = rawIngredientsFromJson(o.optJSONArray("ingredients")),
    steps = stringsFromJson(o.optJSONArray("steps")),
    nutritionPerServing = nutritionFromJson(o.optJSONObject("nutritionPerServing")),
    // Zero, negative or non-numeric is "not weighed", never a dish that weighs
    // nothing -- a per-serving weight divides by nothing sensible otherwise.
    totalWeightGrams = o.optDouble("totalWeightGrams", Double.NaN)
        .takeIf { it.isFinite() && it > 0 },
    nutritionUnknownKeys = o.optJSONObject("nutritionPerServing")?.let { it.keysOutside(it.modelledNutritionKeys()) },
    unknownKeys = o.keysOutside(RECIPE_KEYS)
)

/**
 * Written in Coach iOS's spelling -- bare strings for ingredients -- because
 * that is the file this app produces and the one `BackupCodec` mirrors. A
 * library read from a web export is therefore normalised to the iOS spelling
 * on the way out, which loses nothing: `rawText` was the only field the web's
 * ingredient objects carried.
 */
fun Recipe.toJson(): JSONObject {
    val o = JSONObject()
    unknownKeys?.copyInto(o)
    o.put("id", id)
    o.put("name", name)
    o.put("servings", servings)
    o.put("ingredients", JSONArray(rawIngredients))
    o.put("steps", JSONArray(steps))
    nutritionPerServing?.let { o.put("nutritionPerServing", it.toJson(nutritionUnknownKeys)) }
    totalWeightGrams?.let { o.put("totalWeightGrams", it) }
    return o
}

fun plannedMealFromJson(o: JSONObject): PlannedMeal = PlannedMeal(
    id = o.optStringOrNull("id") ?: UUID.randomUUID().toString(),
    // iOS spells it recipeID, the web recipeId.
    recipeId = o.optStringOrNull("recipeID") ?: o.optStringOrNull("recipeId").orEmpty(),
    clientId = o.optStringOrNull("clientID") ?: o.optStringOrNull("clientId"),
    dayKey = o.optStringOrNull("dayKey") ?: o.optStringOrNull("date").orEmpty(),
    meal = o.optStringOrNull("meal") ?: "dinner",
    servings = o.optDouble("servings", 1.0),
    recipeName = o.optStringOrNull("recipeName").orEmpty(),
    snapshotNutrition = nutritionFromJson(o.optJSONObject("snapshotNutrition")),
    snapshotNutritionUnknownKeys = o.optJSONObject("snapshotNutrition")?.let { it.keysOutside(it.modelledNutritionKeys()) },
    unknownKeys = o.keysOutside(MEAL_KEYS)
)

fun PlannedMeal.toJson(): JSONObject {
    val o = JSONObject()
    unknownKeys?.copyInto(o)
    o.put("id", id)
    o.put("recipeID", recipeId)
    o.put("recipeName", recipeName)
    o.put("dayKey", dayKey)
    o.put("meal", meal)
    o.put("servings", servings)
    clientId?.let { o.put("clientID", it) }
    snapshotNutrition?.let { o.put("snapshotNutrition", it.toJson(snapshotNutritionUnknownKeys)) }
    return o
}
