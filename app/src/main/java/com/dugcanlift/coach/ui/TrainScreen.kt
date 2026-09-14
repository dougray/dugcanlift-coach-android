package com.dugcanlift.coach.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dugcanlift.coach.data.Client
import com.dugcanlift.coach.data.ClientRepository
import com.dugcanlift.coach.data.PrescribedSet
import com.dugcanlift.coach.data.Routine
import com.dugcanlift.coach.data.RoutineExercise
import com.dugcanlift.coach.data.ScheduledSession
import com.dugcanlift.coach.data.TrainRepository
import com.dugcanlift.coach.data.forClient
import com.dugcanlift.kit.DayKey
import com.dugcanlift.kit.trimZeros
import kotlinx.coroutines.launch

/**
 * TRAIN for Coach: the routines a coach writes and the sessions they book onto
 * a client's calendar.
 *
 * Two sections behind one chip row rather than Cook's three -- a routine has no
 * shopping list. Otherwise the shape is deliberately Cook's, down to which
 * state lives where: the picked client is held here, not inside the Schedule
 * section, so switching sections does not lose it.
 */
private enum class TrainSection(val label: String) {
    ROUTINES("Routines"), SCHEDULE("Schedule")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrainScreen(
    repo: ClientRepository,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val train = remember { TrainRepository(context.filesDir) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var revision by remember { mutableStateOf(0) }
    val library = remember(revision) { train.load() }
    val clients = remember { repo.all() }

    var section by remember { mutableStateOf(TrainSection.ROUTINES) }
    var editing by remember { mutableStateOf<Routine?>(null) }
    var confirmingDelete by remember { mutableStateOf<Routine?>(null) }
    var planClientId by remember { mutableStateOf(clients.firstOrNull()?.id) }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Train") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).padding(horizontal = 16.dp)) {

            if (library.isUnreadable) {
                Text(
                    "This device's routines can't be read. Nothing has been deleted — " +
                        "restore a backup from Connect before adding anything new, or the " +
                        "next save will write over what is still there.",
                    style = MaterialTheme.typography.bodyMedium
                )
                return@Column
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TrainSection.entries.forEach { entry ->
                    FilterChip(
                        selected = section == entry,
                        onClick = { section = entry },
                        label = { Text(entry.label) }
                    )
                }
            }
            Spacer(Modifier.height(12.dp))

            val routinesById = library.routines.associateBy { it.id }
            val clientId = planClientId
            val clientName = clients.firstOrNull { it.id == clientId }?.name
            val booked = clientId?.let { library.sessions.forClient(it) } ?: emptyList()

            when (section) {
                TrainSection.ROUTINES -> RoutineList(
                    routines = library.routines,
                    onAdd = { editing = Routine(name = "") },
                    onEdit = { editing = it },
                    onDelete = { confirmingDelete = it }
                )

                TrainSection.SCHEDULE -> ScheduleList(
                    clients = clients,
                    selectedClientId = clientId,
                    onPickClient = { planClientId = it },
                    clientName = clientName,
                    sessions = booked,
                    routinesById = routinesById,
                    routines = library.routines,
                    onBook = { routine ->
                        train.upsertSession(
                            ScheduledSession(
                                clientId = clientId ?: return@ScheduleList,
                                dayKey = DayKey.today(),
                                routineId = routine.id
                            )
                        )
                        revision++
                    },
                    onRemove = { train.deleteSession(it.id); revision++ }
                )
            }
        }
    }

    editing?.let { routine ->
        RoutineEditor(
            routine = routine,
            onCancel = { editing = null },
            onSave = {
                train.upsertRoutine(it)
                editing = null
                revision++
                scope.launch { snackbar.showSnackbar("Saved ${it.name}.") }
            }
        )
    }

    confirmingDelete?.let { routine ->
        val booked = library.sessions.count { it.routineId == routine.id }
        AlertDialog(
            onDismissRequest = { confirmingDelete = null },
            title = { Text("Delete ${routine.name}?") },
            text = {
                Text(
                    if (booked > 0)
                        "This also removes $booked booked session(s) built from it. " +
                            "A plan already sent to a client is unaffected — it left as a link."
                    else "This cannot be undone."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    train.deleteRoutine(routine.id); confirmingDelete = null; revision++
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmingDelete = null }) { Text("Keep") } }
        )
    }
}

@Composable
private fun RoutineList(
    routines: List<Routine>,
    onAdd: () -> Unit,
    onEdit: (Routine) -> Unit,
    onDelete: (Routine) -> Unit
) {
    Button(onClick = onAdd, modifier = Modifier.fillMaxWidth()) { Text("Write a routine") }
    Spacer(Modifier.height(12.dp))

    if (routines.isEmpty()) {
        Text(
            "No routines yet. Write the ones you actually prescribe — a client's " +
                "week is booked from these.",
            style = MaterialTheme.typography.bodyMedium
        )
        return
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(routines, key = { it.id }) { routine ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(routine.name.ifBlank { "Untitled" }, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${routine.exercises.size} exercise(s) · ${routine.setCount} set(s)",
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (routine.exercises.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            routine.exercises.joinToString(", ") { it.displayName },
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { onEdit(routine) }) { Text("Edit") }
                        TextButton(onClick = { onDelete(routine) }) { Text("Delete") }
                    }
                }
            }
        }
    }
}

@Composable
private fun ScheduleList(
    clients: List<Client>,
    selectedClientId: String?,
    onPickClient: (String) -> Unit,
    clientName: String?,
    sessions: List<ScheduledSession>,
    routinesById: Map<String, Routine>,
    routines: List<Routine>,
    onBook: (Routine) -> Unit,
    onRemove: (ScheduledSession) -> Unit
) {
    if (clients.isEmpty()) {
        Text(
            "No clients yet. A session is booked for someone, so import a client " +
                "from the roster first — the routines above are yours either way.",
            style = MaterialTheme.typography.bodyMedium
        )
        return
    }

    Text("Booking for", style = MaterialTheme.typography.labelMedium)
    Spacer(Modifier.height(4.dp))
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.horizontalScroll(rememberScrollState())
    ) {
        clients.forEach { client ->
            FilterChip(
                selected = client.id == selectedClientId,
                onClick = { onPickClient(client.id) },
                label = { Text(client.name) }
            )
        }
    }
    Spacer(Modifier.height(12.dp))

    if (clientName == null) {
        Text("Pick a client to book for.", style = MaterialTheme.typography.bodyMedium)
        return
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(sessions, key = { it.id }) { session ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        routinesById[session.routineId]?.name ?: "Deleted routine",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(session.dayKey, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { onRemove(session) }) { Text("Remove") }
                }
            }
        }

        if (routines.isNotEmpty()) {
            item { HorizontalDivider() }
            item { Text("Book a session", style = MaterialTheme.typography.titleSmall) }
            items(routines, key = { "book-${it.id}" }) { routine ->
                OutlinedButton(onClick = { onBook(routine) }, modifier = Modifier.fillMaxWidth()) {
                    Text(routine.name.ifBlank { "Untitled" })
                }
            }
        } else {
            item {
                Text("Write a routine first — a session is booked from one.",
                     style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/**
 * One exercise per line, `name | equipment | sets x reps @ kg`, which is the
 * shortest thing that still round-trips through [Routine] without inventing a
 * multi-screen editor. Weights are kilograms, as stored -- see `Train.kt`.
 */
@Composable
private fun RoutineEditor(routine: Routine, onCancel: () -> Unit, onSave: (Routine) -> Unit) {
    var name by remember(routine.id) { mutableStateOf(routine.name) }
    var lines by remember(routine.id) {
        mutableStateOf(routine.exercises.joinToString("\n") { exercise ->
            val first = exercise.sets.firstOrNull()
            val scheme = listOfNotNull(
                exercise.sets.size.takeIf { it > 0 }?.toString(),
                first?.targetReps?.toString()
            ).joinToString(" x ")
            val load = first?.targetWeightKg?.trimZeros()?.let { " @ $it" }.orEmpty()
            listOf(exercise.name, exercise.equipment).filter { it.isNotBlank() }
                .joinToString(" | ") + (if (scheme.isNotBlank()) " | $scheme$load" else "")
        })
    }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(if (routine.name.isBlank()) "New routine" else "Edit routine") },
        text = {
            Column {
                OutlinedTextField(value = name, onValueChange = { name = it },
                                  label = { Text("Name") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = lines, onValueChange = { lines = it },
                    label = { Text("One exercise per line") },
                    supportingText = { Text("Bench | Barbell | 3 x 8 @ 60") }
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = { onSave(routine.copy(name = name.trim(), exercises = parseExercises(lines))) }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } }
    )
}

/**
 * `name | equipment | sets x reps @ kg`, every part after the name optional.
 *
 * Internal rather than private so it can be tested directly: it is the one
 * piece of this screen with rules rather than layout.
 */
internal fun parseExercises(text: String): List<RoutineExercise> =
    text.lines().mapNotNull { raw ->
        val line = raw.trim()
        if (line.isEmpty()) return@mapNotNull null
        val parts = line.split("|").map { it.trim() }
        val name = parts.getOrNull(0).orEmpty()
        if (name.isEmpty()) return@mapNotNull null
        val equipment = parts.getOrNull(1).orEmpty()
        val scheme = parts.getOrNull(2).orEmpty()

        // "3 x 8 @ 60" -- sets, reps, kilograms. A line with no scheme is an
        // exercise with no prescribed sets, which is a legitimate thing to
        // write down and not an error.
        val load = scheme.substringAfter("@", "").trim().toDoubleOrNull()
        val counts = scheme.substringBefore("@").split("x", "X")
            .mapNotNull { it.trim().toIntOrNull() }
        val setCount = counts.getOrNull(0) ?: 0
        val reps = counts.getOrNull(1)
        RoutineExercise(
            name = name,
            equipment = equipment,
            sets = List(setCount) { PrescribedSet(targetWeightKg = load, targetReps = reps) }
        )
    }
