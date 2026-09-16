package com.dugcanlift.coach.ui.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.dugcanlift.coach.ui.theme.DclAccent
import com.dugcanlift.coach.ui.theme.DclAccent2
import com.dugcanlift.coach.ui.theme.DclBg
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/** ~11 m at the equator -- keeps a nearly stationary route from a wild zoom. */
private const val MIN_SPAN_DEGREES = 0.0001

/**
 * A client's route as a line on a plain panel, ported from LIFT Android's `RoutePolylineCanvas`
 * (`OutdoorRecordingScreen.kt`). No map SDK and no street tiles, on purpose: a tile server would
 * learn where the client runs, and this app talks to no server about a client at all. Same reason
 * the charts are hand-drawn -- see `LineChart`.
 *
 * [points] are (latitude, longitude). Line and finish dot in the accent, start dot in the second
 * accent, as LIFT and Coach web draw them. Wider than tall by [aspectRatio] (2:1 on the client
 * screen, where a square would fill it).
 */
@Composable
fun RouteCanvas(points: List<Pair<Double, Double>>, modifier: Modifier = Modifier, aspectRatio: Float = 2f) {
    // Captured here: the draw lambda is not composition, and the palette resolves light or dark.
    val lineColor = DclAccent
    val startColor = DclAccent2
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(aspectRatio)
            .clip(RoundedCornerShape(12.dp))
            .background(DclBg)
    ) {
        if (points.size < 2) return@Canvas
        val offsets = projectRoutePoints(points, size.width, size.height)
        val path = Path()
        offsets.forEachIndexed { index, offset ->
            if (index == 0) path.moveTo(offset.x, offset.y) else path.lineTo(offset.x, offset.y)
        }
        drawPath(path = path, color = lineColor, style = Stroke(width = 6f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawCircle(color = startColor, radius = 8f, center = offsets.first())
        drawCircle(color = lineColor, radius = 10f, center = offsets.last())
    }
}

/**
 * LIFT's projection into a [width] x [height] box: equirectangular, longitude scaled by the cosine
 * of the average latitude, one scale for both axes so a route is never stretched, the spare room on
 * the longer side split evenly so it is centred, north up, and 10% padding of the shorter side.
 */
internal fun projectRoutePoints(points: List<Pair<Double, Double>>, width: Float, height: Float): List<Offset> {
    if (points.isEmpty()) return emptyList()
    val lonScale = cos(Math.toRadians(points.map { it.first }.average()))

    val xs = points.map { it.second * lonScale }
    val ys = points.map { it.first }
    val minX = xs.min()
    val maxX = xs.max()
    val minY = ys.min()
    val maxY = ys.max()

    val padding = min(width, height) * 0.1f
    val drawableWidth = width - padding * 2f
    val drawableHeight = height - padding * 2f
    // Degrees per pixel: whichever axis is tighter decides.
    val scale = max(max(maxX - minX, MIN_SPAN_DEGREES) / drawableWidth, max(maxY - minY, MIN_SPAN_DEGREES) / drawableHeight)
    val offsetX = (drawableWidth - (maxX - minX) / scale) / 2
    val offsetY = (drawableHeight - (maxY - minY) / scale) / 2

    return points.mapIndexed { i, (latitude, _) ->
        Offset(
            x = padding + (offsetX + (xs[i] - minX) / scale).toFloat(),
            // Screen y grows downward and latitude grows northward, so flip.
            y = padding + (offsetY + (maxY - latitude) / scale).toFloat()
        )
    }
}
