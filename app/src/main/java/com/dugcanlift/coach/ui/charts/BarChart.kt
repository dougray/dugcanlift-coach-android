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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dugcanlift.coach.ui.theme.DclAccent

/**
 * Single-series bar chart matching [LineChart]'s Canvas approach and palette -- one bar per
 * `label to value` pair in [values], left-to-right in the order given.
 */
@Composable
fun BarChart(
    values: List<Pair<String, Double>>,
    modifier: Modifier = Modifier,
    barColor: Color = DclAccent,
    height: Dp = 160.dp
) {
    val gridColor = MaterialTheme.colorScheme.outline
    val maxValue = values.maxOfOrNull { it.second } ?: 0.0

    Column(modifier = modifier.fillMaxWidth()) {
        if (values.isEmpty() || maxValue <= 0.0) {
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
            val count = values.size

            listOf(0f, 0.5f, 1f).forEach { fraction ->
                val y = h - (h * fraction)
                drawLine(color = gridColor, start = Offset(0f, y), end = Offset(w, y), strokeWidth = 1f)
            }

            val slot = w / count
            val barWidth = slot * 0.6f
            values.forEachIndexed { index, (_, value) ->
                val barHeight = (value / maxValue * h).toFloat().coerceAtLeast(0f)
                val left = slot * index + (slot - barWidth) / 2f
                drawRect(
                    color = barColor,
                    topLeft = Offset(left, h - barHeight),
                    size = Size(barWidth, barHeight)
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = values.first().first,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = values.last().first,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
