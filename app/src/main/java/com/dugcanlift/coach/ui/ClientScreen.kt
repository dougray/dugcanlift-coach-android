package com.dugcanlift.coach.ui

import androidx.compose.foundation.clickable
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
import com.dugcanlift.coach.data.Stats
import com.dugcanlift.coach.data.TrainingDay
import com.dugcanlift.coach.data.WeekStats
import com.dugcanlift.coach.ui.charts.BarChart
import com.dugcanlift.coach.ui.charts.LineChart
import com.dugcanlift.coach.ui.theme.DclAccent
import com.dugcanlift.coach.ui.theme.DclMuted
import com.dugcanlift.kit.DayKey
import kotlinx.coroutines.Dispatchers
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
 * volume, fuel-vs-goal, bodyweight and per-lift e1RM charts, and an expandable session log.
 * Everything here is read from [Stats] over the client loaded fresh from [repo] -- never
 * recomputed here -- so re-opening this screen after a re-import always shows the latest numbers.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClientScreen(clientId: String, repo: ClientRepository, onBack: () -> Unit,
                 onCook: () -> Unit = {}) {
    var client by remember(clientId) { mutableStateOf<Client?>(null) }
    var loaded by remember(clientId) { mutableStateOf(false) }

    // Loads off the main thread; keyed on clientId so navigating between clients (or back to the
    // same one) always re-reads the latest saved data rather than reusing stale state.
    LaunchedEffect(clientId) {
        client = withContext(Dispatchers.IO) { repo.get(clientId) }
        loaded = true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(client?.name ?: clientId) },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
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
    val today = remember { DayKey.today() }
    val unit = client.displayUnit

    // The client's whole history, in whole weeks: from their oldest logged day through today
    // (capped -- see Stats.MAX_HISTORY_SPAN_WEEKS), zero for a client with no days at all.
    val spanWeeks = remember(client, today) { Stats.historySpanWeeks(client, today) }

    // Oldest-first for charts (left-to-right reads as time passing); the table itself is
    // newest-first below so a coach sees the most recent week without scrolling.
    val weeksChronological = remember(client, spanWeeks, today) {
        Stats.weeklyBuckets(client, spanWeeks, today)
    }

    // A wide history needs a wide canvas -- see CHART_WEEK_SLOT_WIDTH -- rather than squeezing
    // every bucket into the screen's width, which is what turns 104 weekly bars into a smear.
    val weeklyChartWidth = remember(weeksChronological) {
        CHART_WEEK_SLOT_WIDTH * weeksChronological.size.coerceAtLeast(1)
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

    val bodyweightPoints = remember(client, unit) {
        Stats.bodyweightSeries(client).map { (day, lb) -> day to displayWeightValue(lb, unit) }
    }

    val e1rmByLift = remember(client, unit) {
        Stats.perLiftE1rm(client)
            .toList()
            .sortedBy { (key, _) -> liftDisplayName(key) }
            .map { (key, series) -> key to series.map { (day, lb) -> day to displayWeightValue(lb, unit) } }
    }

    val sessionDays = remember(client) { client.days.filter { it.sets.isNotEmpty() }.sortedByDescending { it.dayKey } }

    LazyColumn(modifier = modifier, contentPadding = PaddingValues(bottom = 32.dp)) {
        item {
            SectionTitle("Weekly Summary")
            WeeklySummaryTable(weeks = weeksChronological.sortedByDescending { it.endKey }, unit = unit)
        }

        item {
            SectionTitle("Training Volume")
            Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                BarChart(
                    values = volumeBars,
                    barColor = DclAccent,
                    modifier = Modifier.width(weeklyChartWidth).padding(horizontal = 16.dp)
                )
            }
        }

        item {
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
        }

        item {
            SectionTitle("Bodyweight")
            LineChart(
                points = bodyweightPoints,
                lineColor = DclAccent,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }

        item {
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
        items(e1rmByLift, key = { it.first }) { (key, points) ->
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

        item {
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
        items(sessionDays, key = { it.dayKey }) { day ->
            DayLogCard(day = day, unit = unit)
            HorizontalDivider()
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
    var expanded by remember(day.dayKey) { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text(text = day.dayKey, style = MaterialTheme.typography.titleMedium)
                (day.sessionName ?: day.focus)?.let {
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
