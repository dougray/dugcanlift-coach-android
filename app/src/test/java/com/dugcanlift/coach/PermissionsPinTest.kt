package com.dugcanlift.coach

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * Every permission Coach Android ships with, pinned to an explicit list that the public privacy
 * policy has to match.
 *
 * The page is `www.dugcanlift.com/coach/privacy/` (`dugcanlift-site/coach-privacy.md`), section
 * "Network requests, by platform" > "Android": "None. The Android app has no internet permission
 * and makes no network requests of any kind." Coach asks the user for nothing at all -- a link or
 * a backup file is everything it reads. **A new permission fails this test until someone has
 * updated that page.** The page is public copy and Doug reviews it, so change `coach-privacy.md`
 * first and this list second, never the list alone.
 *
 * This is the merged-manifest form of the no-INTERNET guards in `InboundLinkTest` and
 * `RecipeCatalogTest`, which read `src/main/AndroidManifest.xml`. Those catch a permission written
 * into Coach's own manifest; this catches one a library brings in, because it reads the release
 * variant's **merged** manifest -- Coach's plus every dependency's, which is what an installed APK
 * declares. `app/build.gradle.kts` hands its path across as `releaseMergedManifest` and declares it
 * an input of every unit test task, so `testDebugUnitTest` runs `processReleaseManifest` first.
 */
class PermissionsPinTest {

    private val expected: Set<String> = setOf(
        // Not a request to the user or to the system: androidx.core declares this signature-level
        // permission and holds it itself, so a receiver registered NOT_EXPORTED on Android 12 and
        // older is protected from other apps. Only an app signed with Coach's key can hold it.
        "com.dugcanlift.coach.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
    )

    private val page = "dugcanlift-site/coach-privacy.md, \"Network requests, by platform\" > \"Android\""

    private val android = "http://schemas.android.com/apk/res/android"

    private val manifest: Element by lazy {
        val path = System.getProperty("releaseMergedManifest")
        requireNotNull(path) { "releaseMergedManifest is not set: see app/build.gradle.kts" }
        val file = File(path)
        assertTrue("merged manifest not found at ${file.absolutePath}", file.isFile)
        DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(file)
            .documentElement
    }

    private fun elements(tag: String): List<Element> {
        val nodes = manifest.getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    /** A permission and the qualifiers that change what it grants, written the way [expected] is. */
    private fun describe(element: Element): String {
        val qualifiers = listOf("maxSdkVersion", "minSdkVersion", "usesPermissionFlags")
            .mapNotNull { name -> element.getAttributeNS(android, name).takeIf { it.isNotEmpty() }?.let { "$name=$it" } }
        return (listOf(element.getAttributeNS(android, "name")) + qualifiers).joinToString(" ")
    }

    @Test
    fun `the release APK asks for exactly what the privacy page describes`() {
        // uses-permission-sdk-23 is the same request, limited to API 23 and up; it counts.
        val declared = (elements("uses-permission") + elements("uses-permission-sdk-23")).map(::describe)
        assertEquals("a permission is declared twice: $declared", declared.size, declared.toSet().size)
        assertEquals(
            "The release APK's permissions changed. Update $page first, then this list.",
            expected.sorted().joinToString("\n"),
            declared.sorted().joinToString("\n"),
        )
    }

    @Test
    fun `the only permission Coach defines is androidx's signature-level receiver guard`() {
        val defined = elements("permission").associate {
            it.getAttributeNS(android, "name") to it.getAttributeNS(android, "protectionLevel")
        }
        assertEquals(
            mapOf("com.dugcanlift.coach.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION" to "signature"),
            defined,
        )
    }

    @Test
    fun `this is the release manifest, merged`() {
        // The release application id, not the debug build's ".debug" one: what ships is pinned.
        assertEquals("com.dugcanlift.coach", manifest.getAttribute("package"))
        // Merging writes the libraries' components into the file (androidx.startup's provider,
        // profileinstaller's receiver); src/main names none of them.
        assertTrue(
            "this does not look like a merged manifest",
            listOf("activity", "provider", "receiver", "service").flatMap(::elements)
                .any { it.getAttributeNS(android, "name").startsWith("androidx.") },
        )
    }
}
