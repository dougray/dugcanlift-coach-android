package com.dugcanlift.coach.data
import com.dugcanlift.kit.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LinkFragmentTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun freshFragment() =
        ShareLinkCodec.encodeFragment(SharePayload(ShareClient("a1b2c3d4", "Doug", platform = "ios"), null, "2026-09-01", "2026-09-13", 1, emptyList()))

    @Test fun `a full share URL reduces to its fragment`() =
        assertEquals("1zABCDEF", fragmentFrom("https://www.dugcanlift.com/coach/#1zABCDEF"))

    @Test fun `a bare fragment passes through unchanged`() =
        assertEquals("1zABCDEF", fragmentFrom("1zABCDEF"))

    @Test fun `trailing whitespace after the fragment is trimmed`() =
        assertEquals("1zABCDEF", fragmentFrom("https://www.dugcanlift.com/coach/#1zABCDEF   \n"))

    @Test fun `only the text after the last hash is kept when there are multiple`() =
        assertEquals("last", fragmentFrom("https://example.com/#first#second#last"))

    @Test fun `surrounding whitespace around a bare fragment is trimmed`() =
        assertEquals("1zABCDEF", fragmentFrom("  1zABCDEF  "))

    @Test fun `an empty string yields an empty fragment`() =
        assertEquals("", fragmentFrom("   "))

    @Test fun `a caption trailing the link after the hash is dropped`() =
        assertEquals("XYZ777", fragmentFrom("Check my week -> https://www.dugcanlift.com/coach/#XYZ777 thanks!"))

    @Test fun `a caption trailing a bare hash fragment is dropped too`() =
        assertEquals("1zABCDEF", fragmentFrom("#1zABCDEF sent via LIFT"))

    // --- Finding 2: a mail client that wraps a pasted link in angle brackets, or a sentence that
    // ends with it, leaves a trailing character that is not part of the base64url alphabet. iOS
    // (dugcanlift-kit ShareLink.swift:132) trims at the first such character; match it here instead
    // of only cutting at whitespace, so a link copied out of Mail imports the same on both devices.

    @Test fun `a trailing angle bracket from a mail client is dropped`() =
        assertEquals("1zABCDEF", fragmentFrom("https://www.dugcanlift.com/coach/#1zABCDEF>"))

    @Test fun `a trailing full stop ending a sentence is dropped`() =
        assertEquals("1zABCDEF", fragmentFrom("Check my week: https://www.dugcanlift.com/coach/#1zABCDEF."))

    @Test fun `a trailing close-paren is dropped`() =
        assertEquals("1zABCDEF", fragmentFrom("(https://www.dugcanlift.com/coach/#1zABCDEF)"))

    @Test fun `a clean fragment with no trailing junk is unchanged`() =
        assertEquals("1zABCDEF", fragmentFrom("https://www.dugcanlift.com/coach/#1zABCDEF"))

    // --- degenerate input must reduce to something the decoder rejects, not something valid-looking ---

    @Test fun `a literal empty string reduces to an empty fragment the decoder rejects`() {
        val reduced = fragmentFrom("")
        assertEquals("", reduced)
        assertEquals(ImportResult.Malformed, ShareLinkImporter.import(reduced, ClientRepository(tmp.root)))
    }

    @Test fun `a lone hash with nothing after it reduces to an empty fragment the decoder rejects`() {
        val reduced = fragmentFrom("#")
        assertEquals("", reduced)
        assertEquals(ImportResult.Malformed, ShareLinkImporter.import(reduced, ClientRepository(tmp.root)))
    }

    // --- Bare-fragment parity: iOS's decode(link:) (ShareLink.swift:169-174) routes a bare
    // fragment through the same decode(fragment:) as a #-bearing URL, so the same base64url
    // trim (ShareLink.swift:132) applies whether or not a `#` was present. A bare fragment pasted
    // with trailing punctuation must be cut and still import, matching a #-bearing link with the
    // same trailing junk. ---

    @Test fun `a bare fragment with a trailing full stop is cut to a valid fragment and imports`() {
        val repo = ClientRepository(tmp.root)
        val fragment = freshFragment()
        val reduced = fragmentFrom("$fragment.")
        assertEquals(fragment, reduced)
        assertTrue(ShareLinkImporter.import(reduced, repo) is ImportResult.Imported)
    }

    @Test fun `a bare fragment with a trailing angle bracket is cut to a valid fragment and imports`() {
        val repo = ClientRepository(tmp.root)
        val fragment = freshFragment()
        val reduced = fragmentFrom("$fragment>")
        assertEquals(fragment, reduced)
        assertTrue(ShareLinkImporter.import(reduced, repo) is ImportResult.Imported)
    }

    @Test fun `a bare fragment with a trailing close-paren is cut to a valid fragment and imports`() {
        val repo = ClientRepository(tmp.root)
        val fragment = freshFragment()
        val reduced = fragmentFrom("$fragment)")
        assertEquals(fragment, reduced)
        assertTrue(ShareLinkImporter.import(reduced, repo) is ImportResult.Imported)
    }

    @Test fun `a clean bare fragment passes through unchanged and imports successfully`() {
        val repo = ClientRepository(tmp.root)
        val fragment = freshFragment()
        val reduced = fragmentFrom(fragment)
        assertEquals(fragment, reduced)
        assertTrue(ShareLinkImporter.import(reduced, repo) is ImportResult.Imported)
    }
}

class FragmentToImportTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `a VIEW intent's data fragment is used as-is, no matter the action or extras`() =
        assertEquals("XYZ777", fragmentToImport(dataFragment = "XYZ777", action = "android.intent.action.VIEW", extraText = null))

    @Test fun `a data fragment wins even when SEND extras are also present`() =
        assertEquals("XYZ777", fragmentToImport(dataFragment = "XYZ777", action = "android.intent.action.SEND", extraText = "ignored#nope"))

    @Test fun `ACTION_SEND with a full URL containing a fragment extracts just the fragment`() =
        assertEquals(
            "XYZ777",
            fragmentToImport(dataFragment = null, action = "android.intent.action.SEND", extraText = "https://www.dugcanlift.com/coach/#XYZ777")
        )

    // Was pinned to the literal intermediate string ("https://www.dugcanlift.com/coach/"), an
    // implementation detail no user ever observes -- both the pre-fix (whitespace-only trim, which
    // keeps the whole string) and post-fix (base64url-cut, which stops at "https") reductions of
    // this input start with 'h' where the codec expects the version character '1', so both fail
    // to import the same way (ImportResult.UnsupportedVersion, decoded via ShareLinkImporter.import
    // exactly as RosterScreen calls it on a real share). What actually matters, and what iOS also
    // guarantees, is that sharing a fragment-less URL never imports successfully; assert that end
    // to end instead of pinning the intermediate value, so a correct change to the reduction (like
    // the base64url-cut fix applied to bare fragments below) doesn't have to fight this test.
    @Test fun `ACTION_SEND with a URL that has no fragment fails to import`() {
        val fragment = fragmentToImport(dataFragment = null, action = "android.intent.action.SEND", extraText = "https://www.dugcanlift.com/coach/")
        assertEquals(ImportResult.UnsupportedVersion, ShareLinkImporter.import(fragment!!, ClientRepository(tmp.root)))
    }

    @Test fun `ACTION_SEND with shared text that is a bare fragment imports it directly`() =
        assertEquals("XYZ777", fragmentToImport(dataFragment = null, action = "android.intent.action.SEND", extraText = "XYZ777"))

    @Test fun `ACTION_SEND with surrounding caption words keeps only the fragment token`() =
        assertEquals(
            "XYZ777",
            fragmentToImport(
                dataFragment = null,
                action = "android.intent.action.SEND",
                extraText = "Check my week -> https://www.dugcanlift.com/coach/#XYZ777 thanks!"
            )
        )

    @Test fun `a null EXTRA_TEXT on ACTION_SEND yields no import`() =
        assertNull(fragmentToImport(dataFragment = null, action = "android.intent.action.SEND", extraText = null))

    @Test fun `an empty EXTRA_TEXT on ACTION_SEND yields no import`() =
        assertNull(fragmentToImport(dataFragment = null, action = "android.intent.action.SEND", extraText = ""))

    @Test fun `a non-SEND, non-VIEW-with-data action yields no import`() =
        assertNull(fragmentToImport(dataFragment = null, action = "android.intent.action.MAIN", extraText = "XYZ777"))
}
