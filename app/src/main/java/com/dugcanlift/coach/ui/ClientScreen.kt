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

private val WEEK_OPTIONS = listOf(4, 8, 12)

/**
 * One client's detail: a weekly summary table (4/8/12 weeks, selectable), training volume,
 * fuel-vs-goal, bodyweight and per-lift e1RM charts, and an expandable session log. Everything
 * here is read from [Stats] over the client loaded fresh from [repo] -- never recomputed here --
 * so re-opening this screen after a re-import always shows the latest numbers.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClientScreen(clientId: String, repo: ClientRepository, onBack: () -> Unit) {
    val client = remember(clientId) { repo.get(clientId) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(client?.name ?: clientId) },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } }
            )
        }
    ) { padding ->
        if (client == null) {
            Column(modifier = Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
                Text("This client's data couldn't be loaded.", style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            ClientDetail(client = client, modifier = Modifier.padding(padding).fillMaxSize())
        }
    }
}

@Composable
private fun ClientDetail(client: Client, modifier: Modifier = Modifier) {
    var selectedWeeks by remember { mutableStateOf(WEEK_OPTIONS.first()) }
    val today = remember { DayKey.today() }
    val unit = client.displayUnit

    // Oldest-first for charts (left-to-right reads as time passing); the table itself is
    // newest-first below so a coach sees the most recent week without scrolling.
    val weeksChronological = remember(client, selectedWeeks, today) {
        Stats.weeklyBuckets(client, selectedWeeks, today)
    }

    val volumeBars = remember(weeksChronological, unit) {
        weeksChronological.map { it.endKey to displayWeightValue(it.volume, unit) }
    }

    val fuelPoints = remember(weeksChronological) {
        // Weeks with nothing logged are left out entirely, not plotted as a zero.
        weeksChronological.mapNotNull { week -> week.kcalAvg?.let { week.endKey to it.toDouble() } }
    }
    val goalCalories = client.goal?.calories?.toDouble()
    val proteinHitRates = remember(weeksChronological) { weeksChronological.mapNotNull { it.proteinHitRate } }
    val avgProteinHitRate = if (proteinHitRates.isEmpty()) null else proteinHitRates.average()

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
            WeekSelector(selected = selectedWeeks, onSelect = { selectedWeeks = it })
            Spacer(modifier = Modifier.height(8.dp))
            WeeklySummaryTable(weeks = weeksChronological.sortedByDescending { it.endKey }, unit = unit)
        }

        item {
            SectionTitle("Training Volume")
            BarChart(
                values = volumeBars,
                barColor = DclAccent,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }

        item {
            SectionTitle("Fuel vs Goal")
            LineChart(
                points = fuelPoints,
                lineColor = DclAccent,
                goal = goalCalories,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
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

@Composable
private fun WeekSelector(selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        WEEK_OPTIONS.forEach { weeks ->
            Text(
                text = "$weeks wk",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (weeks == selected) FontWeight.Bold else FontWeight.Normal,
                color = if (weeks == selected) DclAccent else DclMuted,
                modifier = Modifier.clickable { onSelect(weeks) }
            )
        }
    }
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
