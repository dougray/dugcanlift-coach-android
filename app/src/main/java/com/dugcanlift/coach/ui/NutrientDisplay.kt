package com.dugcanlift.coach.ui

import com.dugcanlift.coach.data.DayNutrientTotals
import com.dugcanlift.coach.data.Nutrient
import com.dugcanlift.coach.data.NutrientAverage
import java.util.Locale
import kotlin.math.roundToLong

/**
 * Text for saturated fat, sugar and sodium, kept free of Compose so it can be tested. There are no
 * bars, goals or colours anywhere in it: these are tracked, never targeted (SHARE-FORMAT "Saturated
 * fat, sugar and sodium"). A nutrient nobody recorded produces no line at all rather than a zero or
 * a dash -- a column of dashes for three values most clients never log is noise.
 */

/** Grams to one decimal with a trailing ".0" dropped; milligrams whole, grouped: "21.5 g", "1,840 mg". */
fun formatNutrientAmount(value: Double, nutrient: Nutrient): String =
    if (nutrient.unit == "mg") {
        String.format(Locale.US, "%,d mg", value.roundToLong())
    } else {
        val tenths = (value * 10).roundToLong()
        val text = if (tenths % 10 == 0L) (tenths / 10).toString() else String.format(Locale.US, "%.1f", tenths / 10.0)
        "$text g"
    }

/**
 * One day's line per recorded nutrient: "Sodium 1,840 mg", or "Sodium 1,840 mg · from 3 of 5 foods"
 * when the total covers only some of the day's foods -- a partial total is a floor, not a day, and
 * reading it as a day is exactly the mistake the counts exist to prevent.
 */
fun dayNutrientLines(totals: DayNutrientTotals?): List<String> {
    if (totals == null) return emptyList()
    return Nutrient.entries.mapNotNull { nutrient ->
        val total = nutrient.total(totals) ?: return@mapNotNull null
        val covered = nutrient.covered(totals)
        val base = "${nutrient.label} ${formatNutrientAmount(total, nutrient)}"
        if (covered < totals.foods) "$base · from $covered of ${totals.foods} foods" else base
    }
}

/**
 * "Sodium 2,105 mg a day · 4 days" -- how many days the average is over is always said, because an
 * average of two days and an average of seven read identically otherwise. Partial days are named
 * too: "· 4 days, 1 from only some foods".
 */
fun nutrientAverageLine(average: NutrientAverage): String {
    val days = if (average.days == 1) "1 day" else "${average.days} days"
    val partial = if (average.partialDays > 0) ", ${average.partialDays} from only some foods" else ""
    return "${average.nutrient.label} ${formatNutrientAmount(average.perDay, average.nutrient)} a day · $days$partial"
}
