package com.dugcanlift.coach.ui.charts

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt

/** How a chart lays its points along the x axis; see [ChartAxis.linePositions] / [ChartAxis.barPositions]. */
internal enum class AxisLayout { LINE, BAR }

/**
 * The x axis under a client chart: a few of [dayKeys] as short dates in the
 * reader's locale ("Sep 27", "Oct 11", "Oct 25"), each under its own point,
 * chosen by [ChartAxis.ticks] so they are evenly spaced and never touch.
 *
 * [dayKeys] are the chart's points, left to right, one per point. The width is
 * the chart's own, so a wide, scrolling weekly chart gets more labels than a
 * phone-width one.
 */
@Composable
internal fun DateAxis(dayKeys: List<String>, layout: AxisLayout, modifier: Modifier = Modifier) {
    if (dayKeys.isEmpty()) return
    val locale = LocalLocale.current.platformLocale
    val skeleton = remember(dayKeys) { ChartAxis.skeleton(dayKeys) }
    val pattern = remember(locale, skeleton) {
        android.text.format.DateFormat.getBestDateTimePattern(locale, skeleton)
    }
    val labels = remember(dayKeys, locale, pattern) { dayKeys.map { ChartAxis.dayLabel(it, locale, pattern) } }
    val style = MaterialTheme.typography.bodyMedium
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    val measurer = rememberTextMeasurer()
    // The widest label sets the spacing. Measuring the longest few by length is
    // enough: "Sep 27" and "Sep 28" differ by a digit's width at most.
    val labelWidth = remember(labels, style, measurer) {
        labels.distinct().sortedByDescending { it.length }.take(8)
            .maxOf { measurer.measure(it, style, softWrap = false, maxLines = 1).size.width }
            .toFloat()
    }

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val width = constraints.maxWidth.toFloat()
        val ticks = remember(dayKeys.size, layout, width, labelWidth) {
            val positions = when (layout) {
                AxisLayout.LINE -> ChartAxis.linePositions(dayKeys.size, width)
                AxisLayout.BAR -> ChartAxis.barPositions(dayKeys.size, width)
            }
            // A label's width of room between labels: a few dates, not a fence of them.
            ChartAxis.ticks(positions, width, labelWidth, gap = labelWidth)
        }
        ticks.forEach { tick ->
            Text(
                text = labels[tick.index],
                style = style,
                color = color,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.offset { IntOffset(tick.left.roundToInt(), 0) }
            )
        }
    }
}
