package com.dugcanlift.coach.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.unit.dp
import com.dugcanlift.coach.data.CoachShoppingList
import com.dugcanlift.coach.data.CookPlanEncoder
import com.dugcanlift.coach.data.CookRepository
import com.dugcanlift.coach.data.PlannedMeal
import com.dugcanlift.coach.data.Recipe
import com.dugcanlift.coach.data.forClient
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
    RECIPES("Recipes"), WEEK("Week"), SHOPPING("Shopping")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CookScreen(
    clientId: String?,
    clientName: String?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val cook = remember { CookRepository(context.filesDir) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    // Bumped after every write so the whole screen re-reads. The library is one
    // small file and every section shows a different slice of it; a
    // finer-grained store would be more machinery than the data justifies.
    var revision by remember { mutableStateOf(0) }
    val library = remember(revision) { cook.load() }

    var section by remember { mutableStateOf(CookSection.RECIPES) }
    var editing by remember { mutableStateOf<Recipe?>(null) }
    var confirmingDelete by remember { mutableStateOf<Recipe?>(null) }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(if (clientName != null) "Cook — $clientName" else "Cook") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).padding(horizontal = 16.dp)) {

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
            val weekMeals = clientId?.let { library.meals.forClient(it) } ?: emptyList()

            when (section) {
                CookSection.RECIPES -> RecipeList(
                    recipes = library.recipes,
                    onAdd = { editing = Recipe(name = "") },
                    onEdit = { editing = it },
                    onDelete = { confirmingDelete = it }
                )

                CookSection.WEEK -> WeekList(
                    clientName = clientName,
                    meals = weekMeals,
                    recipesById = recipesById,
                    canPlan = clientId != null && library.recipes.isNotEmpty(),
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
                    lines = CoachShoppingList.build(weekMeals, recipesById)
                )
            }
        }
    }

    editing?.let { recipe ->
        RecipeEditor(
            recipe = recipe,
            onCancel = { editing = null },
            onSave = {
                cook.upsertRecipe(it)
                editing = null
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
    onAdd: () -> Unit,
    onEdit: (Recipe) -> Unit,
    onDelete: (Recipe) -> Unit
) {
    Button(onClick = onAdd, modifier = Modifier.fillMaxWidth()) { Text("Write a recipe") }
    Spacer(Modifier.height(12.dp))

    if (recipes.isEmpty()) {
        Text(
            "No recipes yet. Write the ones you actually give clients — a week is built " +
                "from these, and so is the shopping list.",
            style = MaterialTheme.typography.bodyMedium
        )
        return
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(recipes, key = { it.id }) { recipe ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(recipe.name.ifBlank { "Untitled" }, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Serves ${recipe.servings.trimZeros()} · " +
                            "${recipe.rawIngredients.size} ingredient(s)" +
                            (recipe.nutritionPerServing?.let { " · ${it.calories.toInt()} kcal/serving" } ?: ""),
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
    }
}

@Composable
private fun WeekList(
    clientName: String?,
    meals: List<PlannedMeal>,
    recipesById: Map<String, Recipe>,
    recipes: List<Recipe>,
    canPlan: Boolean,
    onAdd: (Recipe) -> Unit,
    onRemove: (PlannedMeal) -> Unit,
    onSend: () -> Unit
) {
    if (clientName == null) {
        Text(
            "Open a client first. A week is planned for someone — there is no " +
                "such thing as a week belonging to nobody.",
            style = MaterialTheme.typography.bodyMedium
        )
        return
    }

    if (meals.isNotEmpty()) {
        Button(onClick = onSend, modifier = Modifier.fillMaxWidth()) {
            Text("Send this week to $clientName")
        }
        Spacer(Modifier.height(12.dp))
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(meals, key = { it.id }) { meal ->
            Card(Modifier.fillMaxWidth()) {
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

        if (canPlan) {
            item { HorizontalDivider() }
            item { Text("Add to the week", style = MaterialTheme.typography.titleSmall) }
            items(recipes, key = { "add-${it.id}" }) { recipe ->
                OutlinedButton(onClick = { onAdd(recipe) }, modifier = Modifier.fillMaxWidth()) {
                    Text(recipe.name.ifBlank { "Untitled" })
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
private fun ShoppingList(lines: List<com.dugcanlift.coach.data.ShoppingLine>) {
    if (lines.isEmpty()) {
        Text("Nothing planned yet, so there is nothing to buy.",
             style = MaterialTheme.typography.bodyMedium)
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items(lines, key = { it.name }) { line ->
            val amounts = line.amounts.entries
                .joinToString(" + ") { (unit, value) ->
                    listOf(value.trimZeros(), unit).filter { it.isNotBlank() }.joinToString(" ")
                }
            // An unreadable quantity still earns a line. "a pinch of salt" is on
            // the list; the parser not knowing how much is not a reason to shop
            // for none of it.
            val suffix = if (line.unquantified > 0) {
                if (amounts.isBlank()) "as needed" else "$amounts + as needed"
            } else amounts
            Text("${line.name} — $suffix", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun RecipeEditor(recipe: Recipe, onCancel: () -> Unit, onSave: (Recipe) -> Unit) {
    var name by remember(recipe.id) { mutableStateOf(recipe.name) }
    var servings by remember(recipe.id) { mutableStateOf(recipe.servings.trimZeros()) }
    // One line per ingredient, exactly as typed. The parser reads these for the
    // shopping list, but what the coach wrote is what is stored -- a re-save
    // must never launder "a pinch" into nothing.
    var ingredients by remember(recipe.id) { mutableStateOf(recipe.rawIngredients.joinToString("\n")) }
    var steps by remember(recipe.id) { mutableStateOf(recipe.steps.joinToString("\n")) }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(if (recipe.name.isBlank()) "New recipe" else "Edit recipe") },
        text = {
            Column {
                OutlinedTextField(value = name, onValueChange = { name = it },
                                  label = { Text("Name") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = servings, onValueChange = { servings = it },
                                  label = { Text("Serves") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = ingredients, onValueChange = { ingredients = it },
                                  label = { Text("Ingredients, one per line") })
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = steps, onValueChange = { steps = it },
                                  label = { Text("Steps, one per line") })
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
                            steps = steps.lines().map { it.trim() }.filter { it.isNotEmpty() }
                        )
                    )
                }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } }
    )
}
