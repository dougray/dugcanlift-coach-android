package com.dugcanlift.coach.data

import kotlin.math.abs

/**
 * Cook → Plan's week: which meals sit on which day and slot, and what a Send
 * and the shopping list read.
 *
 * Booking used to be "today or nowhere" here, as it once was in Train: every
 * "Add to the week" built a [PlannedMeal] on `DayKey.today()`, as dinner, for
 * one serving, so a coach could not plan Tuesday's lunch or two servings of
 * anything. This is the same fix Train got ([PlanWeek] plus a per-day book
 * menu), with Coach iOS's `CookPlanView` grid of day x meal slot and its
 * servings menu.
 *
 * A plain value with no Compose in it, for the reason [TrainPlanSend] is one:
 * there is no Compose harness here, and what a coach ships is not a thing to
 * leave untested in a lambda.
 */
object CookPlanWeek {

    /** PLAN-FORMAT's `m.s` order: 0 breakfast, 1 lunch, 2 dinner, 3 snack. */
    val SLOTS: List<String> = listOf("breakfast", "lunch", "dinner", "snack")

    /** Coach iOS's `CookPlanView.servingOptions`, so both apps offer the same menu. */
    val SERVING_OPTIONS: List<Double> = listOf(0.5, 1.0, 1.5, 2.0, 3.0, 4.0)

    fun slotLabel(slot: String): String = slot.replaceFirstChar { it.uppercase() }

    /** The slot a stored meal belongs to; an unknown name reads as dinner, as the encoder does. */
    fun slotOf(meal: PlannedMeal): String = SLOTS[CookPlanEncoder.slot(meal.meal)]

    /** [meals] that fall in [week]: what the Send carries and the shopping list adds up. */
    fun inWeek(meals: List<PlannedMeal>, week: PlanWeek): List<PlannedMeal> {
        val days = week.days.toSet()
        return meals.filter { it.dayKey in days }
    }

    /** The meals booked on [day] at [slot]. */
    fun at(meals: List<PlannedMeal>, day: String, slot: String): List<PlannedMeal> =
        meals.filter { it.dayKey == day && slotOf(it) == slot }

    /**
     * A new booking: [recipe] on [day] at [slot] for [clientId], with the
     * recipe's name and per-serving nutrition snapshotted so a later edit to the
     * recipe does not rewrite a week already sent.
     */
    fun book(
        recipe: Recipe,
        clientId: String,
        day: String,
        slot: String,
        servings: Double = 1.0
    ): PlannedMeal {
        require(slot in SLOTS) { "Unknown meal slot: $slot" }
        return PlannedMeal(
            recipeId = recipe.id,
            clientId = clientId,
            dayKey = day,
            meal = slot,
            servings = servings,
            recipeName = recipe.name,
            snapshotNutrition = recipe.nutritionPerServing,
            snapshotNutritionUnknownKeys = recipe.nutritionUnknownKeys
        )
    }

    /** "1 serving", "1.5 servings". */
    fun servingsLabel(servings: Double): String {
        val number = if (abs(servings - Math.rint(servings)) < 1e-9) servings.toLong().toString()
        else servings.toString()
        return "$number serving${if (servings == 1.0) "" else "s"}"
    }
}
