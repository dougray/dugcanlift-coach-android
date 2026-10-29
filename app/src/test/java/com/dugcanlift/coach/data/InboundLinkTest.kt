package com.dugcanlift.coach.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [inboundLink] is the one place a log link and a recipe link are told apart. A log link must
 * still import exactly as it did, by every route it ever had, and a recipe link must never reach
 * the log importer -- nor the other way round.
 */
class InboundLinkTest {
    @get:Rule val tmp = TemporaryFolder()

    private val view = "android.intent.action.VIEW"
    private val send = "android.intent.action.SEND"

    private fun fixture(name: String) =
        javaClass.classLoader!!.getResource("fixtures/$name")!!.readText().trim()

    private val logFragment by lazy { fixture("share-link-android.txt") }
    private val recipeFragment by lazy { fixture("recipe-link.txt").substringAfter('#') }

    @Test fun `the bookmarklet's link is a recipe link`() {
        val data = "dugcanliftcoach://recipe#$recipeFragment"
        assertEquals(
            InboundLink.RecipePage(data),
            inboundLink(view, "dugcanliftcoach", "recipe", data, recipeFragment, null)
        )
    }

    @Test fun `a mangled recipe link is still a recipe link, shown as unreadable`() {
        val link = inboundLink(view, "dugcanliftcoach", "recipe", "dugcanliftcoach://recipe", null, null)
        assertEquals(InboundLink.RecipePage("dugcanliftcoach://recipe"), link)
        assertEquals(
            RecipeLinkReview.Refused(RecipeLinkImport.UNREADABLE),
            RecipeLinkImport.read((link as InboundLink.RecipePage).link, "d")
        )
    }

    @Test fun `a tapped log link imports as before`() {
        val data = "https://www.dugcanlift.com/coach/#$logFragment"
        val link = inboundLink(view, "https", "www.dugcanlift.com", data, logFragment, null)
        assertEquals(InboundLink.Log(logFragment), link)
        assertTrue(ShareLinkImporter.import(logFragment, ClientRepository(tmp.root)) is ImportResult.Imported)
    }

    @Test fun `a shared log link imports as before`() {
        val text = "My week https://www.dugcanlift.com/coach/#$logFragment"
        assertEquals(InboundLink.Log(logFragment), inboundLink(send, null, null, null, null, text))
        // And is exactly what fragmentToImport made of it before recipe links existed.
        assertEquals(fragmentToImport(null, send, text), logFragment)
    }

    @Test fun `a recipe link shared as text is a recipe link`() {
        val text = "dugcanliftcoach://recipe#$recipeFragment"
        val link = inboundLink(send, null, null, null, null, text)
        assertEquals(InboundLink.RecipePage("#$recipeFragment"), link)
        assertTrue(RecipeLinkImport.read((link as InboundLink.RecipePage).link, "d") is RecipeLinkReview.Ready)
    }

    @Test fun `Coach web's recipe target caught by the App Link is a recipe link, not a log`() {
        val data = "https://www.dugcanlift.com/coach/#$recipeFragment"
        assertEquals(
            InboundLink.RecipePage("#$recipeFragment"),
            inboundLink(view, "https", "www.dugcanlift.com", data, recipeFragment, null)
        )
    }

    @Test fun `neither reader accepts the other's link`() {
        assertNull(RecipeLink.decode("https://www.dugcanlift.com/coach/#$logFragment"))
        // The log importer reads an r1 fragment as a link from a newer LIFT -- the wrong message
        // entirely, which is why inboundLink sorts recipe links out before it is ever called.
        assertFalse(ShareLinkImporter.import(recipeFragment, ClientRepository(tmp.root)) is ImportResult.Imported)
        assertTrue(ClientRepository(tmp.root).all().isEmpty())
    }

    @Test fun `a launch with nothing in it is nothing`() {
        assertNull(inboundLink("android.intent.action.MAIN", null, null, null, null, null))
        assertNull(inboundLink(send, null, null, null, null, ""))
    }

    @Test fun `the manifest routes the recipe scheme to the app, and asks for no network`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val filter = Regex("<intent-filter>(?:(?!</intent-filter>).)*dugcanliftcoach(?:(?!</intent-filter>).)*</intent-filter>", RegexOption.DOT_MATCHES_ALL)
            .find(manifest)!!.value
        assertTrue(filter.contains("android.intent.action.VIEW"))
        assertTrue(filter.contains("android.intent.category.DEFAULT"))
        assertTrue(filter.contains("android.intent.category.BROWSABLE"))
        assertTrue(filter.contains("android:host=\"recipe\""))
        assertTrue(!filter.contains("autoVerify"))
        assertTrue(!manifest.contains("android.permission.INTERNET"))
        // The log filters are untouched.
        assertTrue(manifest.contains("android:pathPrefix=\"/coach/\""))
        assertTrue(manifest.contains("android:mimeType=\"text/plain\""))
    }
}
