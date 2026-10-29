package com.dugcanlift.coach.data

import com.dugcanlift.kit.IngredientParser
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

/**
 * The bundled recipe catalogue: the file is the kit's bytes, what the list and
 * a search find, the two kinds of yield, and what a copy puts in the library.
 *
 * Coach iPhone's `RecipeCatalogView` and the kit's `RecipeCatalog.swift` are
 * the reference; every expected string here is one of theirs.
 */
class RecipeCatalogTest {

    @get:Rule val tmp = TemporaryFolder()

    /** The bundled file itself, the same bytes the kit exported. */
    private val recipes: List<CatalogRecipe> by lazy {
        parseRecipeCatalog(File("src/main/assets/$RECIPE_CATALOGUE_ASSET").readText())
    }
    private val catalog by lazy { RecipeCatalog(recipes) }

    private fun named(name: String) = recipes.first { it.name == name }

    private val carbonara get() = named("Spaghetti carbonara")
    /** A heritage recipe whose book states no yield, carrying whole-dish totals. */
    private val potPie get() = recipes.first { it.id == "gutenberg-26558:beef-pot-pie" }

    // MARK: - The copy is the kit's bytes

    /**
     * recipes.json is exported once, by `dugcanlift-kit`'s
     * `Tools/export_recipes_json.py`, from the `recipes.db` the iPhones read,
     * and copied byte for byte into each build that cannot open the database.
     * Every other check here reads the data's shape, and a copy several
     * exports behind has a perfectly good shape -- so this is the one that
     * says the copy is current.
     *
     * The exporter writes the checksum; copy recipes.json AND recipes.sha256
     * over together, and never re-write the hash by hand to make this pass.
     */
    @Test
    fun `the bundled recipe catalogue is the kit's bytes`() {
        val bytes = File("src/main/assets/$RECIPE_CATALOGUE_ASSET").readBytes()
        val pinned = File("src/main/assets/recipes.sha256").readText().trim()
        assertTrue(
            "recipes.sha256 should be one bare sha256 and nothing else",
            Regex("^[0-9a-f]{64}$").matches(pinned)
        )
        val actual = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        assertEquals(
            "src/main/assets/$RECIPE_CATALOGUE_ASSET does not match recipes.sha256. " +
                "Copy dugcanlift-kit/data/recipes.json and data/recipes.sha256 over together.",
            pinned,
            actual
        )
    }

    // MARK: - The file

    @Test fun `all 622 recipes read, each with a unique id`() {
        assertEquals(622, recipes.size)
        assertEquals(recipes.size, recipes.map { it.id }.toSet().size)
    }

    @Test fun `every recipe carries its own licence and attribution`() {
        assertTrue(recipes.all { it.license.isNotBlank() && it.attribution.isNotBlank() })
        assertEquals(
            listOf(
                "Pennsylvania Dutch Cooking (Project Gutenberg #26558) — Public domain",
                "UniTools — theunitools.com — CC BY-SA 4.0"
            ),
            catalog.credits()
        )
    }

    @Test fun `a row with no stated yield carries totals, never per-serving figures`() {
        val noYield = recipes.filter { it.servings == null }
        assertEquals(121, noYield.size)
        assertTrue(noYield.all { it.caloriesPerServing == null })
        assertTrue(noYield.all { it.id.startsWith("gutenberg-") })
        val stated = recipes.filter { it.servings != null }
        assertTrue(stated.all { it.caloriesTotal == null })
    }

    @Test fun `junk rows are dropped and a file that is not a catalogue throws`() {
        val json = JSONObject().put(
            "recipes", JSONArray()
                .put("not an object")
                .put(JSONObject().put("name", "No id"))
                .put(JSONObject().put("id", "x").put("name", "Toast").put("servings", "two")
                    .put("caloriesPerServing", JSONObject.NULL))
        ).toString()
        val read = parseRecipeCatalog(json)
        assertEquals(listOf("Toast"), read.map { it.name })
        assertNull("a non-number is unknown, never zero", read[0].servings)
        assertNull(read[0].caloriesPerServing)
        assertTrue(runCatching { parseRecipeCatalog("{}") }.isFailure)
    }

    // MARK: - Browse and search

    @Test fun `the opening page is the first 60 by name, both collections interleaved`() {
        val page = catalog.browse()
        assertEquals(60, page.size)
        assertEquals(recipes.map { it.name }.sorted().take(60), page.map { it.name })
        assertEquals("A Good Pennsylvania Dutch Salad Dressing", page.first().name)
        assertTrue(page.any { it.source == "unitools" } && page.any { it.source.startsWith("gutenberg") })
    }

    @Test fun `carbonara finds spaghetti carbonara first`() {
        assertEquals("Spaghetti carbonara", catalog.search("carbonara").first().name)
        assertEquals("Spaghetti carbonara", catalog.search("  Carbonara ").first().name)
    }

    @Test fun `every word must begin a word, in any order`() {
        assertEquals("Spaghetti carbonara", catalog.search("carb spag").first().name)
        assertTrue(catalog.search("carbonara pizza").isEmpty())
        assertTrue("a word's middle is not a match", catalog.search("bonara").isEmpty())
    }

    @Test fun `search reads the summary and the country, as iOS's index does`() {
        // "guanciale" is in carbonara's summary, not its name.
        assertTrue(catalog.search("guanciale").any { it.name == "Spaghetti carbonara" })
        val italian = catalog.search("it", limit = 100)
        assertTrue(italian.map { it.name }.containsAll(listOf("Spaghetti carbonara", "Tiramisu", "Pizza margherita")))
    }

    @Test fun `category is not searched, on either build`() {
        // "sauce" is a category; a recipe whose only "sauce" is its category is not found.
        val onlyByCategory = recipes.filter { r ->
            r.category == "sauce" && RecipeCatalog.words(listOfNotNull(r.name, r.summary, r.country).joinToString(" "))
                .none { it.startsWith("sauce") }
        }
        assertTrue(onlyByCategory.isNotEmpty())
        val found = catalog.search("sauce", limit = 1000).map { it.id }.toSet()
        assertTrue(onlyByCategory.none { it.id in found })
    }

    @Test fun `accents and case are ignored`() {
        assertEquals("Bánh mì", catalog.search("banh mi").first().name)
        assertEquals("Bánh mì", catalog.search("BÁNH").first().name)
    }

    @Test fun `fewer than two characters finds nothing, and results stop at 40`() {
        assertTrue(catalog.search("c").isEmpty())
        assertTrue(catalog.search(" ").isEmpty())
        val everyMatch = catalog.search("with", limit = 1000)
        assertTrue("a broad word matches more than a page", everyMatch.size > RecipeCatalog.SEARCH_LIMIT)
        assertEquals(everyMatch.take(40), catalog.search("with"))
    }

    @Test fun `a name match outranks a summary match, then the shorter name wins`() {
        val found = catalog.search("chicken")
        val firstSummaryOnly = found.indexOfFirst { r -> RecipeCatalog.words(r.name).none { it.startsWith("chicken") } }
        val lastNameMatch = found.indexOfLast { r -> RecipeCatalog.words(r.name).any { it.startsWith("chicken") } }
        if (firstSummaryOnly >= 0) assertTrue(lastNameMatch < firstSummaryOnly)
        val nameMatches = found.take(lastNameMatch + 1).map { it.name.length }
        assertEquals(nameMatches.sorted(), nameMatches)
    }

    // MARK: - Rows

    @Test fun `a row says what its macros are per, in iOS's words`() {
        assertEquals(
            "690 kcal per serving · ${carbonara.ingredients.size} ingredients",
            RecipeCatalog.rowSummary(carbonara)
        )
        assertEquals("Macros for the whole dish · 6 ingredients", RecipeCatalog.rowSummary(potPie))
        val noMacros = recipes.first { it.servings == null && it.caloriesTotal == null }
        assertTrue(RecipeCatalog.rowSummary(noMacros).startsWith("No macros · "))
    }

    @Test fun `servings read as iOS's CookFormat prints them`() {
        assertEquals("1 serving", RecipeCatalog.servingsLabel(1.0))
        assertEquals("4 servings", RecipeCatalog.servingsLabel(4.0))
        assertEquals("2.5 servings", RecipeCatalog.servingsLabel(2.5))
    }

    // MARK: - Two kinds of yield

    @Test fun `a stated yield uses the published per-serving figures and ignores the count`() {
        assertFalse(carbonara.needsServings)
        assertEquals(2.0, carbonara.servings!!, 0.0)
        for (count in listOf(1.0, 4.0, 12.0)) {
            val facts = carbonara.nutrition(count)!!
            assertEquals(690.0, facts.calories, 0.0)
            assertEquals(32.0, facts.proteinG, 0.0)
            assertEquals(62.0, facts.carbsG, 0.0)
            assertEquals(34.0, facts.fatG, 0.0)
        }
        assertEquals("Per serving, as published.", RecipeCatalog.macroBasis(carbonara, 4.0))
        assertEquals("690 kcal · P 32 · C 62 · F 34", RecipeCatalog.macroLine(carbonara.nutrition(2.0)!!))
    }

    @Test fun `no stated yield divides the whole-dish totals by the coach's count`() {
        assertTrue(potPie.needsServings)
        assertEquals(4.0, RecipeCatalog.openingServings(potPie), 0.0)
        assertEquals(2.0, RecipeCatalog.openingServings(carbonara), 0.0)
        val atFour = potPie.nutrition(4.0)!!
        assertEquals(898.1 / 4, atFour.calories, 1e-9)
        assertEquals(40.0 / 4, atFour.proteinG, 1e-9)
        assertEquals(71.2 / 4, atFour.carbsG, 1e-9)
        assertEquals(50.2 / 4, atFour.fatG, 1e-9)
        assertEquals(898.1 / 6, potPie.nutrition(6.0)!!.calories, 1e-9)
        assertEquals("224 kcal · P 10 · C 17 · F 12", RecipeCatalog.macroLine(atFour))
        assertEquals("Per serving at 4 servings.", RecipeCatalog.macroBasis(potPie, 4.0))
        assertEquals("a nonsense count divides by one, never by zero", 898.1, potPie.nutrition(0.0)!!.calories, 1e-9)
    }

    @Test fun `no yield and no macros is no macros, not zeros`() {
        val bare = recipes.first { it.servings == null && it.caloriesTotal == null }
        assertFalse(bare.needsServings)
        assertNull(bare.nutrition(4.0))
        assertNull(bare.makeRecipe(4.0).nutritionPerServing)
    }

    // MARK: - The copy

    @Test fun `a copy takes the file's macros exactly - nothing is re-costed`() {
        val made = carbonara.makeRecipe(chosenServings = 9.0)
        assertEquals("a stated yield wins over the stepper", 2.0, made.servings, 0.0)
        assertEquals(carbonara.nutrition(2.0), made.nutritionPerServing)
        assertTrue("estimated carries over", made.nutritionPerServing!!.estimated)
        assertEquals(0.0, made.nutritionPerServing!!.fiberG, 0.0)
        assertNull(made.nutritionPerServing!!.saturatedFatG)
    }

    @Test fun `a copy with no stated yield stores the chosen count and the divided figures`() {
        val made = potPie.makeRecipe(chosenServings = 6.0)
        assertEquals(6.0, made.servings, 0.0)
        assertEquals(898.1 / 6, made.nutritionPerServing!!.calories, 1e-9)
        assertEquals("the totals come back whole", 898.1, made.totalNutrition!!.calories, 1e-9)
    }

    @Test fun `ingredient lines go in as raw text and the existing parser reads them`() {
        val made = carbonara.makeRecipe(4.0)
        assertEquals(carbonara.ingredients, made.rawIngredients)
        assertEquals(carbonara.steps, made.steps)
        assertEquals(made.rawIngredients.map { IngredientParser.parse(it) }, made.parsedIngredients)
        val spaghetti = made.parsedIngredients.first()
        assertEquals(200.0, spaghetti.qty!!, 0.0)
        assertEquals("g", spaghetti.unit)
    }

    @Test fun `the credit and the caveat travel with the copy under BACKUP-FORMAT's names`() {
        val made = potPie.makeRecipe(4.0)
        val extras = made.unknownKeys!!
        assertEquals("Pennsylvania Dutch Cooking (Project Gutenberg #26558)", extras.getString("sourceAuthor"))
        assertEquals("https://www.gutenberg.org/ebooks/26558", extras.getString("sourceUrl"))
        assertEquals(
            listOf(potPie.attribution, potPie.license, potPie.nutritionNote).joinToString("\n"),
            extras.getString("sourceTranscript")
        )
        assertFalse("an unknown time is left out, not written as null", extras.has("prepMinutes"))
        val withTimes = carbonara.makeRecipe(4.0).unknownKeys!!
        assertEquals(10, withTimes.getInt("prepMinutes"))
        assertEquals(15, withTimes.getInt("cookMinutes"))
        assertEquals("UniTools — theunitools.com\nCC BY-SA 4.0", withTimes.getString("sourceTranscript"))
    }

    @Test fun `a copy is an ordinary library recipe through the editor's save`() {
        val cook = CookRepository(tmp.root)
        cook.upsertRecipe(potPie.makeRecipe(chosenServings = 5.0, id = "r1"))
        cook.upsertRecipe(carbonara.makeRecipe(chosenServings = 4.0, id = "r2"))
        val library = cook.load()
        assertFalse(library.isUnreadable)
        assertEquals(listOf("Beef Pot Pie", "Spaghetti carbonara"), library.recipes.map { it.name })
        val pie = library.recipes.first { it.id == "r1" }
        assertEquals(5.0, pie.servings, 0.0)
        assertEquals(898.1 / 5, pie.nutritionPerServing!!.calories, 1e-9)
        assertEquals(potPie.ingredients, pie.rawIngredients)
        assertEquals(
            "the credit survives the round trip",
            potPie.attribution, pie.unknownKeys!!.getString("sourceAuthor")
        )
        assertNotNull(pie.toJson().optString("sourceTranscript").takeIf { it.isNotEmpty() })
    }

    // MARK: - No connection

    @Test fun `the app still asks for no network permission`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertFalse(manifest.contains("android.permission.INTERNET"))
        assertFalse(manifest.contains("ACCESS_NETWORK_STATE"))
    }
}
