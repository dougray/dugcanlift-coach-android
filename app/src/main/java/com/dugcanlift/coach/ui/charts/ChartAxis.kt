package com.dugcanlift.coach.ui.charts

import com.dugcanlift.kit.DayKey
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * What the client charts' axes say, as plain values with no Compose in them, so
 * the rules can be unit tested (this repo has no Compose harness).
 *
 * **The x axis reads as dates.** Every client chart is keyed by a local day key
 * ("2026-10-11"), and the axis used to print the first and last of them raw.
 * Coach iPhone plots dates and lets the system pick a few ticks in the reader's
 * locale ("Sep 27 · Oct 11 · Oct 25"); this is the same thing for a hand-drawn
 * Canvas: a few labels, evenly spaced, as many as fit with room between them.
 *
 * **The bodyweight axis is fitted to the weights** ([fittedBounds]), as LIFT's
 * body weight chart and Coach iPhone's are: from zero, a few pounds over a month
 * draw a flat line.
 *
 * Display only. Nothing here changes a number, a statistic or a stored value:
 * a label is a day key formatted at the last moment, and a bound is where the
 * line is drawn.
 */
internal object ChartAxis {

    /** "Sep 27" in en-US; on a device, `getBestDateTimePattern(locale, DAY_SKELETON)`. */
    const val DAY_SKELETON = "MMMd"

    /** "Sep 27, 2025", for a chart whose days cross a new year. */
    const val DAY_YEAR_SKELETON = "yMMMd"

    /**
     * The skeleton for a chart over [dayKeys]: with the year only when they run
     * across more than one calendar year, where "Jan 3" alone would not say which.
     */
    fun skeleton(dayKeys: List<String>): String {
        val years = dayKeys.mapNotNull { DayKey.parse(it)?.year }.distinct()
        return if (years.size > 1) DAY_YEAR_SKELETON else DAY_SKELETON
    }

    /**
     * A day key as a short date in [locale], from the locale's own [pattern] for
     * [skeleton]. A day key is a local calendar date, parsed as one -- never an
     * instant, so no time zone can move it to the day before. A key that does
     * not parse is printed as it is rather than throwing inside composition
     * (keys are validated where they enter the app, so this is a second line).
     */
    fun dayLabel(dayKey: String, locale: Locale, pattern: String): String =
        DayKey.parse(dayKey)?.let { DateTimeFormatter.ofPattern(pattern, locale).format(it) } ?: dayKey

    /** A label on the axis: which point it names, and where its left edge sits, in pixels. */
    data class Tick(val index: Int, val left: Float)

    /**
     * [count] points spaced as [LineChart] and [MultiLineChart] space them: the
     * first at the left edge, the last at the right, one alone in the middle.
     */
    fun linePositions(count: Int, width: Float): List<Float> = when {
        count <= 0 -> emptyList()
        count == 1 -> listOf(width / 2f)
        else -> List(count) { width * it / (count - 1) }
    }

    /** [count] bars' centres, as [BarChart] lays them out: one equal slot each. */
    fun barPositions(count: Int, width: Float): List<Float> =
        if (count <= 0) emptyList() else List(count) { width * (it + 0.5f) / count }

    /**
     * [k] indices out of [count], evenly spaced, always the first and the last:
     * 5 of 9 is 0, 2, 4, 6, 8.
     */
    fun evenIndices(count: Int, k: Int): List<Int> = when {
        count <= 0 || k <= 0 -> emptyList()
        k == 1 || count == 1 -> listOf(count - 1)
        else -> List(min(k, count)) { i -> (i * (count - 1).toDouble() / (min(k, count) - 1)).roundToInt() }.distinct()
    }

    /**
     * Which points get a label, and where each label goes.
     *
     * The most evenly spaced labels that fit, first and last always among them,
     * each centred under its point and kept inside the chart's edges, with at
     * least [gap] between neighbours. Two that will not fit leave the newest
     * alone; a single point gets its one label.
     *
     * @param positions each point's x, in pixels, from [linePositions] or [barPositions].
     * @param width the chart's width, in pixels.
     * @param labelWidth the widest label's measured width, in pixels.
     * @param gap the least room between two labels, in pixels.
     */
    fun ticks(positions: List<Float>, width: Float, labelWidth: Float, gap: Float): List<Tick> {
        val n = positions.size
        if (n == 0) return emptyList()
        fun place(i: Int) = Tick(i, (positions[i] - labelWidth / 2f).coerceIn(0f, max(0f, width - labelWidth)))
        if (n == 1) return listOf(place(0))
        val room = floor((width + gap) / (labelWidth + gap)).toInt()
        for (k in min(n, room) downTo 2) {
            val ticks = evenIndices(n, k).map(::place)
            if (ticks.zipWithNext().all { (a, b) -> b.left - a.left >= labelWidth + gap }) return ticks
        }
        return listOf(place(n - 1))
    }

    /** Three guides (bottom, middle, top) at round numbers, holding every value. */
    data class FittedBounds(val bottom: Double, val top: Double) {
        val middle: Double get() = (bottom + top) / 2.0
        /** The guides' values, bottom to top, each a whole step from the last. */
        val guides: List<Double> get() = listOf(bottom, middle, top)
    }

    /**
     * Y bounds fitted to [values] rather than drawn from zero: a little room
     * above and below, then widened outward to round numbers so the three guide
     * lines (bottom, middle, top) can be labelled with values a coach reads at a
     * glance -- 170, 180, 190 rather than 173.4, 179.1, 184.8. A flat series
     * sits in the middle. Never below zero. Null for no values.
     */
    fun fittedBounds(values: List<Double>): FittedBounds? {
        val finite = values.filter { it.isFinite() }
        if (finite.isEmpty()) return null
        val lo = finite.min()
        val hi = finite.max()
        // A tenth of the range of room each side, and never less than 1% of the
        // value itself (or half a unit), so a flat line still has an axis.
        val pad = max(max((hi - lo) * 0.1, hi.coerceAtLeast(0.0) * 0.01), 0.5)
        val low = (lo - pad).coerceAtLeast(0.0)
        val high = hi + pad
        var step = niceStep((high - low) / 2.0)
        while (true) {
            val bottom = floor(low / step) * step
            val top = bottom + 2 * step
            // The room above sizes the step; the data alone must fit under the top guide.
            if (top >= hi - 1e-9) return FittedBounds(bottom.cleaned(), top.cleaned())
            step = nextNiceStep(step)
        }
    }

    /** The smallest of 1, 2, 2.5 and 5 times a power of ten that is at least [raw]. */
    private fun niceStep(raw: Double): Double {
        val magnitude = 10.0.pow(floor(log10(raw)))
        return NICE.map { it * magnitude }.firstOrNull { it >= raw - 1e-9 } ?: (10 * magnitude)
    }

    private fun nextNiceStep(step: Double): Double {
        val magnitude = 10.0.pow(floor(log10(step) + 1e-9))
        val i = NICE.indexOfFirst { kotlin.math.abs(it * magnitude - step) < 1e-9 * magnitude }
        return if (i in 0 until NICE.size - 1) NICE[i + 1] * magnitude else 10 * magnitude
    }

    private val NICE = listOf(1.0, 2.0, 2.5, 5.0)

    /** Floating-point dust off a multiple of a step: 170.00000000000003 is 170. */
    private fun Double.cleaned(): Double = (this * 1e6).roundToLong() / 1e6

    /** A guide's value as printed: "180", "177.5". */
    fun guideLabel(value: Double): String {
        val rounded = (value * 10).roundToLong() / 10.0
        return if (rounded == ceil(rounded)) rounded.toLong().toString() else rounded.toString()
    }
}
