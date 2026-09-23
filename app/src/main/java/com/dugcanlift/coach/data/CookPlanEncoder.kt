package com.dugcanlift.coach.data

import com.dugcanlift.kit.RecipeNutrition
import com.dugcanlift.kit.ShareNutrients
import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds the plan-link fragment a coach sends a client, for the Cook half.
 *
 * The shared Kotlin kit ships `PlanLinkCodec.decode` but no encoder: the
 * decoder is the *receiving* side, which LIFT Android uses to read a link.
 * Encoding lived only in Coach iOS (`PlanLinkEncoder.swift`) and the browser
 * until this file. The format is not re-derived here -- it is written to match
 * what that decoder reads, and `CookPlanEncoderTest` proves it by decoding its
 * own output with the shipped decoder rather than with a second copy of these
 * rules.
 *
 * Keys are single letters because the whole payload rides in a URL fragment in
 * an email body. `PLAN-FORMAT.md` in coach-ios is the written contract.
 */
object CookPlanEncoder {

    /** Meal slot indices, matching `PlanMeal.mealSlot`'s default of 2 (dinner). */
    private val SLOTS = listOf("breakfast", "lunch", "dinner", "snack")

    internal fun slot(meal: String): Int =
        SLOTS.indexOf(meal.trim().lowercase()).takeIf { it >= 0 } ?: 2

    /**
     * PLAN-FORMAT's `ux`: `[saturatedFatG, sugarG, sodiumMg]` per serving,
     * trailing nulls trimmed, omitted when none is known. Built by the kit's
     * `ShareNutrients.itemRow` -- the same tuple, rounding (grams to one decimal,
     * sodium to whole milligrams, half-up) and trimming SHARE-FORMAT's `fe` uses
     * -- rather than a second copy of those rules. Only trailing nulls go: a
     * leading null holds its slot, or sugar would slide into saturated fat.
     */
    internal fun uxTuple(n: RecipeNutrition): JSONArray? {
        val row = ShareNutrients.itemRow(n.details) ?: return null
        return JSONArray().also { a -> row.forEach { a.put(it ?: JSONObject.NULL) } }
    }

    /**
     * @param lifterId the client's id. The decoder refuses a fragment whose `l`
     *   is not the reader's own id ([PlanDecodeResult.NotAddressedToYou]), which
     *   is what stops one client opening another's plan.
     * @param roadPicks the Road Food item ids this coach is happy with for this
     *   client ([RoadPicks]). Defaults to none, which writes no `rf` key at
     *   all: a plan with no picks is byte for byte the plan this encoder wrote
     *   before they existed, and every existing caller's link is unchanged.
     *   Cook's Send is the one that carries them -- Train's sends training, and
     *   a pick is food.
     * @return the fragment, without a leading `#`.
     */
    fun encode(
        meals: List<PlannedMeal>,
        recipes: Map<String, Recipe>,
        lifterId: String,
        coachName: String,
        roadPicks: List<String> = emptyList()
    ): String {
        // `x` indexes into `r`, so only recipes actually inlined may be
        // referenced. A meal whose recipe is missing is dropped rather than
        // pointed at whichever recipe happens to sit at that index -- a stale
        // index is the wrong dinner on someone's Tuesday.
        val inlined = meals.mapNotNull { recipes[it.recipeId] }.distinctBy { it.id }
        val indexById = inlined.withIndex().associate { (i, r) -> r.id to i }

        val r = JSONArray()
        inlined.forEach { recipe ->
            val o = JSONObject()
                .put("n", recipe.name)
                .put("s", recipe.servings)
            if (recipe.rawIngredients.isNotEmpty()) o.put("i", JSONArray(recipe.rawIngredients))
            if (recipe.steps.isNotEmpty()) o.put("t", JSONArray(recipe.steps))
            recipe.nutritionPerServing?.let { n ->
                // Fixed-order tuple; the decoder requires at least five entries
                // before it reads any of them. Omitted when no macro was entered
                // -- PLAN-FORMAT: `u` "must never be sent as zeros", and a recipe
                // holding only sodium carries five placeholder zeros (see
                // [hasMacros]). Coach iOS's `PlanLinkEncoder.planRecipe` guards the
                // same all-zero case.
                if (n.hasMacros) {
                    o.put("u", JSONArray(listOf(n.calories, n.proteinG, n.carbsG, n.fatG, n.fiberG)))
                }
                uxTuple(n)?.let { o.put("ux", it) }
            }
            r.put(o)
        }

        val m = JSONArray()
        meals.forEach { meal ->
            val index = indexById[meal.recipeId] ?: return@forEach
            m.put(
                JSONObject()
                    .put("d", meal.dayKey)
                    .put("s", slot(meal.meal))
                    .put("x", index)
                    .put("q", meal.servings)
            )
        }

        val payload = PlanEnvelope.payload(lifterId, coachName)
        // Empty means absent, not `[]` -- PLAN-FORMAT: "a coach who plans only
        // training sends a payload with no `r` or `m` at all."
        if (r.length() > 0) payload.put("r", r)
        if (m.length() > 0) payload.put("m", m)

        // `rf` is a flat list of Road Food item ids, left out entirely when
        // there are none -- never `[]`, the same rule `r` and `m` follow.
        // Nothing is dropped here for being absent from this app's copy of
        // road-food.json: the client's build is the only one that can say what
        // it has, and it skips an id it does not know. PLAN-FORMAT "Road
        // picks"; RoadPicks holds the rule.
        RoadPicks.wire(roadPicks)?.let { payload.put("rf", JSONArray(it)) }

        return PlanEnvelope.fragment(payload)
    }
}
