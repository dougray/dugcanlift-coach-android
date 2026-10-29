package com.dugcanlift.coach.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.dugcanlift.coach.data.CatalogRecipe
import com.dugcanlift.coach.data.Recipe
import com.dugcanlift.coach.data.RecipeCatalog
import com.dugcanlift.coach.data.RecipeCatalogStore
import com.dugcanlift.coach.ui.adaptive.AdaptiveLayout
import com.dugcanlift.coach.ui.adaptive.GridRow
import com.dugcanlift.coach.ui.adaptive.rowMajor
import com.dugcanlift.coach.ui.theme.dclCardBorder
import com.dugcanlift.coach.ui.theme.dclOutlinedButtonColors
import com.dugcanlift.coach.ui.theme.dclTextButtonColors
import com.dugcanlift.coach.ui.theme.dclTextFieldColors
import com.dugcanlift.kit.IngredientParser
import kotlinx.coroutines.delay

/**
 * Browse the bundled recipe catalogue and copy one into the library -- a port
 * of Coach iPhone's `RecipeCatalogView`, wording and all. The rules are
 * [RecipeCatalog] and [CatalogRecipe]; this only draws them.
 *
 * One of the import sources beside a pasted recipe. Neither needs a network;
 * this one reads `recipes.json`, an app asset. Nothing is costed here: the
 * catalogue's macros and coverage note are already decided.
 *
 * [onAdd] receives the finished library recipe and saves it through the same
 * path as the recipe editor's Save. [onDone] closes the catalogue without
 * adding anything.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RecipeCatalogDialog(onDone: () -> Unit, onAdd: (Recipe) -> Unit) {
    val context = LocalContext.current
    var catalog by remember { mutableStateOf<RecipeCatalog?>(null) }
    var loadFailed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        catalog = RecipeCatalogStore.load(context)
        loadFailed = catalog == null
    }

    // Saved by id and as plain values, so a theme or density change reopens
    // the same recipe at the same serving count.
    var query by rememberSaveable { mutableStateOf("") }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var servings by rememberSaveable { mutableStateOf(RecipeCatalog.DEFAULT_SERVINGS) }
    var entries by remember { mutableStateOf<List<CatalogRecipe>>(emptyList()) }
    var note by remember { mutableStateOf("") }

    val credits = remember(catalog) { catalog?.credits().orEmpty() }
    val selected = selectedId?.let { id -> catalog?.recipes?.firstOrNull { it.id == id } }

    // iOS's load(): a two-character floor, and a 200 ms pause before a search
    // so each keystroke does not run one. A one-character query leaves the
    // list as it was.
    LaunchedEffect(query, catalog, loadFailed) {
        if (loadFailed) {
            entries = emptyList()
            note = "The recipe catalogue could not be opened."
            return@LaunchedEffect
        }
        val loaded = catalog ?: return@LaunchedEffect
        val trimmed = query.trim()
        if (trimmed.isNotEmpty() && trimmed.length < RecipeCatalog.MIN_QUERY) return@LaunchedEffect
        if (trimmed.isNotEmpty()) delay(200)
        val found = if (trimmed.length >= RecipeCatalog.MIN_QUERY) loaded.search(trimmed) else loaded.browse()
        entries = found
        note = if (found.isEmpty() && trimmed.length >= RecipeCatalog.MIN_QUERY) "Nothing matches that." else ""
    }

    val back = { if (selectedId != null) selectedId = null else onDone() }

    Dialog(
        onDismissRequest = back,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                topBar = {
                    TopAppBar(
                        title = { Text(if (selected == null) "Recipe catalogue" else "Add recipe") },
                        navigationIcon = {
                            TextButton(onClick = back, colors = dclTextButtonColors()) {
                                Text(if (selected == null) "Done" else "Back")
                            }
                        },
                        actions = {
                            if (selected != null) {
                                TextButton(
                                    onClick = { onAdd(selected.makeRecipe(servings)) },
                                    colors = dclTextButtonColors()
                                ) { Text("Add") }
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.background
                        )
                    )
                }
            ) { padding ->
                BoxWithConstraints(Modifier.padding(padding).fillMaxSize()) {
                    val available = maxWidth.value - 32f
                    if (selected == null) {
                        CatalogList(
                            query = query,
                            onQuery = { query = it },
                            entries = entries,
                            note = note,
                            credits = credits,
                            columns = AdaptiveLayout.cardColumns(available),
                            onOpen = {
                                servings = RecipeCatalog.openingServings(it)
                                selectedId = it.id
                            }
                        )
                    } else {
                        CatalogDetail(
                            recipe = selected,
                            servings = servings,
                            onServings = { servings = it },
                            availableWidth = available
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Caption(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
    )
}

@Composable
private fun CatalogList(
    query: String,
    onQuery: (String) -> Unit,
    entries: List<CatalogRecipe>,
    note: String,
    credits: List<String>,
    columns: Int,
    onOpen: (CatalogRecipe) -> Unit
) {
    val row: @Composable (CatalogRecipe, Modifier) -> Unit = { recipe, modifier ->
        Card(
            modifier
                .fillMaxWidth()
                .clickable { onOpen(recipe) },
            border = dclCardBorder()
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(recipe.name, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(2.dp))
                Caption(RecipeCatalog.rowSummary(recipe))
            }
        }
    }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item(key = "search") {
            OutlinedTextField(
                value = query,
                onValueChange = onQuery,
                placeholder = { Text("Search 600+ recipes") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier
                    .widthIn(max = AdaptiveLayout.MAX_FORM_DP.dp)
                    .fillMaxWidth()
                    .semantics { contentDescription = "Search the recipe catalogue" },
                colors = dclTextFieldColors()
            )
        }
        if (note.isNotEmpty()) {
            item(key = "note") { Caption(note) }
        }
        if (columns == 1) {
            items(entries, key = { it.id }) { recipe -> row(recipe, Modifier) }
        } else {
            items(rowMajor(entries, columns), key = { it.first().id }) { cells ->
                GridRow(cells, columns) { recipe -> row(recipe, Modifier.fillMaxHeight()) }
            }
        }
        item(key = "footer") {
            Caption("Ships with Coach; no connection needed. Nothing is added to your library until you tap Add.")
        }
        if (credits.isNotEmpty()) {
            // CC BY-SA requires the credit be given, so it sits on the screen
            // that shows the data rather than in a settings page.
            item(key = "credits") {
                Column(Modifier.padding(top = 8.dp, bottom = 24.dp)) {
                    Text("Credits", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(4.dp))
                    credits.forEach { Caption(it) }
                }
            }
        }
    }
}

@Composable
private fun DetailSection(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(modifier.fillMaxWidth(), border = dclCardBorder()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            content()
        }
    }
}

@Composable
private fun CatalogDetail(
    recipe: CatalogRecipe,
    servings: Double,
    onServings: (Double) -> Unit,
    availableWidth: Float
) {
    val contentWidth = AdaptiveLayout.clientContentWidth(availableWidth)
    val sideBySide = AdaptiveLayout.clientColumns(contentWidth) == 2

    val ingredients: @Composable (Modifier) -> Unit = { modifier ->
        DetailSection("Ingredients (${recipe.ingredients.size})", modifier) {
            recipe.ingredients.forEach { line ->
                val parsed = IngredientParser.parse(line)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(parsed.displayText, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    if (parsed.qty == null) {
                        Spacer(Modifier.width(8.dp))
                        Caption("as written")
                    }
                }
            }
        }
    }
    val method: @Composable (Modifier) -> Unit = { modifier ->
        DetailSection("Method (${recipe.steps.size})", modifier) {
            recipe.steps.forEachIndexed { index, step ->
                Text("${index + 1}. $step", style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        val width = Modifier.widthIn(max = AdaptiveLayout.MAX_CONTENT_DP.dp).fillMaxWidth()

        item(key = "recipe") {
            DetailSection("Recipe", width) {
                Text(recipe.name, style = MaterialTheme.typography.titleMedium)
                recipe.summary?.let { Caption(it) }
                if (recipe.asksServings) {
                    ServingsStepper(servings, onServings)
                    Caption(RecipeCatalog.noYieldNote(recipe))
                } else {
                    recipe.servings?.let {
                        Text(RecipeCatalog.servingsLabel(it), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }

        val hasSteps = recipe.steps.isNotEmpty()
        if (sideBySide && hasSteps) {
            item(key = "lines") {
                Box(width) {
                    GridRow(listOf(0, 1), 2) { cell ->
                        if (cell == 0) ingredients(Modifier.fillMaxHeight()) else method(Modifier.fillMaxHeight())
                    }
                }
            }
        } else {
            item(key = "ingredients") { ingredients(width) }
            if (hasSteps) item(key = "method") { method(width) }
        }

        item(key = "macros") {
            DetailSection("Macros", width) {
                val facts = recipe.nutrition(servings)
                if (facts != null) {
                    Text(RecipeCatalog.macroLine(facts), style = MaterialTheme.typography.bodyMedium)
                    Caption(RecipeCatalog.macroBasis(recipe, servings))
                } else {
                    Caption("None.")
                }
                recipe.nutritionNote?.let { Caption(it) }
            }
        }

        item(key = "source") {
            DetailSection("Source", width.padding(bottom = 24.dp)) {
                Caption(recipe.attribution)
                Caption(recipe.license)
            }
        }
    }
}

/** iOS's `Stepper(in: 1...48, step: 1)`: minus, the count in words, plus. */
@Composable
private fun ServingsStepper(servings: Double, onServings: (Double) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            RecipeCatalog.servingsLabel(servings),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        OutlinedButton(
            onClick = { onServings((servings - 1).coerceAtLeast(RecipeCatalog.MIN_SERVINGS)) },
            enabled = servings > RecipeCatalog.MIN_SERVINGS,
            colors = dclOutlinedButtonColors(),
            modifier = Modifier.semantics { contentDescription = "One serving fewer" }
        ) { Text("−") }
        Spacer(Modifier.width(8.dp))
        OutlinedButton(
            onClick = { onServings((servings + 1).coerceAtMost(RecipeCatalog.MAX_SERVINGS)) },
            enabled = servings < RecipeCatalog.MAX_SERVINGS,
            colors = dclOutlinedButtonColors(),
            modifier = Modifier.semantics { contentDescription = "One serving more" }
        ) { Text("+") }
    }
}
