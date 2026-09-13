package com.dugcanlift.coach.ui.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dugcanlift.coach.ui.theme.DclAccent
import com.dugcanlift.coach.ui.theme.DclMuted

/**
 * The minimum fraction of the peak value ([yAxisBounds]'s `top`) the y-axis must span. Below this,
 * a series that barely varies (e.g. a bodyweight or e1RM line moving by a pound or two around a
 * couple hundred) would otherwise collapse into a hairline pinned to the top edge -- the same
 * value repeated maps to the same fraction-of-max, however close that fraction is to 1.0.
 */
private const val MIN_SPAN_FRACTION = 0.1

/**
 * The y-axis's bottom and top bounds for [LineChart]'s multi-point case. Ordinarily the same
 * `0.0..max(values, goal)` this chart has always used -- returned unchanged whenever the data
 * already spans at least [MIN_SPAN_FRACTION] of that ceiling, so a genuinely varying series (one
 * with real, visible spread) renders exactly as before. When it spans less than that -- a nearly
 * flat series -- the data would otherwise sit pinned to the top edge (every value maps close to
 * the same near-1.0 fraction of the max); instead the bounds are widened to that minimum span,
 * centered on the data's own midpoint, so the (nearly) flat line renders in the middle of the
 * chart instead of hugging one edge. [goal] only ever raises the top, never the bottom, matching
 * how the ceiling was already computed before this fix. Returns `0.0..0.0` for an empty list or a
 * non-positive top (the composable's own empty/zero check bails before this would matter, and the
 * single-point case intentionally never calls this -- see [LineChart]).
 */
internal fun yAxisBounds(values: List<Double>, goal: Double? = null): ClosedFloatingPointRange<Double> {
    val top = (values.maxOrNull() ?: 0.0).coerceAtLeast(goal ?: 0.0)
    if (top <= 0.0) return 0.0..0.0
    val dataMin = values.minOrNull() ?: 0.0
    val naturalSpan = top - dataMin
    val minSpan = top * MIN_SPAN_FRACTION
    if (naturalSpan >= minSpan) return 0.0..top
    val center = (dataMin + top) / 2.0
    val half = minSpan / 2.0
    return (center - half)..(center + half)
}

/**
 * Single-series line chart drawn directly on a Canvas -- same approach and palette as LIFT
 * Android's `LineChart.kt` -- plus an optional [goal] line for targets like a calorie goal.
 *
 * [points] is `label to value` pairs in left-to-right (typically chronological) order. A day or
 * week with nothing logged should simply be left out of [points] by the caller rather than
 * passed as a zero -- omitting a point leaves a gap instead of implying "logged zero".
 */
@Composable
fun LineChart(
    points: List<Pair<String, Double>>,
    modifier: Modifier = Modifier,
    lineColor: Color = DclAccent,
    goal: Double? = null,
    height: Dp = 160.dp
) {
    val gridColor = MaterialTheme.colorScheme.outline
    val values = points.map { it.second }
    val maxValue = (values.maxOrNull() ?: 0.0).coerceAtLeast(goal ?: 0.0)
    // The single-point case keeps its original 0..maxValue scale unchanged (per review: leave
    // single-point rendering as-is) -- only the multi-point series gets the minimum-span floor.
    val bounds = if (points.size > 1) yAxisBounds(values, goal) else 0.0..maxValue
    val bottom = bounds.start
    val top = bounds.endInclusive

    Column(modifier = modifier.fillMaxWidth()) {
        if (points.isEmpty() || maxValue <= 0.0) {
            Text(
                text = "Not enough logged yet to chart.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@Column
        }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
        ) {
            val w = size.width
            val h = size.height
            val count = points.size
            val span = top - bottom

            // Horizontal guides at 0, 50, 100% of the max.
            listOf(0f, 0.5f, 1f).forEach { fraction ->
                val y = h - (h * fraction)
                drawLine(color = gridColor, start = Offset(0f, y), end = Offset(w, y), strokeWidth = 1f)
            }

            goal?.let { g ->
                val y = h - ((g - bottom) / span * h).toFloat()
                drawLine(
                    color = DclMuted,
                    start = Offset(0f, y),
                    end = Offset(w, y),
                    strokeWidth = 3f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))
                )
            }

            if (count == 1) {
                val y = h - ((points[0].second - bottom) / span * h).toFloat()
                drawCircle(color = lineColor, radius = 5f, center = Offset(w / 2f, y))
            } else {
                val stepX = w / (count - 1)
                var previous: Offset? = null
                points.forEachIndexed { index, (_, value) ->
                    val x = stepX * index
                    val y = h - ((value - bottom) / span * h).toFloat()
                    val point = Offset(x, y)
                    previous?.let {
                        drawLine(
                            color = lineColor,
                            start = it,
                            end = point,
                            strokeWidth = 4f,
                            cap = StrokeCap.Round
                        )
                    }
                    drawCircle(color = lineColor, radius = 5f, center = point)
                    previous = point
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = points.first().first,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = points.last().first,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
