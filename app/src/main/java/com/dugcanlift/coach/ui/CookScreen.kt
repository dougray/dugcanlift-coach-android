package com.dugcanlift.coach.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.saveable.rememberSaveable
import com.dugcanlift.coach.ui.adaptive.AdaptiveLayout
import com.dugcanlift.coach.ui.adaptive.GridRow
import com.dugcanlift.coach.ui.adaptive.columnMajor
import com.dugcanlift.coach.ui.adaptive.rowMajor
import com.dugcanlift.coach.ui.theme.dclCardBorder
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.dugcanlift.coach.data.Client
import com.dugcanlift.coach.data.ClientRepository
import com.dugcanlift.coach.data.CoachShoppingList
import com.dugcanlift.coach.data.CookPlanEncoder
import com.dugcanlift.coach.data.CookRepository
import com.dugcanlift.coach.data.PlanEnvelope
import com.dugcanlift.coach.data.PlannedMeal
import com.dugcanlift.coach.data.Recipe
import com.dugcanlift.coach.data.RoadFoodChain
import com.dugcanlift.coach.data.RoadFoodData
import com.dugcanlift.coach.data.RoadFoodItem
import com.dugcanlift.coach.data.RoadFoodStore
import com.dugcanlift.coach.data.RoadPickRepository
import com.dugcanlift.coach.data.RoadPicks
import com.dugcanlift.coach.data.SentPlan
import com.dugcanlift.coach.data.SentPlanRepository
import com.dugcanlift.coach.data.SentPlans
import com.dugcanlift.coach.data.forClient
import com.dugcanlift.coach.data.hasMacros
import com.dugcanlift.kit.CaptionRecipe
import com.dugcanlift.kit.IngredientParser
import com.dugcanlift.kit.Split
import com.dugcanlift.coach.data.RecipeWeightUnit
import com.dugcanlift.kit.RecipeNutrition
import com.dugcanlift.kit.trimZeros
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * COOK for Coach: the recipes a coach writes, the week they build for one
 * client, the shopping list that falls out of it, and the Road Food items the
 * coach is happy with for that client.
 *
 * Four sections behind one chip row rather than four routes, matching Coach
 * iOS's `CookView` and Coach web's own chip row. The week and the shopping
 * list are two views of the same planned meals, and a coach moves between them
 * constantly while planning; Road sits beside them on the same client picker
 * because a pick is addressed to one person and rides in the same plan link.
 * Train was the other candidate and is the wrong half of the app: this is food.
 */
private enum class CookSection(val label: String) {
    RECIPES("Recipes"), PLAN("Plan"), SHOPPING("Shopping"), ROAD("Road")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CookScreen(
    repo: ClientRepository,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    showBack: Boolean = true
) {
    val context = LocalContext.current
    val cook = remember { CookRepository(context.filesDir) }
    val pickStore = remember { RoadPickRepository(context.filesDir) }
    val clients = remember { repo.all() }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    // Bumped after every write so the whole screen re-reads. The library is one
    // small file and every section shows a different slice of it; a
    // finer-grained store would be more machinery than the data justifies.
    var revision by remember { mutableStateOf(0) }
    val library = remember(revision) { cook.load() }

    var section by rememberSaveable { mutableStateOf(CookSection.RECIPES) }

    // Held here rather than inside the Plan section: each section is its own
    // subtree, so state living below would be torn down and rebuilt on every
    // section switch and the picked client would be lost on the way to
    // Shopping -- which reads the same client's week. Coach iOS's CookView
    // owns it at this level for exactly the same reason.
    var planClientId by rememberSaveable { mutableStateOf(clients.firstOrNull()?.id) }
    // The recipe open in the editor, saved as its id so an activity recreation (a theme or
    // density change) reopens it. An id the library does not hold is a new, unsaved recipe.
    // Its own revision, not the library's: a tick should not re-read the whole
    // recipe book, and the recipe book's writes should not re-read the picks.
    var pickRevision by remember { mutableStateOf(0) }
    val storedPicks = remember(pickRevision) { pickStore.load() }

    // The Road Food file, read the first time the Road section is opened rather
    // than at launch -- a coach who never marks a pick never pays for it.
    var roadData by remember { mutableStateOf<RoadFoodData?>(null) }
    var roadError by remember { mutableStateOf<String?>(null) }
    // Which place cards are open, held here rather than inside the section:
    // each section is its own subtree, so state below would be torn down on
    // every switch. The same reason planClientId lives at this level.
    val roadOpen = remember { mutableStateMapOf<String, Boolean>() }
    var confirmingClearPicks by remember { mutableStateOf(false) }

    LaunchedEffect(section) {
        if (section == CookSection.ROAD && roadData == null && roadError == null) {
            roadData = RoadFoodStore.load(context)
            if (roadData == null) roadError = RoadFoodStore.lastError ?: "unknown error"
        }
    }

    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    val editing = editingId?.let { id -> library.recipes.firstOrNull { it.id == id } ?: Recipe(id = id, name = "") }
    var confirmingDelete by remember { mutableStateOf<Recipe?>(null) }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Cook") },
                navigationIcon = { if (showBack) TextButton(onClick = onBack) { Text("Back") } }
            )
        }
    ) { padding ->
      BoxWithConstraints(Modifier.padding(padding)) {
        // Below 600 dp every grid here is one column: the phone layout, unchanged.
        val available = maxWidth.value - 32f
        Column(Modifier.padding(horizontal = 16.dp)) {

            if (library.isUnreadable) {
                // The same distinction StoredCookLibrary exists to make: this is
                // not an empty library, and offering "write your first recipe"
                // here would invite the coach to save over what is still there.
                Text(
                    "This device's recipes can't be read. Nothing has been deleted — " +
                        "restore a backup from Connect, or send this device's file to Doug " +
                        "before adding anything new.",
                    style = MaterialTheme.typography.bodyMedium
                )
                return@Column
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CookSection.entries.forEach { entry ->
                    FilterChip(
                        selected = section == entry,
                        onClick = { section = entry },
                        label = { Text(entry.label) }
                    )
                }
            }
            Spacer(Modifier.height(12.dp))

            val recipesById = library.recipes.associateBy { it.id }
            val clientId = planClientId
            val clientName = clients.firstOrNull { it.id == clientId }?.name
            val weekMeals = clientId?.let { library.meals.forClient(it) } ?: emptyList()
            val clientPicks = storedPicks.forClient(clientId)

            when (section) {
                CookSection.RECIPES -> RecipeList(
                    recipes = library.recipes,
                    columns = AdaptiveLayout.cardColumns(available),
                    onAdd = { editingId = Recipe(name = "").id },
                    onEdit = { editingId = it.id },
                    onDelete = { confirmingDelete = it }
                )

                CookSection.PLAN -> PlanList(
                    clients = clients,
                    selectedClientId = clientId,
                    onPickClient = { planClientId = it },
                    clientName = clientName,
                    meals = weekMeals,
                    recipesById = recipesById,
                    canPlan = clientId != null && library.recipes.isNotEmpty(),
                    roadPickCount = clientPicks.size,
                    dayColumns = AdaptiveLayout.planDayColumns(available),
                    buttonColumns = AdaptiveLayout.cardColumns(available),
                    onAdd = { recipe ->
                        cook.upsertMeal(
                            PlannedMeal(
                                recipeId = recipe.id,
                                clientId = clientId,
                                dayKey = com.dugcanlift.kit.DayKey.today(),
                                recipeName = recipe.name,
                                snapshotNutrition = recipe.nutritionPerServing,
                                snapshotNutritionUnknownKeys = recipe.nutritionUnknownKeys
                            )
                        )
                        revision++
                    },
                    recipes = library.recipes,
                    onRemove = { cook.deleteMeal(it.id); revision++ },
                    onSend = {
                        // The client's own id is the plan's `l`: the decoder
                        // refuses a fragment addressed to anyone else, which is
                        // what stops one client opening another's week.
                        val coachName = context
                            .getSharedPreferences("connect", android.content.Context.MODE_PRIVATE)
                            .getString("coachName", "") ?: ""
                        // Road picks ride in the same link; nothing is filtered
                        // against this app's copy of road-food.json on the way
                        // out. See RoadPicks.
                        val payload = CookPlanEncoder.payload(
                            weekMeals, recipesById, clientId.orEmpty(),
                            coachName.ifBlank { "Your coach" }, clientPicks
                        )
                        val fragment = PlanEnvelope.fragment(payload)
                        // What was sent, kept. Train's Send has filed one since the Booked card
                        // shipped and Cook's did not, because nothing then read a payload's meals;
                        // a food plan that books nobody's day still has to be a record now that
                        // the card puts a booked dinner beside the log. Filed when the chooser
                        // opens, which is the last moment this app can see, and off the main
                        // thread because it is a read-modify-write of a file.
                        if (!clientId.isNullOrEmpty()) {
                            val row = SentPlan(
                                id = java.util.UUID.randomUUID().toString(),
                                clientId = clientId,
                                sentAt = System.currentTimeMillis() / 1000,
                                payloadHash = SentPlans.hash(payload),
                                payloadJson = payload.toString()
                            )
                            scope.launch(Dispatchers.IO) {
                                // A record that cannot be written must never stop a plan being
                                // sent, and must never be written over an unreadable file.
                                runCatching { SentPlanRepository(context.filesDir).record(row) }
                            }
                        }
                        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(
                                android.content.Intent.EXTRA_TEXT,
                                "Here's your week.\n\nhttps://www.dugcanlift.com/lift/#$fragment"
                            )
                        }
                        context.startActivity(
                            android.content.Intent.createChooser(send, "Send this week")
                        )
                    }
                )

                CookSection.SHOPPING -> ShoppingList(
                    lines = CoachShoppingList.build(weekMeals, recipesById),
                    columns = AdaptiveLayout.shoppingColumns(available)
                )

                CookSection.ROAD -> RoadList(
                    clients = clients,
                    selectedClientId = clientId,
                    onPickClient = { planClientId = it },
                    clientName = clientName,
                    data = roadData,
                    loadError = roadError,
                    unreadable = storedPicks.isUnreadable,
                    picks = clientPicks,
                    open = roadOpen,
                    onSetPicks = { ids ->
                        pickStore.setForClient(clientId.orEmpty(), ids)
                        pickRevision++
                    },
                    onClearAll = { confirmingClearPicks = true }
                )
            }
        }
      }
    }

    editing?.let { recipe ->
        RecipeEditor(
            recipe = recipe,
            onCancel = { editingId = null },
            onSave = {
                cook.upsertRecipe(it)
                editingId = null
                revision++
                scope.launch { snackbar.showSnackbar("Saved ${it.name}.") }
            }
        )
    }

    if (confirmingClearPicks) {
        val name = clients.firstOrNull { it.id == planClientId }?.name ?: "this client"
        AlertDialog(
            onDismissRequest = { confirmingClearPicks = false },
            title = { Text("Clear every road pick for $name?") },
            text = {
                Text(
                    "Their picks stop going in the plan link. A link you already sent is " +
                        "unaffected — it left as a link, and clearing them here does not reach " +
                        "the copy on their phone."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    planClientId?.let { pickStore.setForClient(it, emptyList()) }
                    confirmingClearPicks = false
                    pickRevision++
                }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { confirmingClearPicks = false }) { Text("Keep") } }
        )
    }

    confirmingDelete?.let { recipe ->
        val planned = library.meals.count { it.recipeId == recipe.id }
        AlertDialog(
            onDismissRequest = { confirmingDelete = null },
            title = { Text("Delete ${recipe.name}?") },
            text = {
                Text(
                    if (planned > 0)
                        "This also removes $planned planned meal(s) built from it. " +
                            "A week already sent to a client is unaffected — it left as a link."
                    else "This cannot be undone."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    cook.deleteRecipe(recipe.id)
                    confirmingDelete = null
                    revision++
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmingDelete = null }) { Text("Keep") } }
        )
    }
}

@Composable
private fun RecipeList(
    recipes: List<Recipe>,
    columns: Int,
    onAdd: () -> Unit,
    onEdit: (Recipe) -> Unit,
    onDelete: (Recipe) -> Unit
) {
    Button(onClick = onAdd, modifier = Modifier.wideButton(columns)) { Text("Write a recipe") }
    Spacer(Modifier.height(12.dp))

    if (recipes.isEmpty()) {
        Text(
            "No recipes yet. Write the ones you actually give clients — a week is built " +
                "from these, and so is the shopping list.",
            style = MaterialTheme.typography.bodyMedium
        )
        return
    }

    val card: @Composable (Recipe, Modifier) -> Unit = { recipe, modifier ->
        Card(modifier.fillMaxWidth(), border = dclCardBorder()) {
            Column(Modifier.padding(12.dp)) {
                Text(recipe.name.ifBlank { "Untitled" }, style = MaterialTheme.typography.titleMedium)
                Text(
                    "Serves ${recipe.servings.trimZeros()} · " +
                        "${recipe.rawIngredients.size} ingredient(s)" +
                        (recipe.nutritionPerServing?.takeIf { it.hasMacros }?.let { " · ${it.calories.toInt()} kcal/serving" } ?: ""),
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onEdit(recipe) }) { Text("Edit") }
                    TextButton(onClick = { onDelete(recipe) }) { Text("Delete") }
                }
            }
        }
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (columns == 1) {
            items(recipes, key = { it.id }) { recipe -> card(recipe, Modifier) }
        } else {
            items(rowMajor(recipes, columns), key = { row -> row.first().id }) { row ->
                GridRow(row, columns) { recipe -> card(recipe, Modifier.fillMaxHeight()) }
            }
        }
    }
}

/** Full width on a phone; in a wide layout a primary button stops being a banner. */
internal fun Modifier.wideButton(columns: Int): Modifier =
    if (columns == 1) fillMaxWidth()
    else widthIn(max = AdaptiveLayout.MAX_WIDE_BUTTON_DP.dp).fillMaxWidth()

@Composable
private fun PlanList(
    clients: List<Client>,
    selectedClientId: String?,
    onPickClient: (String) -> Unit,
    clientName: String?,
    meals: List<PlannedMeal>,
    recipesById: Map<String, Recipe>,
    recipes: List<Recipe>,
    canPlan: Boolean,
    /** How many road picks this client has. They travel in the same link. */
    roadPickCount: Int,
    dayColumns: Int,
    buttonColumns: Int,
    onAdd: (Recipe) -> Unit,
    onRemove: (PlannedMeal) -> Unit,
    onSend: () -> Unit
) {
    if (clients.isEmpty()) {
        Text(
            "No clients yet. A week is planned for someone, so import a client " +
                "from the roster first — the recipes above are yours either way.",
            style = MaterialTheme.typography.bodyMedium
        )
        return
    }

    // Which client this week is for. A meal carries its own clientId, so
    // switching here shows a different week rather than re-assigning this one.
    Text("Planning for", style = MaterialTheme.typography.labelMedium)
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
        Text("Pick a client to plan for.", style = MaterialTheme.typography.bodyMedium)
        return
    }

    // Road picks travel in the same link, so a coach whose only answer this
    // week is "these are fine on the road" still has something to send.
    if (meals.isNotEmpty() || roadPickCount > 0) {
        Button(onClick = onSend, modifier = Modifier.wideButton(dayColumns)) {
            Text(
                if (meals.isEmpty())
                    "Send ${roadPickCount} road pick${if (roadPickCount == 1) "" else "s"} to $clientName"
                else "Send this week to $clientName"
            )
        }
        if (meals.isNotEmpty() && roadPickCount > 0) {
            Text(
                "The $roadPickCount road pick${if (roadPickCount == 1) "" else "s"} you marked " +
                    "for $clientName go in the same link.",
                style = MaterialTheme.typography.bodySmall
            )
        }
        Spacer(Modifier.height(12.dp))
    }

    val mealCard: @Composable (PlannedMeal) -> Unit = { meal ->
        Card(Modifier.fillMaxWidth(), border = dclCardBorder()) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    meal.recipeName.ifBlank { recipesById[meal.recipeId]?.name ?: "Deleted recipe" },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${meal.dayKey} · ${meal.meal} · ${meal.servings.trimZeros()} serving(s)",
                    style = MaterialTheme.typography.bodySmall
                )
                TextButton(onClick = { onRemove(meal) }) { Text("Remove") }
            }
        }
    }

    // The week as days side by side when there is room; a phone keeps its single list.
    val days = remember(meals) { meals.groupBy { it.dayKey }.toSortedMap().toList() }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (dayColumns == 1) {
            items(meals, key = { it.id }) { meal -> mealCard(meal) }
        } else {
            items(rowMajor(days, dayColumns), key = { row -> "days-${row.first().first}" }) { row ->
                GridRow(row, dayColumns) { (dayKey, dayMeals) ->
                    Card(Modifier.fillMaxWidth().fillMaxHeight(), border = dclCardBorder()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(formatShortDay(dayKey), style = MaterialTheme.typography.titleSmall)
                            Text(dayKey, style = MaterialTheme.typography.bodySmall)
                            dayMeals.forEach { meal ->
                                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                                Text(
                                    meal.recipeName.ifBlank { recipesById[meal.recipeId]?.name ?: "Deleted recipe" },
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    "${meal.meal} · ${meal.servings.trimZeros()} serving(s)",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                TextButton(onClick = { onRemove(meal) }) { Text("Remove") }
                            }
                        }
                    }
                }
            }
        }

        if (canPlan) {
            item { HorizontalDivider() }
            item { Text("Add to the week", style = MaterialTheme.typography.titleSmall) }
            if (buttonColumns == 1) {
                items(recipes, key = { "add-${it.id}" }) { recipe ->
                    OutlinedButton(onClick = { onAdd(recipe) }, modifier = Modifier.fillMaxWidth()) {
                        Text(recipe.name.ifBlank { "Untitled" })
                    }
                }
            } else {
                items(rowMajor(recipes, buttonColumns), key = { row -> "add-${row.first().id}" }) { row ->
                    GridRow(row, buttonColumns) { recipe ->
                        OutlinedButton(onClick = { onAdd(recipe) }, modifier = Modifier.fillMaxWidth()) {
                            Text(recipe.name.ifBlank { "Untitled" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        } else if (recipes.isEmpty()) {
            item {
                Text(
                    if (roadPickCount > 0)
                        "No recipes yet — a week is built from them. The road picks you marked " +
                            "still go in the link."
                    else "Write a recipe first — a week is built from them.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

/* ---------------- road picks ----------------
 *
 * The items a coach is happy with at the places a client stops on the road.
 *
 * In Cook, beside the week and the shopping list, rather than on the client's
 * page: that page is a record of what a client did, and this is something the
 * coach makes for them -- addressed to one person and sent in the same plan
 * link as the rest of Cook. Coach web puts it in the same place for the same
 * reason.
 *
 * A pick says "this fits how I want you eating on the road" and nothing about
 * calories or macros: LIFT already ranks Road Food against what is left of the
 * client's day, and a pick floats to the top of that list, labelled, without
 * re-ranking the numbers underneath or hiding anything that fits. Nothing here
 * or there judges what a client ate against what was picked.
 */
@Composable
private fun RoadList(
    clients: List<Client>,
    selectedClientId: String?,
    onPickClient: (String) -> Unit,
    clientName: String?,
    data: RoadFoodData?,
    loadError: String?,
    unreadable: Boolean,
    picks: List<String>,
    open: MutableMap<String, Boolean>,
    onSetPicks: (List<String>) -> Unit,
    onClearAll: () -> Unit
) {
    if (clients.isEmpty()) {
        Text(
            "No clients yet. Picks are made for one person, so import a client from the " +
                "roster first.",
            style = MaterialTheme.typography.bodyMedium
        )
        return
    }
    if (unreadable) {
        // The same distinction StoredRoadPicks exists to make: this is not "no
        // picks", and offering a tick box here would invite saving over what is
        // still on disk for every other client.
        Text(
            "This device's road picks can't be read. Nothing has been deleted — restore a " +
                "backup from Connect before ticking anything new.",
            style = MaterialTheme.typography.bodyMedium
        )
        return
    }

    Text("Picking for", style = MaterialTheme.typography.labelMedium)
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
        Text("Pick a client to mark picks for.", style = MaterialTheme.typography.bodyMedium)
        return
    }

    Text(
        "Marked here, sent with the plan link. In LIFT they sit at the top of that place's " +
            "list, named as yours. The ranking underneath is unchanged, and nothing that fits " +
            "is hidden.",
        style = MaterialTheme.typography.bodyMedium
    )
    Spacer(Modifier.height(12.dp))

    if (data == null) {
        Text(
            if (loadError != null)
                "The Road Food list could not load. It is part of the app, so this is a bad " +
                    "install rather than a connection ($loadError)."
            else "Loading the Road Food list...",
            style = MaterialTheme.typography.bodyMedium
        )
        return
    }

    val summary = RoadPicks.summary(picks, data)
    val missing = RoadPicks.missing(picks, data)

    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item(key = "road-summary") {
            Card(Modifier.fillMaxWidth(), border = dclCardBorder()) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        if (summary.isEmpty()) "Nothing picked for $clientName yet."
                        else "$summary picked for $clientName.",
                        style = MaterialTheme.typography.titleSmall
                    )
                    if (missing.isNotEmpty()) {
                        // A pick for an item this copy of the file no longer
                        // lists. It still travels: the client's app knows what
                        // its own menus hold, and skips what it cannot find
                        // rather than drawing a broken row.
                        Text(
                            "${missing.size} more ${if (missing.size == 1) "pick is" else "picks are"} " +
                                "not on the menus this copy has. They still travel; an app skips " +
                                "what it cannot find.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    if (picks.isNotEmpty()) {
                        TextButton(onClick = onClearAll) { Text("Clear these picks") }
                    }
                }
            }
        }

        data.chains.forEach { chain ->
            roadPlace(chain.id, chain.name, chain.items, picks, open, onSetPicks, showCategory = false)
        }
        if (data.snacks.isNotEmpty()) {
            val snacks = data.snacks.sortedWith(
                compareBy({ it.category.orEmpty() }, { it.name })
            )
            roadPlace(
                RoadPicks.SNACKS_PLACE_ID, RoadPicks.SNACKS_PLACE_NAME, snacks,
                picks, open, onSetPicks, showCategory = true
            )
        }
    }
}

/**
 * One place: a card that folds open to its items, each a tick. Folded by
 * default -- eight chains and twenty-two snacks is a scroll nobody asked for.
 */
private fun androidx.compose.foundation.lazy.LazyListScope.roadPlace(
    placeId: String,
    name: String,
    items: List<RoadFoodItem>,
    picks: List<String>,
    open: MutableMap<String, Boolean>,
    onSetPicks: (List<String>) -> Unit,
    showCategory: Boolean
) {
    item(key = "road-place-$placeId") {
        val isOpen = open[placeId] == true
        val picked = RoadPicks.countIn(picks, items)
        Card(Modifier.fillMaxWidth(), border = dclCardBorder()) {
            Column(Modifier.padding(12.dp)) {
                Row(
                    Modifier.fillMaxWidth().clickable { open[placeId] = !isOpen },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(name, style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (picked > 0) "$picked of ${items.size} picked" else "${items.size} items",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (!isOpen) return@Column

                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onSetPicks(RoadPicks.toggleAll(picks, items, true)) }) {
                        Text("Pick all")
                    }
                    TextButton(
                        enabled = picked > 0,
                        onClick = { onSetPicks(RoadPicks.toggleAll(picks, items, false)) }
                    ) { Text("Clear") }
                }
                items.forEach { item ->
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    RoadPickRow(
                        item = item,
                        checked = item.id in picks,
                        showCategory = showCategory,
                        onToggle = { on -> onSetPicks(RoadPicks.toggle(picks, item.id, on)) }
                    )
                }
            }
        }
    }
}

/**
 * One item: the tick, the name, and the figures plainly. No colour, no
 * threshold, nothing ranked -- the ranking is the client's app's job, against
 * a day this screen knows nothing about.
 */
@Composable
private fun RoadPickRow(
    item: RoadFoodItem,
    checked: Boolean,
    showCategory: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Row(
        Modifier.fillMaxWidth().clickable { onToggle(!checked) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = onToggle)
        Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
            Text(item.name, style = MaterialTheme.typography.bodyMedium)
            // Blank stays blank: an item with no figure says so rather than showing 0.
            val bits = buildList {
                if (showCategory) item.category?.let { add(it) }
                add(item.kcal?.let { "${Math.round(it)} kcal" } ?: "kcal not listed")
                add(item.proteinG?.let { "P ${Math.round(it)} g" } ?: "protein not listed")
                item.serving?.let { add(it) }
            }
            Text(bits.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
            item.modification?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun ShoppingList(lines: List<com.dugcanlift.coach.data.ShoppingLine>, columns: Int = 1) {
    if (lines.isEmpty()) {
        Text("Nothing planned yet, so there is nothing to buy.",
             style = MaterialTheme.typography.bodyMedium)
        return
    }
    val line: @Composable (com.dugcanlift.coach.data.ShoppingLine, Modifier) -> Unit = { line, modifier ->
        Text(shoppingLineText(line), style = MaterialTheme.typography.bodyMedium, modifier = modifier)
    }
    // Two columns of short lines drift apart across a whole tablet; keep them within reading distance.
    val listModifier = if (columns == 1) Modifier else Modifier.widthIn(max = AdaptiveLayout.MAX_FORM_DP.dp * 1.25f)
    LazyColumn(listModifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (columns == 1) {
            items(lines, key = { it.name }) { line(it, Modifier) }
        } else {
            // Read down each column, as a list is, rather than across.
            items(columnMajor(lines, columns), key = { row -> "shop-${row.first().name}" }) { row ->
                GridRow(row, columns, gap = 24f) { line(it, Modifier) }
            }
        }
    }
}

/**
 * "lean beef mince — 450 g", "peppers — 3". The count sentinel ([IngredientParser.COUNT_UNIT])
 * keeps "3 peppers" apart from any real unit when adding up, but it is not a unit and never reaches
 * the screen. It used to, as an invisible NUL followed by "count" -- which also made
 * `uiautomator dump` crash on this screen.
 */
internal fun shoppingLineText(line: com.dugcanlift.coach.data.ShoppingLine): String {
    val amounts = line.amounts.entries
        .joinToString(" + ") { (unit, value) ->
            val shownUnit = if (unit == IngredientParser.COUNT_UNIT) "" else unit
            listOf(value.trimZeros(), shownUnit).filter { it.isNotBlank() }.joinToString(" ")
        }
    // An unreadable quantity still earns a line. "a pinch of salt" is on
    // the list; the parser not knowing how much is not a reason to shop
    // for none of it.
    val suffix = if (line.unquantified > 0) {
        if (amounts.isBlank()) "as needed" else "$amounts + as needed"
    } else amounts
    return "${line.name} — $suffix"
}

@Composable
private fun RecipeEditor(recipe: Recipe, onCancel: () -> Unit, onSave: (Recipe) -> Unit) {
    var name by rememberSaveable(recipe.id) { mutableStateOf(recipe.name) }
    var servings by rememberSaveable(recipe.id) { mutableStateOf(recipe.servings.trimZeros()) }
    // One line per ingredient, exactly as typed. The parser reads these for the
    // shopping list, but what the coach wrote is what is stored -- a re-save
    // must never launder "a pinch" into nothing.
    var ingredients by rememberSaveable(recipe.id) { mutableStateOf(recipe.rawIngredients.joinToString("\n")) }
    var steps by rememberSaveable(recipe.id) { mutableStateOf(recipe.steps.joinToString("\n")) }

    // Macros. Blank rather than "0" when the recipe carries none, because the
    // whole point below is that an untouched field must not become a measured
    // zero on a client's phone.
    val macros = recipe.nutritionPerServing
    var calories by rememberSaveable(recipe.id) { mutableStateOf(macroFieldText(macros) { it.calories }) }
    var protein by rememberSaveable(recipe.id) { mutableStateOf(macroFieldText(macros) { it.proteinG }) }
    var carbs by rememberSaveable(recipe.id) { mutableStateOf(macroFieldText(macros) { it.carbsG }) }
    var fat by rememberSaveable(recipe.id) { mutableStateOf(macroFieldText(macros) { it.fatG }) }
    var fiber by rememberSaveable(recipe.id) { mutableStateOf(macroFieldText(macros) { it.fiberG }) }
    // Saturated fat, sugar, sodium: each optional on its own, blank when unknown.
    var saturatedFat by rememberSaveable(recipe.id) { mutableStateOf(macros?.saturatedFatG?.trimZeros() ?: "") }
    var sugar by rememberSaveable(recipe.id) { mutableStateOf(macros?.sugarG?.trimZeros() ?: "") }
    var sodium by rememberSaveable(recipe.id) { mutableStateOf(macros?.sodiumMg?.trimZeros() ?: "") }

    // Weight. The unit is the coach's own display preference, remembered
    // across recipes; the model is always grams.
    val context = LocalContext.current
    val weightPrefs = remember { context.getSharedPreferences("cook", android.content.Context.MODE_PRIVATE) }
    var weightUnit by remember { mutableStateOf(RecipeWeightUnit.fromKey(weightPrefs.getString("recipeWeightUnit", null))) }
    var totalWeight by remember(recipe.id) {
        mutableStateOf(recipe.totalWeightGrams?.let { roundOne(weightUnit.fromGrams(it)).trimZeros() } ?: "")
    }

    // Paste a recipe written out as text -- a video caption, an email, a card
    // off the fridge. Offered only on a new recipe: pasting over one that
    // exists would replace the coach's work rather than start from it.
    val isNew = recipe.name.isBlank()
    var pasting by rememberSaveable(recipe.id) { mutableStateOf(false) }
    var pasteText by rememberSaveable(recipe.id) { mutableStateOf("") }
    var splitAdvice by remember(recipe.id) { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(if (recipe.name.isBlank()) "New recipe" else "Edit recipe") },
        text = {
            // Scrolls. It did not, which was harmless with four fields and
            // unusable with the macro section: on a phone the lower fields sat
            // below the dialog's edge with no way to reach them. LIFT Android's
            // recipe dialog scrolls the same way.
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                // `CaptionRecipe` only PROPOSES a split. It fills the fields
                // below and the coach checks them before saving -- so a wrong
                // split costs an edit, never a number. The parser still reads
                // the quantities on save and still refuses to weigh a volume.
                if (isNew) {
                    if (!pasting) {
                        TextButton(onClick = { pasting = true }) { Text("Paste a recipe") }
                    } else {
                        OutlinedTextField(
                            value = pasteText,
                            onValueChange = { pasteText = it },
                            label = { Text("Paste the recipe's text") }
                        )
                        Row {
                            TextButton(
                                enabled = pasteText.isNotBlank(),
                                onClick = {
                                    val parsed = CaptionRecipe.parse(pasteText)
                                    if (parsed.isEmpty) {
                                        splitAdvice = "Nothing in that reads as a recipe. " +
                                            "Paste the ingredients and steps as text."
                                    } else {
                                        name = parsed.name.orEmpty()
                                        ingredients = parsed.ingredientLines.joinToString("\n")
                                        steps = parsed.steps.joinToString("\n")
                                        // Only ever from an explicit "serves 4".
                                        // A guessed yield silently divides every
                                        // macro by a number nobody chose.
                                        parsed.servings?.let { servings = it.trimZeros() }
                                        splitAdvice = when (parsed.split) {
                                            Split.LABELLED ->
                                                "Split on the headings in the text \u2014 check it read them right."
                                            Split.INFERRED ->
                                                "The text labelled one section and this worked out the rest, " +
                                                    "so check the division."
                                            Split.UNSORTED ->
                                                "The text had no headings, so everything landed in Ingredients " +
                                                    "\u2014 cut any steps out and paste them below."
                                        } + if (parsed.servings == null) {
                                            " It didn't say how many this serves; set it below."
                                        } else ""
                                        pasting = false
                                        pasteText = ""
                                    }
                                }
                            ) { Text("Read it") }
                            TextButton(onClick = { pasting = false; pasteText = "" }) { Text("Cancel") }
                        }
                    }
                    splitAdvice?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.height(8.dp))
                }

                OutlinedTextField(value = name, onValueChange = { name = it },
                                  label = { Text("Name") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = servings, onValueChange = { servings = it },
                                  label = { Text("Serves") }, singleLine = true)

                Spacer(Modifier.height(12.dp))
                Text("Total weight of the finished dish", style = MaterialTheme.typography.labelLarge)
                Row {
                    RecipeWeightUnit.entries.forEach { unit ->
                        TextButton(
                            onClick = {
                                if (unit == weightUnit) return@TextButton
                                // Convert through grams, never relabel. Switching
                                // units must keep the mass the coach entered --
                                // relabelling turned 1200 g into 1200 oz on iOS
                                // before this rule existed there.
                                totalWeight = reweigh(totalWeight, weightUnit, unit)
                                weightUnit = unit
                                weightPrefs.edit().putString("recipeWeightUnit", unit.name).apply()
                            }
                        ) {
                            Text(if (unit == weightUnit) "\u2713 ${unit.label}" else unit.label)
                        }
                    }
                }
                OutlinedTextField(
                    value = totalWeight,
                    onValueChange = { totalWeight = it },
                    label = { Text("Total weight (${weightUnit.abbreviation})") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                perServingWeight(totalWeight, servings, weightUnit)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    "Optional. With it, a serving has a weight a client can put on a scale, " +
                        "which a count never gives them.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = ingredients, onValueChange = { ingredients = it },
                                  label = { Text("Ingredients, one per line") })
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = steps, onValueChange = { steps = it },
                                  label = { Text("Steps, one per line") })

                // Macros were missing here entirely. `Recipe.nutritionPerServing`
                // has always existed, the JSON codec round-trips all five values
                // and `CookPlanEncoder` already sends them as `u` -- so a recipe
                // that arrived with macros displayed and forwarded them
                // correctly, and a coach simply had no way to enter or correct
                // any of it on this device.
                Spacer(Modifier.height(16.dp))
                Text("Macros, per serving", style = MaterialTheme.typography.labelLarge)
                Text(
                    "Leave blank if you don't know them. Blank stays unknown — it " +
                        "will not log as zero on your client's phone.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(8.dp))
                MacroField("Calories", "kcal", calories) { calories = it }
                MacroField("Protein", "g", protein) { protein = it }
                MacroField("Carbs", "g", carbs) { carbs = it }
                MacroField("Fat", "g", fat) { fat = it }
                MacroField("Fibre", "g", fiber) { fiber = it }

                Spacer(Modifier.height(8.dp))
                Text("Also per serving", style = MaterialTheme.typography.labelLarge)
                Text(
                    "Optional, and tracked only \u2014 there is no target for these. " +
                        "Each one you leave blank stays unknown.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(8.dp))
                MacroField("Saturated fat", "g", saturatedFat) { saturatedFat = it }
                MacroField("Sugar", "g", sugar) { sugar = it }
                MacroField("Sodium", "mg", sodium) { sodium = it }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    onSave(
                        recipe.copy(
                            name = name.trim(),
                            servings = servings.toDoubleOrNull() ?: recipe.servings,
                            rawIngredients = ingredients.lines().map { it.trim() }.filter { it.isNotEmpty() },
                            steps = steps.lines().map { it.trim() }.filter { it.isNotEmpty() },
                            nutritionPerServing = enteredMacros(
                                calories, protein, carbs, fat, fiber, recipe.nutritionPerServing,
                                saturatedFat = saturatedFat, sugar = sugar, sodium = sodium
                            ),
                            totalWeightGrams = enteredWeightGrams(totalWeight, weightUnit)
                        )
                    )
                }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } }
    )
}


/** One labelled macro row. The unit is shown because kcal and grams are not
 *  the same thing and a column of bare numbers does not say which is which. */
@Composable
private fun MacroField(
    label: String,
    unit: String,
    value: String,
    onValueChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text("$label ($unit)") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(8.dp))
}

/**
 * The macro rule, kept out of the composable so it can be tested.
 *
 * Null unless something was actually typed: an untouched form must never write
 * zeros, because a zero here becomes a zero-calorie dinner in a client's day
 * total. This is the rule `MacroFields.entered` holds on iOS and the web app's
 * recipe form holds in `app.js`.
 *
 * **Fibre cannot say "unknown" here, and that is a model limit rather than a
 * choice.** [RecipeNutrition]'s fields are non-null `Double`s, so a blank fibre
 * field with real calories stores 0.0 and ships `u[4] = 0`. iOS can hold
 * `fiberG` as nil locally; the wire cannot -- PLAN-FORMAT's `u` is five plain
 * numbers -- so every client already sends 0 for unknown fibre. Widening
 * [RecipeNutrition] would be a data-shape change reaching both shipped Android
 * apps and would still not change what travels.
 *
 * `estimated` is carried from the existing figure rather than reset: a coach
 * correcting one number on an imported recipe has not turned it into a
 * measurement.
 */
internal fun enteredMacros(
    calories: String,
    protein: String,
    carbs: String,
    fat: String,
    fiber: String,
    existing: RecipeNutrition?,
    saturatedFat: String = "",
    sugar: String = "",
    sodium: String = ""
): RecipeNutrition? {
    val values = listOf(calories, protein, carbs, fat, fiber).map { it.trim().toDoubleOrNull() }
    // Saturated fat, sugar and sodium are each nullable, so unlike fibre above
    // blank really can stay unknown. Negative or non-finite is not a reading.
    val details = listOf(saturatedFat, sugar, sodium)
        .map { it.trim().toDoubleOrNull()?.takeIf { v -> v.isFinite() && v >= 0 } }
    if (values.all { it == null } && details.all { it == null }) return null
    // Only details typed: the five macros hold placeholder zeros that
    // `hasMacros` reads as "not entered", so `u` is not sent and the fields
    // reopen blank. The shape Coach iOS's NutritionFacts has for the same case.
    return RecipeNutrition(
        calories = values[0] ?: 0.0,
        proteinG = values[1] ?: 0.0,
        carbsG = values[2] ?: 0.0,
        fatG = values[3] ?: 0.0,
        fiberG = values[4] ?: 0.0,
        estimated = existing?.estimated ?: false,
        saturatedFatG = details[0],
        sugarG = details[1],
        sodiumMg = details[2]
    )
}

/**
 * The text a macro field opens with: blank when the recipe has no macros at all
 * -- no nutrition, or nutrition holding only saturated fat, sugar or sodium,
 * whose five macros are placeholders (see `hasMacros`). Showing those as "0"
 * would put a zero in front of the coach that the next Save turns into fact.
 */
internal fun macroFieldText(nutrition: RecipeNutrition?, pick: (RecipeNutrition) -> Double): String =
    nutrition?.takeIf { it.hasMacros }?.let { pick(it).trimZeros() } ?: ""


private fun roundOne(value: Double): Double = Math.round(value * 10) / 10.0

/**
 * The typed weight in grams, or null when blank or not a positive number. A
 * recipe without a weight keeps planning by servings, so blank is a real
 * answer, not a zero.
 */
internal fun enteredWeightGrams(text: String, unit: RecipeWeightUnit): Double? =
    text.trim().toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }?.let { unit.toGrams(it) }

/** Rewrites a displayed weight from one unit into another, through grams. */
internal fun reweigh(text: String, from: RecipeWeightUnit, to: RecipeWeightUnit): String {
    val grams = enteredWeightGrams(text, from) ?: return text
    return roundOne(to.fromGrams(grams)).trimZeros()
}

/** "4 servings · 300 g each", when both numbers are known. */
internal fun perServingWeight(totalText: String, servingsText: String, unit: RecipeWeightUnit): String? {
    val total = totalText.trim().toDoubleOrNull()?.takeIf { it > 0 } ?: return null
    val count = servingsText.trim().toDoubleOrNull()?.takeIf { it > 0 } ?: return null
    val each = roundOne(total / count).trimZeros()
    val servingsWord = if (count == 1.0) "serving" else "servings"
    return "${count.trimZeros()} $servingsWord \u00B7 $each ${unit.abbreviation} each"
}
