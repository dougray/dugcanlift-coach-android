package com.dugcanlift.coach.data

import com.dugcanlift.kit.CompactEncoding
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

    private const val VERSION = 1

    /** Meal slot indices, matching `PlanMeal.mealSlot`'s default of 2 (dinner). */
    private val SLOTS = listOf("breakfast", "lunch", "dinner", "snack")

    internal fun slot(meal: String): Int =
        SLOTS.indexOf(meal.trim().lowercase()).takeIf { it >= 0 } ?: 2

    /**
     * @param lifterId the client's id. The decoder refuses a fragment whose `l`
     *   is not the reader's own id ([PlanDecodeResult.NotAddressedToYou]), which
     *   is what stops one client opening another's plan.
     * @return the fragment, without a leading `#`.
     */
    fun encode(
        meals: List<PlannedMeal>,
        recipes: Map<String, Recipe>,
        lifterId: String,
        coachName: String
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
                // before it reads any of them.
                o.put("u", JSONArray(listOf(n.calories, n.proteinG, n.carbsG, n.fatG, n.fiberG)))
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

        val payload = JSONObject()
            .put("v", VERSION)
            .put("t", "plan")
            .put("l", lifterId)
            .put("n", coachName)
        // Empty means absent, not `[]` -- PLAN-FORMAT: "a coach who plans only
        // training sends a payload with no `r` or `m` at all."
        if (r.length() > 0) payload.put("r", r)
        if (m.length() > 0) payload.put("m", m)

        val json = payload.toString().toByteArray(Charsets.UTF_8)
        return try {
            "1z" + CompactEncoding.base64Url(CompactEncoding.deflateRaw(json))
        } catch (e: Exception) {
            // Uncompressed is a valid encoding, not a failure: the decoder reads
            // `1u` too. A longer link beats no link.
            "1u" + CompactEncoding.base64Url(json)
        }
    }
}
