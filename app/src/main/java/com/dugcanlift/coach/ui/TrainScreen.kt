package com.dugcanlift.coach.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.saveable.rememberSaveable
import com.dugcanlift.coach.ui.adaptive.AdaptiveLayout
import com.dugcanlift.coach.ui.adaptive.GridRow
import com.dugcanlift.coach.ui.adaptive.rowMajor
import com.dugcanlift.coach.ui.theme.dclCardBorder
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
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dugcanlift.coach.data.Client
import com.dugcanlift.coach.data.EQUIPMENT_FILTERS
import com.dugcanlift.coach.data.ExerciseLibraryStore
import com.dugcanlift.coach.data.LibraryExercise
import com.dugcanlift.coach.data.searchExerciseLibrary
import com.dugcanlift.coach.data.titleCaseAscii
import com.dugcanlift.coach.data.ClientRepository
import com.dugcanlift.coach.data.PlanWeek
import com.dugcanlift.coach.data.PrescribedSet
import com.dugcanlift.coach.data.Routine
import com.dugcanlift.coach.data.TrainPlanSend
import com.dugcanlift.coach.data.PrescriptionSides
import com.dugcanlift.coach.data.RoutineEditing
import com.dugcanlift.coach.data.SetSide
import com.dugcanlift.coach.data.RoutineExercise
import com.dugcanlift.coach.data.ScheduledSession
import com.dugcanlift.coach.data.TrainRepository
import com.dugcanlift.coach.data.forClient
import com.dugcanlift.kit.DayKey
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
    modifier: Modifier = Modifier,
    showBack: Boolean = true
) {
    val context = LocalContext.current
    val train = remember { TrainRepository(context.filesDir) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var revision by remember { mutableStateOf(0) }
    val library = remember(revision) { train.load() }
    val clients = remember { repo.all() }

    var section by rememberSaveable { mutableStateOf(TrainSection.ROUTINES) }
    // Saved as an id, as Cook does, so a recreated activity reopens the same editor.
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    val editing = editingId?.let { id -> library.routines.firstOrNull { it.id == id } ?: Routine(id = id, name = "") }
    var confirmingDelete by remember { mutableStateOf<Routine?>(null) }
    var planClientId by rememberSaveable { mutableStateOf(clients.firstOrNull()?.id) }
    // The week on screen, and the week a Send carries. Saved as its first day,
    // as Coach iOS holds `planWeekStart`: a theme change mid-plan should not
    // throw a coach back to today.
    var weekStart by rememberSaveable { mutableStateOf(DayKey.today()) }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Train") },
                navigationIcon = { if (showBack) TextButton(onClick = onBack) { Text("Back") } }
            )
        }
    ) { padding ->
      BoxWithConstraints(Modifier.padding(padding)) {
        // Below 600 dp every grid here is one column: the phone layout, unchanged.
        val columns = AdaptiveLayout.cardColumns(maxWidth.value - 32f)
        Column(Modifier.padding(horizontal = 16.dp)) {

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
                    columns = columns,
                    onAdd = { editingId = Routine(name = "").id },
                    onEdit = { editingId = it.id },
                    onDelete = { confirmingDelete = it }
                )

                TrainSection.SCHEDULE -> {
                    // What the Send would carry, computed once per change rather
                    // than on every recomposition: it filters, builds JSON and
                    // DEFLATEs, and the button, the note and the tap all read the
                    // same answer. Coach iOS computes it into state for the same
                    // reason.
                    val send = remember(revision, clientId, weekStart) {
                        TrainPlanSend.build(
                            clientId = clientId,
                            clientName = clientName,
                            week = PlanWeek(weekStart),
                            sessions = library.sessions,
                            routines = library.routines,
                            coachName = TrainPlanSend.coachName(
                                context.getSharedPreferences("connect", android.content.Context.MODE_PRIVATE)
                                    .getString("coachName", "")
                            )
                        )
                    }
                    ScheduleList(
                        clients = clients,
                        selectedClientId = clientId,
                        onPickClient = { planClientId = it },
                        clientName = clientName,
                        week = PlanWeek(weekStart),
                        onWeek = { weekStart = it.startDayKey },
                        sessions = booked,
                        routinesById = routinesById,
                        routines = library.routines,
                        send = send,
                        onBook = { routine, day ->
                            train.upsertSession(
                                ScheduledSession(
                                    clientId = clientId ?: return@ScheduleList,
                                    dayKey = day,
                                    routineId = routine.id
                                )
                            )
                            revision++
                        },
                        onRemove = { train.deleteSession(it.id); revision++ },
                        onSend = {
                            // Cook's Send, mechanism for mechanism: a plain-text
                            // ACTION_SEND through the chooser, so a coach picks
                            // the mail or message app they already use with this
                            // client. Nothing is copied to a server on the way.
                            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(android.content.Intent.EXTRA_TEXT, send.message)
                            }
                            context.startActivity(
                                android.content.Intent.createChooser(intent, "Send this week")
                            )
                        },
                        columns = columns
                    )
                }
            }
        }
      }
    }

    editing?.let { routine ->
        RoutineEditor(
            routine = routine,
            onCancel = { editingId = null },
            onSave = {
                train.upsertRoutine(it)
                editingId = null
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
    columns: Int,
    onAdd: () -> Unit,
    onEdit: (Routine) -> Unit,
    onDelete: (Routine) -> Unit
) {
    Button(onClick = onAdd, modifier = Modifier.wideButton(columns)) { Text("Write a routine") }
    Spacer(Modifier.height(12.dp))

    if (routines.isEmpty()) {
        Text(
            "No routines yet. Write the ones you actually prescribe — a client's " +
                "week is booked from these.",
            style = MaterialTheme.typography.bodyMedium
        )
        return
    }

    val card: @Composable (Routine, Modifier) -> Unit = { routine, modifier ->
        Card(modifier.fillMaxWidth(), border = dclCardBorder()) {
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

    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (columns == 1) {
            items(routines, key = { it.id }) { routine -> card(routine, Modifier) }
        } else {
            items(rowMajor(routines, columns), key = { row -> row.first().id }) { row ->
                GridRow(row, columns) { routine -> card(routine, Modifier.fillMaxHeight()) }
            }
        }
    }
}

/**
 * A client's week: which routine is booked on which day, and the link that
 * sends it.
 *
 * The week is Coach iOS's `TrainPlanView` and Coach web's training week --
 * seven days from a start a coach can move, a day at a time down the list on a
 * phone and side by side where there is room. What the Send carries is
 * [TrainPlanSend]'s decision, not this composable's; this only draws it.
 */
@Composable
private fun ScheduleList(
    clients: List<Client>,
    selectedClientId: String?,
    onPickClient: (String) -> Unit,
    clientName: String?,
    week: PlanWeek,
    onWeek: (PlanWeek) -> Unit,
    sessions: List<ScheduledSession>,
    routinesById: Map<String, Routine>,
    routines: List<Routine>,
    send: TrainPlanSend,
    onBook: (Routine, String) -> Unit,
    onRemove: (ScheduledSession) -> Unit,
    onSend: () -> Unit,
    columns: Int = 1
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

    if (routines.isEmpty()) {
        Text("Write a routine first — a session is booked from one.",
             style = MaterialTheme.typography.bodyMedium)
        return
    }

    // ‹ This week ›, the control Coach iOS shares between Cook's and Train's
    // plan screens so a coach learns it once.
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        TextButton(onClick = { onWeek(week.advanced(-1)) }) { Text("‹ Earlier") }
        Text(
            week.label(),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Center
        )
        TextButton(onClick = { onWeek(week.advanced(1)) }) { Text("Later ›") }
    }
    Spacer(Modifier.height(8.dp))

    // Only with something booked to send: a link offering nothing is an import
    // prompt on a client's phone that adds nothing, which is Coach iOS's reason
    // for gating the same button.
    if (send.isSendable) {
        Button(onClick = onSend, modifier = Modifier.wideButton(columns)) {
            Text("Send this week to $clientName")
        }
        Spacer(Modifier.height(4.dp))
    }
    Text(
        send.note,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    if (send.removedBookings > 0) {
        Text(
            "${send.removedBookings} booking(s) this week point at a workout that has been " +
                "deleted — they are not sent. Remove them or rebuild the routine before sending.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    Spacer(Modifier.height(12.dp))

    val dayCard: @Composable (String, Modifier) -> Unit = { day, modifier ->
        Card(modifier.fillMaxWidth(), border = dclCardBorder()) {
            Column(Modifier.padding(12.dp)) {
                Text(formatShortDay(day), style = MaterialTheme.typography.titleSmall)
                Text(day, style = MaterialTheme.typography.bodySmall)
                val booked = sessions.filter { it.dayKey == day }
                if (booked.isEmpty()) {
                    Text("Rest.", style = MaterialTheme.typography.bodySmall,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                booked.forEach { session ->
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            // Coach iOS's name for a booking whose template is
                            // gone. It is dropped from the link rather than sent
                            // as a day carrying nothing.
                            routinesById[session.routineId]?.name?.ifBlank { "Untitled" } ?: "Removed workout",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        TextButton(onClick = { onRemove(session) }) { Text("Remove") }
                    }
                }
                Spacer(Modifier.height(4.dp))
                BookMenu(routines = routines, onBook = { onBook(it, day) })
            }
        }
    }

    val days = week.days
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (columns == 1) {
            items(days, key = { it }) { day -> dayCard(day, Modifier) }
        } else {
            items(rowMajor(days, columns), key = { row -> "day-${row.first()}" }) { row ->
                GridRow(row, columns) { day -> dayCard(day, Modifier.fillMaxHeight()) }
            }
        }
    }
}

/** "Book" on a day, and the routines it can book, as one menu. */
@Composable
private fun BookMenu(routines: List<Routine>, onBook: (Routine) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) { Text("Book") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            routines.forEach { routine ->
                DropdownMenuItem(
                    text = { Text(routine.name.ifBlank { "Untitled" }) },
                    onClick = { open = false; onBook(routine) }
                )
            }
        }
    }
}

/**
 * One exercise per line, `name | equipment | sets x reps @ kg`, which is the
 * shortest thing that still round-trips through [Routine] without inventing a
 * multi-screen editor. Weights are kilograms, as stored -- see `Train.kt`.
 *
 * Under the box, each exercise it names gets its sides (PLAN-FORMAT "Sides"):
 * an "Each side" toggle, and a Both / L / R control per set that stays out of
 * sight until the exercise is each side or the coach taps "Set a side" -- so a
 * bench press looks exactly as it always did. The rules are
 * [PrescriptionSides] and [RoutineEditing]; this only draws them.
 */
@Composable
private fun RoutineEditor(routine: Routine, onCancel: () -> Unit, onSave: (Routine) -> Unit) {
    val context = LocalContext.current
    val eachSideChoices = remember { EachSideChoices(context) }
    var name by rememberSaveable(routine.id) { mutableStateOf(routine.name) }
    var lines by rememberSaveable(routine.id) {
        mutableStateOf(routine.exercises.joinToString("\n", transform = RoutineEditing::renderLine))
    }
    // Held as JSON text: what rememberSaveable can put in a Bundle is what
    // survives a theme change with the editor open.
    var sidesText by rememberSaveable(routine.id) { mutableStateOf(RoutineEditing.encode(RoutineEditing.initial(routine))) }
    val sides = remember(sidesText) { RoutineEditing.decode(sidesText) }
    val parsed = remember(lines) { parseExercises(lines) }
    val keys = remember(parsed) { RoutineEditing.keys(parsed) }
    val edited = RoutineEditing.apply(parsed, routine, sides, eachSideChoices::get)
    fun update(index: Int, change: (RoutineEditing.ExerciseSides) -> RoutineEditing.ExerciseSides) {
        val key = keys[index]
        val current = RoutineEditing.sidesFor(sides, key, parsed[index], eachSideChoices::get)
        // An exercise that has not been touched yet starts from the sets it has.
        val base = if (current.sides.isEmpty()) current.copy(sides = edited[index].sets.map { it.side }) else current
        sidesText = RoutineEditing.encode(sides + (key to change(base)))
    }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(if (routine.name.isBlank()) "New routine" else "Edit routine") },
        text = {
            // Scrolls: the picker below can add forty rows to this dialog, and
            // an AlertDialog's own content does not scroll for you.
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(value = name, onValueChange = { name = it },
                                  label = { Text("Name") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = lines, onValueChange = { lines = it },
                    label = { Text("One exercise per line") },
                    supportingText = { Text("Bench | Barbell | 3 x 8 @ 60") }
                )
                edited.forEachIndexed { index, exercise ->
                    Spacer(Modifier.height(12.dp))
                    ExerciseSidesEditor(
                        exercise = exercise,
                        asked = sides[keys[index]]?.asked == true,
                        onEachSide = { on ->
                            eachSideChoices.set(exercise.name, exercise.equipment, on)
                            update(index) { it.copy(eachSide = on) }
                        },
                        onAsk = { update(index) { it.copy(asked = true) } },
                        onSide = { setIndex, side -> update(index) { RoutineEditing.withSide(it, setIndex, side) } }
                    )
                }
                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))
                ExerciseLibraryPicker(
                    onPick = { exercise ->
                        // Appended rather than inserted at the cursor: the box
                        // is a whole routine, and a picked exercise goes at the
                        // end of it like a typed one would.
                        lines = if (lines.isBlank()) exercise.routineLine
                        else lines.trimEnd('\n') + "\n" + exercise.routineLine
                    }
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    // Through RoutineEditing, not straight from the box: an
                    // unchanged line keeps its ramp and its note.
                    onSave(routine.copy(name = name.trim(), exercises = edited))
                }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } }
    )
}

/**
 * One exercise's sides: its name and what it asks for ("3 × 30 × 8 each side
 * + 1 L"), the "Each side" toggle, and -- once shown -- a row per set reading
 * "30 × 8 L" with its Both / L / R control.
 */
@Composable
private fun ExerciseSidesEditor(
    exercise: RoutineExercise,
    asked: Boolean,
    onEachSide: (Boolean) -> Unit,
    onAsk: () -> Unit,
    onSide: (Int, SetSide?) -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Text(exercise.displayName, style = MaterialTheme.typography.titleSmall)
        Text(
            PrescriptionSides.summary(exercise),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        val shown = PrescriptionSides.showsSides(exercise, asked)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = exercise.eachSide,
                onClick = { onEachSide(!exercise.eachSide) },
                label = { Text("Each side") },
                modifier = Modifier.semantics {
                    stateDescription = if (exercise.eachSide) "Every set is done on both sides"
                    else "Sets counted once"
                }
            )
            if (!shown && exercise.sets.isNotEmpty()) {
                TextButton(onClick = onAsk) { Text("Set a side") }
            }
        }
        if (shown) {
            // Beside the set where there is room; on a line of its own under it
            // at phone width, where a column beside it squeezed "14 × 8" onto
            // three lines -- Coach web's rule for the same control.
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val beside = maxWidth >= 380.dp
                Column {
                    exercise.sets.forEachIndexed { i, set ->
                        val label: @Composable (Modifier) -> Unit = { modifier ->
                            Text(
                                "${i + 1}.  ${PrescriptionSides.setText(set)}",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = modifier
                            )
                        }
                        val control: @Composable () -> Unit = { SideControl(i, set.side) { onSide(i, it) } }
                        if (beside) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                label(Modifier.weight(1f))
                                control()
                            }
                        } else {
                            Spacer(Modifier.height(4.dp))
                            label(Modifier)
                            control()
                        }
                    }
                }
            }
        }
    }
}

/** Both / L / R for one set, the chosen segment filled. */
@Composable
private fun SideControl(setIndex: Int, current: SetSide?, onChoose: (SetSide?) -> Unit) {
    SingleChoiceSegmentedButtonRow {
        val choices = listOf<Pair<SetSide?, String>>(null to "Both", SetSide.LEFT to "L", SetSide.RIGHT to "R")
        choices.forEachIndexed { c, (side, label) ->
            SegmentedButton(
                selected = current == side,
                onClick = { onChoose(side) },
                shape = SegmentedButtonDefaults.itemShape(index = c, count = choices.size),
                icon = {},
                modifier = Modifier.semantics {
                    contentDescription = "Set ${setIndex + 1}, " + (side?.label ?: "both sides")
                }
            ) { Text(label, maxLines = 1) }
        }
    }
}

/**
 * The coach's own answer to "Each side" for a lift, kept once given, so the
 * next time that lift is written the toggle starts where they left it rather
 * than where the name guesses. Keyed `name|equipment`, as LIFT keys its
 * per-side preference. On this device only: Coach web keeps its copy in its
 * own settings.
 */
private class EachSideChoices(context: android.content.Context) {
    private val prefs = context.getSharedPreferences("coach_settings", android.content.Context.MODE_PRIVATE)
    private fun key(lift: String) = "each_side|$lift"
    fun get(lift: String): Boolean? = if (prefs.contains(key(lift))) prefs.getBoolean(key(lift), false) else null
    fun set(name: String, equipment: String, on: Boolean) {
        prefs.edit().putBoolean(key(PrescriptionSides.eachSideKey(name, equipment)), on).apply()
    }
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

/**
 * Search over the bundled 873, appending a correctly-spelled `name | equipment`
 * line to the routine box.
 *
 * It sits under the text box rather than replacing it. Writing a whole routine
 * as six lines of text is the fast path and stays the fast path; this is for
 * the name you want spelled the way the client's app spells it, which is every
 * name that has to line up with a logged set.
 *
 * Typing a line by hand still works, and still works when the asset fails to
 * load. A coach's own vocabulary is not an error.
 */
@Composable
private fun ExerciseLibraryPicker(onPick: (LibraryExercise) -> Unit) {
    val context = LocalContext.current

    var library by remember { mutableStateOf<List<LibraryExercise>?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        library = ExerciseLibraryStore.load(context)
        loadError = ExerciseLibraryStore.lastError
        loading = false
    }

    val results = remember(library, query, filter) {
        library?.let { searchExerciseLibrary(it, query, filter) }.orEmpty()
    }

    OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        label = { Text("Find an exercise") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )

    Spacer(Modifier.height(8.dp))

    when {
        loading -> Text("Loading the exercise library...",
                        style = MaterialTheme.typography.bodySmall)

        loadError != null -> Text(
            "Could not load the exercise library ($loadError). " +
                "You can still write exercises by hand above.",
            style = MaterialTheme.typography.bodySmall
        )

        else -> {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            ) {
                FilterChip(selected = filter.isEmpty(), onClick = { filter = "" },
                           label = { Text("All") })
                EQUIPMENT_FILTERS.forEach { option ->
                    FilterChip(
                        selected = filter == option,
                        onClick = { filter = if (filter == option) "" else option },
                        label = { Text(titleCaseAscii(option)) }
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            if (results.isEmpty()) {
                Text("Nothing matches that.", style = MaterialTheme.typography.bodySmall)
            } else {
                results.forEach { hit ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(hit) }
                            .padding(vertical = 6.dp)
                    ) {
                        Text(hit.name, style = MaterialTheme.typography.bodyMedium)
                        Text(hit.detailLabel, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
