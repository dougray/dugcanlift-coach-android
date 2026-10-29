package com.dugcanlift.coach.data

import com.dugcanlift.coach.ui.linkReviewNote
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Deflater

/**
 * A "Send to Coach" link to the recipe the editor opens with. The kit's fixture page is a schema.org
 * `@graph` with a yield, HowToSteps, nutrition and non-ASCII text; the rest are built here.
 */
class RecipeLinkImportTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun fixture(name: String) =
        javaClass.classLoader!!.getResource("fixtures/$name")!!.readText().trim()

    private fun link(url: String, vararg blocks: String): String {
        val json = JSONObject().put("v", 1).put("url", url).put("jsonld", JSONArray(blocks.toList())).toString()
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        deflater.setInput(json.toByteArray(Charsets.UTF_8))
        deflater.finish()
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
        deflater.end()
        return "dugcanliftcoach://recipe#r1" + Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray())
    }

    private val breadcrumb = """{"@context":"https://schema.org","@type":"BreadcrumbList","itemListElement":[{"@type":"ListItem","position":1,"name":"Recipes"}]}"""

    @Test fun `the kit's fixture opens as the page's recipe`() {
        val review = RecipeLinkImport.read(fixture("recipe-link.txt"), id = "draft-1")
        assertTrue(review is RecipeLinkReview.Ready)
        review as RecipeLinkReview.Ready
        val recipe = review.recipe
        assertEquals("draft-1", recipe.id)
        assertEquals("Beef & barley stew — crème fraîche finish 🍲", recipe.name)
        assertTrue(review.servingsStated)
        assertEquals(4.0, recipe.servings, 0.0)
        assertEquals(
            listOf(
                "600 g beef chuck, cubed", "150 g pearl barley", "2 carrots, diced",
                "1 onion, chopped", "1.2 L beef stock", "2 tbsp crème fraîche"
            ),
            recipe.rawIngredients
        )
        assertEquals(
            listOf(
                "Brown the beef in batches.",
                "Add the vegetables, barley and stock; simmer 90 minutes.",
                "Stir in the crème fraîche and serve."
            ),
            recipe.steps
        )
        val n = recipe.nutritionPerServing!!
        assertEquals(520.0, n.calories, 0.0)
        assertEquals(38.0, n.proteinG, 0.0)
        assertEquals(44.0, n.carbsG, 0.0)
        assertEquals(20.0, n.fatG, 0.0)
        assertTrue("the publisher's claim, flagged as one", n.estimated)
        assertEquals("https://example.com/recipes/stew", recipe.unknownKeys!!.getString("sourceUrl"))
        assertEquals("Ana Núñez", recipe.unknownKeys!!.getString("sourceAuthor"))
    }

    @Test fun `opening a link saves nothing`() {
        val cook = CookRepository(tmp.root)
        RecipeLinkImport.read(fixture("recipe-link.txt"), id = "draft-1")
        assertTrue(cook.load().recipes.isEmpty())
        assertFalse(tmp.root.listFiles()!!.any { it.name.contains("cook", ignoreCase = true) })
    }

    @Test fun `the source address rides under BACKUP-FORMAT's name and survives a save`() {
        val review = RecipeLinkImport.read(fixture("recipe-link.txt"), id = "draft-1") as RecipeLinkReview.Ready
        val cook = CookRepository(tmp.root)
        cook.upsertRecipe(review.recipe)
        val saved = cook.load().recipes.single()
        assertEquals("https://example.com/recipes/stew", saved.toJson().getString("sourceUrl"))
        assertEquals("https://example.com/recipes/stew", recipeFromJson(saved.toJson()).unknownKeys!!.getString("sourceUrl"))
    }

    @Test fun `a page with no yield is never given one`() {
        val block = """{"@type":"Recipe","name":"Flatbread","recipeIngredient":["300 g flour","1 tsp salt"]}"""
        val review = RecipeLinkImport.read(link("https://a.example/bread", block), "d") as RecipeLinkReview.Ready
        assertFalse(review.servingsStated)
        assertNull("no nutrition stays unknown, never zero", review.recipe.nutritionPerServing)
    }

    @Test fun `the first block holding a recipe wins, not merely the first block`() {
        val block = """{"@type":"Recipe","name":"Soup","recipeYield":"2 bowls"}"""
        val review = RecipeLinkImport.read(link("https://a.example/soup", breadcrumb, block), "d")
        assertEquals("Soup", (review as RecipeLinkReview.Ready).recipe.name)
        assertEquals(2.0, review.recipe.servings, 0.0)
    }

    @Test fun `a link with no recipe in it says so`() {
        assertEquals(
            RecipeLinkReview.Refused(RecipeLinkImport.NO_RECIPE),
            RecipeLinkImport.read(link("https://a.example/news", breadcrumb), "d")
        )
    }

    @Test fun `an unreadable link says so`() {
        val unreadable = RecipeLinkReview.Refused(RecipeLinkImport.UNREADABLE)
        assertEquals(unreadable, RecipeLinkImport.read("dugcanliftcoach://recipe#r1garbage!!", "d"))
        assertEquals(unreadable, RecipeLinkImport.read("dugcanliftcoach://recipe#nonsense", "d"))
        assertEquals(unreadable, RecipeLinkImport.read("dugcanliftcoach://recipe", "d"))
        assertEquals(unreadable, RecipeLinkImport.read("", "d"))
    }

    @Test fun `a log link never reads as a recipe`() {
        val log = "https://www.dugcanlift.com/coach/#" + fixture("share-link-android.txt")
        assertEquals(RecipeLinkReview.Refused(RecipeLinkImport.UNREADABLE), RecipeLinkImport.read(log, "d"))
    }

    @Test fun `the form note names the site, in the shared wording`() {
        val review = RecipeLinkImport.read(fixture("recipe-link.txt"), id = "d") as RecipeLinkReview.Ready
        assertEquals("From example.com. Check it, then Save.", linkReviewNote(review.recipe, review.servingsStated))
        val www = RecipeLinkImport.read(link("https://www.allrecipes.com/r/1", """{"@type":"Recipe","name":"Loaf"}"""), "d")
            as RecipeLinkReview.Ready
        assertEquals(
            "From allrecipes.com. Check it, then Save. " +
                "The page did not say how many this serves; set it before saving.",
            linkReviewNote(www.recipe, www.servingsStated)
        )
    }

    @Test fun `the two refusals are the shared wording`() {
        assertEquals(
            "That recipe link could not be read, so nothing was added. Open the recipe page and tap Send to Coach again.",
            RecipeLinkImport.UNREADABLE
        )
        assertEquals(
            "That page has no recipe Coach can read. Copy the recipe text and use Paste a recipe instead.",
            RecipeLinkImport.NO_RECIPE
        )
    }
}
