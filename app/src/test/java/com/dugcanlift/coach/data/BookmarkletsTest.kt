package com.dugcanlift.coach.data

import com.dugcanlift.coach.ui.BookmarkletText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.URLDecoder
import java.security.MessageDigest

/**
 * `bookmarklets.json` is the kit's (dugcanlift-kit `bookmarklet/`), built from `send-recipe.js`.
 * Copy it **and** `bookmarklets.sha256` from the kit together; never edit either here and never
 * write the hash by hand. The other checks below only read what the bytes mean, and a stale copy
 * passes all of them.
 */
class BookmarkletsTest {
    private val file = File("src/main/assets/$BOOKMARKLETS_ASSET")

    @Test fun `the bundled file is the kit's bytes`() {
        val pinned = File("src/main/assets/bookmarklets.sha256").readText().trim()
        assertTrue("bookmarklets.sha256 should be one bare sha256", Regex("^[0-9a-f]{64}$").matches(pinned))
        val actual = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }
        assertEquals(
            "src/main/assets/$BOOKMARKLETS_ASSET does not match bookmarklets.sha256. " +
                "Copy dugcanlift-kit/bookmarklet/bookmarklets.json and bookmarklets.sha256 over together.",
            pinned, actual
        )
    }

    @Test fun `Coach offers the coachApp string byte for byte`() {
        val raw = file.readText()
        val shown = bookmarkletFrom(raw)
        assertNotNull(shown)
        // The value as it sits in the file, not re-encoded: a person pastes exactly this.
        assertTrue(raw.contains("\"$COACH_APP_KEY\": \"$shown\""))
    }

    @Test fun `the coachApp bookmarklet opens Coach's own recipe scheme`() {
        val shown = bookmarkletFrom(file.readText())!!
        assertTrue(shown.startsWith("javascript:"))
        assertFalse("a raw # would end the bookmark's address", shown.contains('#'))
        val code = URLDecoder.decode(shown.removePrefix("javascript:"), "UTF-8")
        assertTrue(code.contains("location.href = '$RECIPE_LINK_SCHEME://$RECIPE_LINK_HOST' + '#r1'"))
        assertTrue(code.contains("no recipe Coach can read"))
        // The other three targets are not Coach Android's.
        assertFalse(code.contains("dugcanliftlift:"))
        assertFalse(code.contains("www.dugcanlift.com"))
    }

    @Test fun `a file without the key offers nothing`() {
        assertEquals(null, bookmarkletFrom("{}"))
        assertEquals(null, bookmarkletFrom("{\"coachApp\": \"https://example.com\"}"))
    }

    @Test fun `the sheet's words are the shared wording`() {
        assertEquals("Send recipes from your browser", BookmarkletText.OFFER)
        assertEquals(
            "Save this as a bookmark once. On any recipe page, tap the bookmark and the recipe opens " +
                "here for you to check. Coach never visits the page.",
            BookmarkletText.INTRO
        )
        assertEquals("Send to Coach", BookmarkletText.LINK_LABEL)
        assertEquals(
            listOf(
                "1. Tap Copy.",
                "2. Bookmark any page.",
                "3. Edit the bookmark. Name it Send to Coach, and paste over its address.",
                "4. On a recipe page, type Send to Coach in the address bar and tap the bookmark."
            ),
            BookmarkletText.STEPS
        )
        assertEquals("Copy" to "Copied", BookmarkletText.COPY to BookmarkletText.COPIED)
        assertEquals("Done", BookmarkletText.DONE)
    }
}
