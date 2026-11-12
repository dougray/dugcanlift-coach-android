package com.dugcanlift.coach

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The scanner behind "no app source opens a connection" must itself catch what it names. */
class NoAppConnectionTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun offendersIn(name: String, body: String): List<String> {
        val root = tmp.newFolder()
        File(root, name).writeText(body)
        return NoAppConnection.offenders(root)
    }

    @Test fun `it fails on okhttp3`() =
        assertTrue(offendersIn("A.kt", "import okhttp3.Request").any { it.endsWith("okhttp3") })

    @Test fun `it fails on openConnection`() =
        assertTrue(offendersIn("A.kt", "val c = u.openConnection()").any { it.endsWith("openConnection") })

    @Test fun `it fails on a dot openConnection call`() =
        assertTrue(NoAppConnection.spellingsIn("x.openConnection(").contains(".openConnection("))

    @Test fun `it fails on java net URL`() =
        assertTrue(offendersIn("A.java", "new java.net.URL(\"x\")").any { it.endsWith("java.net.URL(") })

    @Test fun `it fails on openfreemap in a manifest or json too`() {
        assertTrue(offendersIn("m.xml", "<!-- tiles.openfreemap.org -->").isNotEmpty())
        assertTrue(offendersIn("d.json", "{\"u\":\"openfreemap\"}").isNotEmpty())
    }

    @Test fun `it passes clean source and the real app is clean`() {
        assertEquals(emptyList<String>(), offendersIn("A.kt", "fun main() = println(\"hi\")"))
        assertEquals(emptyList<String>(), NoAppConnection.offenders())
    }
}
