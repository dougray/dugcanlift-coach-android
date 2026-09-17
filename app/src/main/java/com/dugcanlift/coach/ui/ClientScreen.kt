package com.dugcanlift.coach.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.Dp
import com.dugcanlift.coach.ui.adaptive.AdaptiveLayout
import com.dugcanlift.coach.ui.adaptive.rowMajor
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dugcanlift.coach.data.Client
import com.dugcanlift.coach.data.ClientRepository
import com.dugcanlift.coach.data.ExerciseSet
import com.dugcanlift.coach.data.LastRoute
import com.dugcanlift.coach.data.OutdoorBest
import com.dugcanlift.coach.data.Stats
import com.dugcanlift.coach.data.TrainingDay
import com.dugcanlift.coach.data.WeekStats
import com.dugcanlift.coach.ui.charts.BarChart
import com.dugcanlift.coach.ui.charts.LineChart
import com.dugcanlift.coach.ui.charts.RouteCanvas
import com.dugcanlift.coach.ui.theme.DclAccent
import com.dugcanlift.coach.ui.theme.DclMuted
import com.dugcanlift.coach.ui.theme.dclCardBorder
import com.dugcanlift.kit.OutdoorShare
import com.dugcanlift.kit.DayKey
import kotlinx.coroutines.Dispatchers
import kotlin.math.floor
import kotlinx.coroutines.withContext

/**
 * Minimum on-screen width per weekly bar/point in the volume and fuel charts. A fixed slot width
 * (rather than squeezing every bucket into the screen's width) is what keeps a two-year, 104-bucket
 * history legible instead of compressing into an unreadable smear -- the same
 * scroll-instead-of-squeeze choice [WeeklySummaryTable] already makes with [TABLE_COLUMN_WIDTH].
 */
private val CHART_WEEK_SLOT_WIDTH = 28.dp

/**
 * One client's detail: a weekly summary table covering the client's entire history, training
 * volume, fuel-vs-goal, bodyweight and per-lift e1RM charts, outdoor activity, and an expandable
 * session log.
 * Everything here is read from [Stats] over the client loaded fresh from [repo] -- never
 * recomputed here -- so re-opening this screen after a re-import always shows the latest numbers.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClientScreen(clientId: String, repo: ClientRepository, onBack: () -> Unit,
                 onCook: () -> Unit = {}, showBack: Boolean = true, reloadKey: Any? = null) {
    var client by remember(clientId) { mutableStateOf<Client?>(null) }
    var loaded by remember(clientId) { mutableStateOf(false) }

    // Loads off the main thread; keyed on clientId so navigating between clients (or back to the
    // same one) always re-reads the latest saved data rather than reusing stale state. [reloadKey]
    // changes when the roster beside this page (two-pane) reloads, e.g. after an import.
    LaunchedEffect(clientId, reloadKey) {
        client = withContext(Dispatchers.IO) { repo.get(clientId) }
        loaded = true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(client?.name ?: clientId) },
                // No Back in the roster's detail pane: the list is right there beside it.
                navigationIcon = { if (showBack) TextButton(onClick = onBack) { Text("Back") } },
                // Cook is reached from a client rather than from the roster: a
                // week is planned for someone, and the recipe library is shared
                // across clients but the week is never.
                actions = { TextButton(onClick = onCook) { Text("Cook") } }
            )
        }
    ) { padding ->
        when {
            // Brief IO read in flight -- nothing to show yet, and nothing was shown at this point
            // before either; avoids a spurious flash of the "couldn't be loaded" message below.
            !loaded -> Unit
            client == null -> Column(modifier = Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
                Text("This client's data couldn't be loaded.", style = MaterialTheme.typography.bodyLarge)
            }
            else -> ClientDetail(client = client!!, modifier = Modifier.padding(padding).fillMaxSize())
        }
    }
}

@Composable
private fun ClientDetail(client: Client, modifier: Modifier = Modifier) {
    // The page's own width, not the screen's: in the roster's two-pane layout this page is a pane.
    BoxWithConstraints(modifier) {
        ClientDetailContent(client = client, paneWidth = maxWidth)
    }
}

@Composable
private fun ClientDetailContent(client: Client, paneWidth: Dp) {
    val today = remember { DayKey.today() }
    val unit = client.displayUnit

    // Past MAX_CONTENT_DP the page stops growing and centres, so text lines stay readable.
    val contentWidth = AdaptiveLayout.clientContentWidth(paneWidth.value).dp
    val columns = AdaptiveLayout.clientColumns(contentWidth.value)
    val columnWidth = contentWidth / columns

    // The client's whole history, in whole weeks: from their oldest logged day through today
    // (capped -- see Stats.MAX_HISTORY_SPAN_WEEKS), zero for a client with no days at all.
    val spanWeeks = remember(client, today) { Stats.historySpanWeeks(client, today) }

    // Oldest-first for charts (left-to-right reads as time passing); the table itself is
    // newest-first below so a coach sees the most recent week without scrolling.
    val weeksChronological = remember(client, spanWeeks, today) {
        Stats.weeklyBuckets(client, spanWeeks, today)
    }

    // A wide history needs a wide canvas -- see CHART_WEEK_SLOT_WIDTH -- rather than squeezing
    // every bucket into the available width, which is what turns 104 weekly bars into a smear.
    // ...but never narrower than the column it sits in. At 28 dp a week, five weeks of history drew
    // a 140 dp chart in the corner with its first and last dates printed on top of each other. The
    // column is the pane's, not the screen's: beside the roster list, the screen's width would
    // push the chart's last weeks out of sight behind a scroll.
    val weeklyChartWidth = remember(weeksChronological, columnWidth) {
        // Whole dp, as `screenWidthDp` was, so a full-screen phone chart measures as it always did.
        maxOf(CHART_WEEK_SLOT_WIDTH * weeksChronological.size.coerceAtLeast(1), floor(columnWidth.value).dp)
    }

    val volumeBars = remember(weeksChronological, unit) {
        weeksChronological.map { it.endKey to displayWeightValue(it.volume, unit) }
    }

    val fuelPoints = remember(weeksChronological) {
        // Weeks with nothing logged are left out entirely, not plotted as a zero.
        weeksChronological.mapNotNull { week -> week.kcalAvg?.let { week.endKey to it.toDouble() } }
    }
    val goalCalories = client.goal?.calories?.toDouble()
    val avgProteinHitRate = remember(weeksChronological) { Stats.avgProteinHitRate(weeksChronological) }

    // Saturated fat, sugar and sodium: the newest day that recorded any, and averages over the
    // last week and four weeks counting only days that recorded each one. No goal exists for them.
    val latestNutrientDay = remember(client) {
        client.days.filter { it.nutrientTotals != null }.maxByOrNull { it.dayKey }
    }
    val nutrientWeek = remember(client, today) { Stats.nutrientAverages(client, 7, today) }
    val nutrientFourWeeks = remember(client, today) { Stats.nutrientAverages(client, 28, today) }

    val bodyweightPoints = remember(client, unit) {
        Stats.bodyweightSeries(client).map { (day, lb) -> day to displayWeightValue(lb, unit) }
    }

    val e1rmByLift = remember(client, unit) {
        Stats.perLiftE1rm(client)
            .toList()
            .sortedBy { (key, _) -> liftDisplayName(key) }
            .map { (key, series) -> key to series.map { (day, lb) -> day to displayWeightValue(lb, unit) } }
    }

    // A run is a session too: a day holding only an outdoor activity belongs in the log.
    val sessionDays = remember(client) { client.days.filter { it.sets.isNotEmpty() || it.outdoor.isNotEmpty() }.sortedByDescending { it.dayKey } }
    val recentOutdoor = remember(client) { recentOutdoorDays(client.days) }
    val hasOutdoor = client.lastRoute != null || client.outdoorBests != null || recentOutdoor.isNotEmpty()

    val volumeSection: @Composable () -> Unit = {
        SectionTitle("Training Volume")
        Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
            BarChart(
                values = volumeBars,
                barColor = DclAccent,
                modifier = Modifier.width(weeklyChartWidth).padding(horizontal = 16.dp)
            )
        }
    }

    // Saturated fat, sugar and sodium: stacked under the fuel chart on a phone, and side by side
    // under both charts when there are two columns, rather than lengthening one column alone.
    val nutrientBlocks: List<Pair<String, List<String>>> = listOfNotNull(
        latestNutrientDay?.let { day -> "Latest day recorded, ${day.dayKey}" to dayNutrientLines(day.nutrientTotals) },
        nutrientWeek.takeIf { it.isNotEmpty() }?.let { "Last 7 days, average" to it.map(::nutrientAverageLine) },
        nutrientFourWeeks.takeIf { it.isNotEmpty() }?.let { "Last 4 weeks, average" to it.map(::nutrientAverageLine) }
    ).filter { it.second.isNotEmpty() }

    val fuelSection: @Composable (Boolean) -> Unit = { withNutrients ->
        SectionTitle("Fuel vs Goal")
        Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
            LineChart(
                points = fuelPoints,
                lineColor = DclAccent,
                goal = goalCalories,
                modifier = Modifier.width(weeklyChartWidth).padding(horizontal = 16.dp)
            )
        }
        Text(
            text = "Protein goal hit ${formatPercentOrDash(avgProteinHitRate)} of logged weeks",
            style = MaterialTheme.typography.bodyMedium,
            color = DclMuted,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
        if (withNutrients) nutrientBlocks.forEach { (heading, lines) -> NutrientBlock(heading, lines) }
    }

    val bodyweightSection: @Composable () -> Unit = {
        SectionTitle("Bodyweight")
        LineChart(
            points = bodyweightPoints,
            lineColor = DclAccent,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
    }

    val e1rmHeading: @Composable () -> Unit = {
        SectionTitle("Estimated One-Rep Max")
        if (e1rmByLift.isEmpty()) {
            Text(
                text = "Not enough logged yet to chart.",
                style = MaterialTheme.typography.bodyMedium,
                color = DclMuted,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }
    }

    val liftChart: @Composable (String, List<Pair<String, Double>>) -> Unit = { key, points ->
        Text(
            text = liftDisplayName(key),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
        LineChart(
            points = points,
            lineColor = DclAccent,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
    }

    // Every wide row is capped at the content width and centred; on a phone the cap is wider
    // than the screen and changes nothing.
    val wide = Modifier.widthIn(max = contentWidth).fillMaxWidth()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item {
            Column(wide) {
                SectionTitle("Weekly Summary")
                WeeklySummaryTable(weeks = weeksChronological.sortedByDescending { it.endKey }, unit = unit)
            }
        }

        if (columns == 1) {
            item { Column(wide) { volumeSection() } }
            item { Column(wide) { fuelSection(true) } }
            item { Column(wide) { bodyweightSection() } }
            item { Column(wide) { e1rmHeading() } }
            items(e1rmByLift, key = { it.first }) { (key, points) ->
                Column(wide) { liftChart(key, points) }
            }
        } else {
            // Two columns: Training Volume | Fuel, their nutrients side by side, then Bodyweight | lifts.
            item {
                Row(wide, verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) { volumeSection() }
                    Column(Modifier.weight(1f)) { fuelSection(false) }
                }
            }
            if (nutrientBlocks.isNotEmpty()) {
                item {
                    Row(wide, verticalAlignment = Alignment.Top) {
                        nutrientBlocks.forEach { (heading, lines) ->
                            Column(Modifier.weight(1f)) { NutrientBlock(heading, lines) }
                        }
                    }
                }
            }
            // Bodyweight beside the first lift, then the rest of the lifts two by two, so a client
            // with eight lifts does not leave the left column empty for a whole screen.
            item {
                Row(wide, verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) { bodyweightSection() }
                    Column(Modifier.weight(1f)) {
                        e1rmHeading()
                        e1rmByLift.firstOrNull()?.let { (key, points) -> liftChart(key, points) }
                    }
                }
            }
            items(rowMajor(e1rmByLift.drop(1), 2), key = { row -> "lifts-${row.first().first}" }) { row ->
                Row(wide, verticalAlignment = Alignment.Top) {
                    row.forEach { (key, points) -> Column(Modifier.weight(1f)) { liftChart(key, points) } }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }

        // Shown only when there is something: most clients never record a run, and an empty
        // "Outdoor" heading would read as though they had and it was lost.
        if (hasOutdoor) {
            item { Column(wide) { SectionTitle("Outdoor") } }
            if (columns == 1) {
                client.lastRoute?.let { route -> item { Column(wide) { LastRouteCard(route, distanceUnitFor(unit)) } } }
                client.outdoorBests?.let { bests -> item { Column(wide) { PersonalBestsCard(bests, distanceUnitFor(unit)) } } }
                if (recentOutdoor.isNotEmpty()) item { Column(wide) { RecentOutdoorCard(recentOutdoor, distanceUnitFor(unit)) } }
            } else {
                // The route gets the larger share, and a taller canvas than on a phone.
                item {
                    Row(wide, verticalAlignment = Alignment.Top) {
                        client.lastRoute?.let { route ->
                            Column(Modifier.weight(1.25f)) { LastRouteCard(route, distanceUnitFor(unit), aspectRatio = 4f / 3f) }
                        }
                        Column(Modifier.weight(1f)) {
                            client.outdoorBests?.let { PersonalBestsCard(it, distanceUnitFor(unit)) }
                            if (recentOutdoor.isNotEmpty()) RecentOutdoorCard(recentOutdoor, distanceUnitFor(unit))
                        }
                    }
                }
            }
        }

        item {
            Column(wide) {
                SectionTitle("Session Log")
                if (sessionDays.isEmpty()) {
                    Text(
                        text = "No sessions logged yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = DclMuted,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            }
        }
        items(sessionDays, key = { it.dayKey }) { day ->
            Column(wide) {
                DayLogCard(day = day, unit = unit)
                HorizontalDivider()
            }
        }
    }
}

/** A heading and its lines; shown only when a caller has lines to show. */
@Composable
private fun NutrientBlock(heading: String, lines: List<String>) {
    if (lines.isEmpty()) return
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(text = heading, style = MaterialTheme.typography.titleSmall)
        lines.forEach { line ->
            Text(text = line, style = MaterialTheme.typography.bodyMedium, color = DclMuted)
        }
    }
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 12.dp)
    )
}

private val TABLE_COLUMN_WIDTH = 76.dp

@Composable
private fun WeeklySummaryTable(weeks: List<WeekStats>, unit: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier.padding(horizontal = 16.dp).horizontalScroll(rememberScrollState())) {
        TableRow(
            listOf("Week", "Sessions", "Sets", "Volume", "Kcal", "Protein", "Protein %", "Steps"),
            bold = true
        )
        HorizontalDivider()
        weeks.forEach { week ->
            TableRow(
                listOf(
                    week.endKey,
                    week.sessions.toString(),
                    week.sets.toString(),
                    formatWeight(week.volume, unit),
                    formatOrDash(week.kcalAvg),
                    formatOrDash(week.proteinAvg),
                    formatPercentOrDash(week.proteinHitRate),
                    formatOrDash(week.stepsAvg)
                )
            )
        }
    }
}

@Composable
private fun TableRow(cells: List<String>, bold: Boolean = false, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        cells.forEach { cell ->
            Text(
                text = cell,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
                color = if (bold) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(TABLE_COLUMN_WIDTH)
            )
        }
    }
}

@Composable
private fun DayLogCard(day: TrainingDay, unit: String, modifier: Modifier = Modifier) {
    var expanded by rememberSaveable(day.dayKey) { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text(text = day.dayKey, style = MaterialTheme.typography.titleMedium)
                (day.sessionName ?: day.focus ?: day.outdoor.firstOrNull()?.let { outdoorTypeLabel(it.type) })?.let {
                    Text(text = it, style = MaterialTheme.typography.bodyMedium, color = DclMuted)
                }
            }
            Text(
                text = if (expanded) "▾" else "▸",
                style = MaterialTheme.typography.titleMedium,
                color = DclMuted
            )
        }

        if (expanded) {
            Spacer(modifier = Modifier.height(8.dp))
            day.sets.forEach { set -> SetLine(set = set, unit = unit) }
            day.outdoor.forEach { activity ->
                Text(
                    text = "${outdoorTypeLabel(activity.type) ?: "Activity"} · ${activitySummary(activity, distanceUnitFor(unit))}",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 2.dp)
                )
            }
            dayNutrientLines(day.nutrientTotals).forEach { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodyMedium,
                    color = DclMuted,
                    modifier = Modifier.padding(vertical = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun SetLine(set: ExerciseSet, unit: String, modifier: Modifier = Modifier) {
    Text(
        text = formatSetLine(set, unit),
        style = MaterialTheme.typography.bodyMedium,
        color = if (set.isWarmup) DclMuted else MaterialTheme.colorScheme.onSurface,
        modifier = modifier.padding(vertical = 2.dp)
    )
}

@Composable
private fun OutdoorCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), border = dclCardBorder()) {
        Column(Modifier.padding(16.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            content()
        }
    }
}

/** Label over value, evenly across the card -- Distance / Time / Pace, and each type's bests. */
@Composable
private fun OutdoorStats(stats: List<Pair<String, String>>) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        stats.forEach { (label, value) ->
            Column(modifier = Modifier.weight(1f)) {
                Text(text = label, style = MaterialTheme.typography.bodySmall, color = DclMuted)
                Text(text = value, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun LastRouteCard(route: LastRoute, distanceUnit: String, aspectRatio: Float = 2f) {
    val points = remember(route.polyline) { OutdoorShare.decodePolyline(route.polyline) }
    OutdoorCard("Last route") {
        RouteCanvas(points = points, aspectRatio = aspectRatio)
        Spacer(modifier = Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(text = outdoorTypeLabel(route.type) ?: "Activity", style = MaterialTheme.typography.bodyMedium)
            Text(text = formatRouteDate(route.startedAtEpochSec), style = MaterialTheme.typography.bodyMedium, color = DclMuted)
        }
        OutdoorStats(listOf(
            "Distance" to formatActivityDistance(route.distanceMeters, distanceUnit),
            "Time" to formatActivityDuration(route.durationSec),
            "Pace" to (activityPace(route.distanceMeters, route.durationSec, distanceUnit) ?: "—")
        ))
        Text(
            text = "The first and last 200 m are left off by the client's app.",
            style = MaterialTheme.typography.bodySmall,
            color = DclMuted
        )
    }
}

@Composable
private fun PersonalBestsCard(bests: List<OutdoorBest>, distanceUnit: String) {
    OutdoorCard("Personal bests") {
        bests.forEach { best ->
            Text(text = bestHeading(best), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
            OutdoorStats(bestStats(best, distanceUnit))
        }
    }
}

@Composable
private fun RecentOutdoorCard(days: List<TrainingDay>, distanceUnit: String) {
    OutdoorCard("Recent") {
        days.forEach { day ->
            day.outdoor.forEach { activity ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        text = "${formatShortDay(day.dayKey)} · ${outdoorTypeLabel(activity.type) ?: "Activity"}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(text = activitySummary(activity, distanceUnit), style = MaterialTheme.typography.bodyMedium, color = DclMuted)
                }
            }
        }
    }
}
