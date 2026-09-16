package com.dugcanlift.coach.data

/**
 * How a coach prefers to read a recipe's weight. Grams or ounces only -- never a
 * volume, because a cup of oil and a cup of flour are not the same mass, which
 * is the same reason the ingredient parser refuses to cost one.
 *
 * Display only. `Recipe.totalWeightGrams` is always grams.
 *
 * LIFT Android has the identical `ServingUnit` in its own app module, and the
 * factor also lives in LIFT web's `food-amount.js` and LiftCore on iOS. Moving
 * this into `dugcanlift-kit-android` would make it one copy on Android; it is
 * here rather than there only to avoid a kit release for a conversion constant.
 */
enum class RecipeWeightUnit(val label: String, val abbreviation: String) {
    GRAMS("Grams", "g"),
    OUNCES("Ounces", "oz");

    fun fromGrams(grams: Double): Double = if (this == GRAMS) grams else grams / GRAMS_PER_OUNCE
    fun toGrams(value: Double): Double = if (this == GRAMS) value else value * GRAMS_PER_OUNCE

    companion object {
        const val GRAMS_PER_OUNCE = 28.3495

        fun fromKey(key: String?): RecipeWeightUnit = entries.firstOrNull { it.name == key } ?: GRAMS
    }
}
