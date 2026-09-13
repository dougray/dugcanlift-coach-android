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
    val maxValue = (points.maxOfOrNull { it.second } ?: 0.0).coerceAtLeast(goal ?: 0.0)

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

            // Horizontal guides at 0, 50, 100% of the max.
            listOf(0f, 0.5f, 1f).forEach { fraction ->
                val y = h - (h * fraction)
                drawLine(color = gridColor, start = Offset(0f, y), end = Offset(w, y), strokeWidth = 1f)
            }

            goal?.let { g ->
                val y = h - (g / maxValue * h).toFloat()
                drawLine(
                    color = DclMuted,
                    start = Offset(0f, y),
                    end = Offset(w, y),
                    strokeWidth = 3f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))
                )
            }

            if (count == 1) {
                val y = h - (points[0].second / maxValue * h).toFloat()
                drawCircle(color = lineColor, radius = 5f, center = Offset(w / 2f, y))
            } else {
                val stepX = w / (count - 1)
                var previous: Offset? = null
                points.forEachIndexed { index, (_, value) ->
                    val x = stepX * index
                    val y = h - (value / maxValue * h).toFloat()
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
