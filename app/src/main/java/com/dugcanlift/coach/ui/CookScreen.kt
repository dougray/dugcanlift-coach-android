package com.dugcanlift.coach.ui

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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.dugcanlift.coach.data.Client
import com.dugcanlift.coach.data.ClientRepository
import com.dugcanlift.coach.data.CoachShoppingList
import com.dugcanlift.coach.data.CookPlanEncoder
import com.dugcanlift.coach.data.CookRepository
import com.dugcanlift.coach.data.PlannedMeal
import com.dugcanlift.coach.data.Recipe
import com.dugcanlift.coach.data.forClient
import com.dugcanlift.coach.data.hasMacros
import com.dugcanlift.kit.CaptionRecipe
import com.dugcanlift.kit.IngredientParser
import com.dugcanlift.kit.Split
import com.dugcanlift.coach.data.RecipeWeightUnit
import com.dugcanlift.kit.RecipeNutrition
import com.dugcanlift.kit.trimZeros
import kotlinx.coroutines.launch

/**
 * COOK for Coach: the recipes a coach writes, the week they build for one
 * client, and the shopping list that falls out of it.
 *
 * Three sections behind one chip row rather than three routes, matching Coach
 * iOS's `CookView`. The week and the shopping list are two views of the same
 * planned meals, and a coach moves between them constantly while planning.
 */
private enum class CookSection(val label: String) {
    RECIPES("Recipes"), PLAN("Plan"), SHOPPING("Shopping")
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
                        val fragment = CookPlanEncoder.encode(
                            weekMeals, recipesById, clientId.orEmpty(),
                            coachName.ifBlank { "Your coach" }
                        )
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

    if (meals.isNotEmpty()) {
        Button(onClick = onSend, modifier = Modifier.wideButton(dayColumns)) {
            Text("Send this week to $clientName")
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
                Text("Write a recipe first — a week is built from them.",
                     style = MaterialTheme.typography.bodyMedium)
            }
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
    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
