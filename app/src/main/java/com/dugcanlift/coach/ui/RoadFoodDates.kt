package com.dugcanlift.coach.ui

import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.FormatStyle
import java.util.Locale

/**
 * A Road Food chain's two dates, as one line on its place card:
 * "Published Nov 2022 · checked Sep 23, 2026".
 *
 * Two different facts, both said. `checkedOn` is the day a person read the
 * chain's chart; `publishedOn` is the date the chart **states about itself**,
 * optional and only as precise as the document is -- "2021-03-29" where
 * Whataburger's says "as of March 29, 2021", "2022-11" where Burger King's says
 * only "NOVEMBER 2022", absent where the document states none.
 *
 * A port of Coach iPhone's `RoadFoodDates`, with the formatting taken from LIFT
 * Android's `checkedLabel` / `publishedLabel` (`RoadFoodScreen.kt`) rather than
 * written a third time. The words are Coach iPhone's: "Checked", not LIFT's
 * "Checked on".
 *
 * **Coach does not warn.** LIFT warns from `publishedOn` at six months; no Coach
 * build says anything about staleness, so this line gains a clause in all three
 * or in none.
 *
 * **The locale is the reader's**, as in every LIFT build. The tests pin one only
 * so they can assert a string. A value that is not `YYYY-MM-DD` or `YYYY-MM`
 * reads as no date at all and its clause is left out -- what LIFT iPhone and
 * LIFT Android both do; only LIFT web prints "Invalid Date".
 *
 * A plain value with no Compose in it, so the strings can be tested.
 */
object RoadFoodDates {

    /**
     * The skeleton a month-only document date is printed with. On a device the
     * pattern is `DateFormat.getBestDateTimePattern(locale, MONTH_YEAR_SKELETON)`
     * -- "MMM y" in en-US -- so it reads right wherever the phone is set.
     */
    const val MONTH_YEAR_SKELETON = "yMMM"

    /**
     * The place card's date line, or null when neither date reads.
     *
     * @param monthYearPattern the locale's pattern for [MONTH_YEAR_SKELETON].
     */
    fun line(publishedOn: String?, checkedOn: String?, locale: Locale, monthYearPattern: String): String? {
        val published = documentText(publishedOn, locale, monthYearPattern)
        val checked = dayText(checkedOn, locale)
        return when {
            published != null && checked != null -> "Published $published · checked $checked"
            published != null -> "Published $published"
            checked != null -> "Checked $checked"
            else -> null
        }
    }

    /**
     * "Sep 20, 2026" for a `YYYY-MM-DD`, or null when the text is not one. The
     * day someone read a chart is always a whole day, so this is the strict
     * shape. LIFT Android's `checkedLabel`.
     */
    fun dayText(key: String?, locale: Locale): String? =
        parseDay(key)?.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))

    /**
     * A document's own date, printed no more precisely than the document wrote
     * it: "Mar 29, 2021" for a chart that gives a day, "Nov 2022" for one that
     * names only a month. No day is invented for the reader. LIFT Android's
     * `publishedLabel`.
     */
    fun documentText(key: String?, locale: Locale, monthYearPattern: String): String? {
        dayText(key, locale)?.let { return it }
        val month = parseMonth(key) ?: return null
        return month.format(DateTimeFormatter.ofPattern(monthYearPattern, locale))
    }

    private val dayShape = Regex("""^\d{4}-\d{2}-\d{2}$""")
    private val monthShape = Regex("""^\d{4}-\d{2}$""")

    /** `YYYY-MM-DD`, a real calendar day, or null. A day key is a date, not an instant. */
    fun parseDay(text: String?): LocalDate? {
        val trimmed = text?.trim() ?: return null
        if (!dayShape.matches(trimmed)) return null
        return try {
            LocalDate.parse(trimmed, DateTimeFormatter.ISO_LOCAL_DATE)
        } catch (e: DateTimeParseException) {
            null
        }
    }

    /** `YYYY-MM`, months 01 to 12, or null. */
    fun parseMonth(text: String?): YearMonth? {
        val trimmed = text?.trim() ?: return null
        if (!monthShape.matches(trimmed)) return null
        val month = trimmed.substring(5).toInt()
        if (month !in 1..12) return null
        return YearMonth.of(trimmed.substring(0, 4).toInt(), month)
    }
}
