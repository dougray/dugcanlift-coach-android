package com.dugcanlift.coach.ui.adaptive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** The four places the rail leads. A client's page belongs to [ROSTER]. */
enum class TopLevel(val label: String) {
    ROSTER("Roster"), TRAIN("Train"), COOK("Cook"), CONNECT("Connect")
}

/**
 * The rail for medium and expanded windows, replacing the roster's bottom bar of text buttons.
 * A rail rather than a permanent drawer at expanded too: the roster's list and a client's page
 * already share that width, and a 240 dp drawer would take a chart column away to repeat four
 * words the rail already shows.
 */
@Composable
fun CoachNavigationRail(current: TopLevel?, onSelect: (TopLevel) -> Unit, modifier: Modifier = Modifier) {
    NavigationRail(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surface,
        windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Start + WindowInsetsSides.Bottom)
    ) {
        Spacer(Modifier.height(12.dp))
        TopLevel.entries.forEach { destination ->
            NavigationRailItem(
                selected = current == destination,
                onClick = { onSelect(destination) },
                icon = { Icon(railIcon(destination), contentDescription = null) },
                label = { Text(destination.label) }
            )
        }
    }
}

/**
 * Items in rows of [columns] equal-width cells, each row as tall as its tallest cell. A plain
 * `Row` per line rather than `LazyVerticalGrid`, so it can sit inside the screens' existing
 * `LazyColumn`s next to headings and buttons that span the whole width.
 */
@Composable
fun <T> GridRow(cells: List<T>, columns: Int, modifier: Modifier = Modifier, gap: Float = AdaptiveLayout.GRID_GAP_DP,
                cell: @Composable (T) -> Unit) {
    Row(
        modifier = modifier.fillMaxWidth().height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(gap.dp)
    ) {
        cells.forEach { item ->
            Box(Modifier.weight(1f).fillMaxHeight()) { cell(item) }
        }
        repeat(columns - cells.size) { Spacer(Modifier.weight(1f)) }
    }
}

// Drawn here rather than taken from material-icons: four glyphs do not justify a dependency, and
// this app already draws its own charts for the same reason.

private fun railIcon(destination: TopLevel): ImageVector = when (destination) {
    TopLevel.ROSTER -> RosterIcon
    TopLevel.TRAIN -> TrainIcon
    TopLevel.COOK -> CookIcon
    TopLevel.CONNECT -> ConnectIcon
}

private fun icon(name: String, block: ImageVector.Builder.() -> Unit): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply(block).build()

private fun PathBuilder.rect(l: Float, t: Float, r: Float, b: Float) {
    moveTo(l, t); lineTo(r, t); lineTo(r, b); lineTo(l, b); close()
}

private fun PathBuilder.circle(cx: Float, cy: Float, radius: Float) {
    moveTo(cx - radius, cy)
    arcToRelative(radius, radius, 0f, true, true, radius * 2, 0f)
    arcToRelative(radius, radius, 0f, true, true, -radius * 2, 0f)
    close()
}

private val Ink = SolidColor(Color.Black)

/** A list of people: a dot and a line, three times. */
private val RosterIcon = icon("Roster") {
    path(fill = Ink) {
        listOf(6f, 12f, 18f).forEach { y ->
            circle(5f, y, 1.8f)
            rect(9f, y - 1f, 21f, y + 1f)
        }
    }
}

/** A dumbbell. */
private val TrainIcon = icon("Train") {
    path(fill = Ink) {
        rect(2f, 9.5f, 4f, 14.5f)
        rect(4.5f, 6.5f, 8f, 17.5f)
        rect(8f, 11f, 16f, 13f)
        rect(16f, 6.5f, 19.5f, 17.5f)
        rect(20f, 9.5f, 22f, 14.5f)
    }
}

/** A pot with a lid. */
private val CookIcon = icon("Cook") {
    path(fill = Ink) {
        rect(11f, 4.5f, 13f, 6.5f)
        rect(3f, 7.5f, 21f, 9.5f)
        rect(5f, 10.5f, 19f, 19.5f)
        rect(1.5f, 11.5f, 5f, 13f)
        rect(19f, 11.5f, 22.5f, 13f)
    }
}

/** Three joined nodes -- sharing, invites, a backup moving between devices. */
private val ConnectIcon = icon("Connect") {
    path(stroke = Ink, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round) {
        moveTo(6f, 12f); lineTo(18f, 5.5f)
        moveTo(6f, 12f); lineTo(18f, 18.5f)
    }
    path(fill = Ink) {
        circle(6f, 12f, 2.8f)
        circle(18f, 5.5f, 2.8f)
        circle(18f, 18.5f, 2.8f)
    }
}
