package com.dugcanlift.coach.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import java.util.zip.Deflater

/**
 * [RecipeLink] against the kit's reference decoder, `bookmarklet/recipe-link.mjs`, case for case
 * with `recipe-link.test.mjs`. `fixtures/recipe-link.txt` is a link **the bookmarklet itself
 * wrote** for the kit's `page-jsonld.json`, and `recipe-link-expected.json` what it decodes to;
 * both are copied unchanged from dugcanlift-kit. Never regenerate them from Kotlin: a fixture
 * written by the code under test proves only that the code agrees with itself.
 */
class RecipeLinkTest {

    private fun fixture(name: String) =
        javaClass.classLoader!!.getResource("fixtures/$name")!!.readText()

    private fun encode(doc: JSONObject): String = "#r1" + encodeRaw(doc.toString())

    private fun encodeRaw(json: String): String {
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        deflater.setInput(json.toByteArray(Charsets.UTF_8))
        deflater.finish()
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
        deflater.end()
        return Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray())
    }

    private fun ok() = JSONObject()
        .put("v", 1).put("url", "https://a.example/r").put("jsonld", JSONArray().put("{\"@type\":\"Recipe\"}"))

    @Test fun `the fixture link decodes to the expected page`() {
        val expected = JSONObject(fixture("recipe-link-expected.json"))
        val decoded = RecipeLink.decode(fixture("recipe-link.txt").trim())
        assertNotNull(decoded)
        assertEquals(expected.getString("url"), decoded!!.url)
        val blocks = expected.getJSONArray("jsonld")
        assertEquals((0 until blocks.length()).map { blocks.getString(it) }, decoded.jsonld)
    }

    @Test fun `the fixture pair is byte for byte the kit's`() {
        // sha256 of dugcanlift-kit's bookmarklet/fixtures files (kit PR #26, a52177e). A copy
        // edited here, or regenerated, fails this before it can agree with itself.
        fun sha(name: String) = java.security.MessageDigest.getInstance("SHA-256")
            .digest(File("src/test/resources/fixtures/$name").readBytes())
            .joinToString("") { "%02x".format(it) }
        assertEquals("4fc1a14a9dadcf1ae250740161fa0aa3730778025d07e875bde043cbfc92401f", sha("recipe-link.txt"))
        assertEquals("090abc06cea476b08029b6edb13f75328cf66810d4e0de4cbef985e9d9bcef2c", sha("recipe-link-expected.json"))
    }

    @Test fun `Coach's own target decodes the same payload`() {
        // The fixture was written for liftApp; coachApp differs only before the #.
        val link = fixture("recipe-link.txt").trim()
        val coach = "dugcanliftcoach://recipe" + link.substring(link.indexOf('#'))
        assertEquals(RecipeLink.decode(link), RecipeLink.decode(coach))
        assertEquals(RecipeLink.decode(link), RecipeLink.decode(link.substring(link.indexOf('#') + 1)))
    }

    @Test fun refusals() {
        assertNotNull(RecipeLink.decode(encode(ok())))
        assertNull("no fragment", RecipeLink.decode("dugcanliftcoach://recipe"))
        assertNull("a log link is not a recipe link", RecipeLink.decode("#1zabc"))
        assertNull("not base64url", RecipeLink.decode("#r1!!!"))
        assertNull("not deflate", RecipeLink.decode("#r1AAAA"))
        assertNull("unknown version", RecipeLink.decode(encode(ok().put("v", 2))))
        assertNull("version as a string", RecipeLink.decode(encode(ok().put("v", "1"))))
        assertNull("not http(s)", RecipeLink.decode(encode(ok().put("url", "javascript:alert(1)"))))
        assertNull("url not a string", RecipeLink.decode(encode(ok().put("url", 7))))
        assertNull("jsonld not an array", RecipeLink.decode(encode(ok().put("jsonld", "x"))))
        assertNull("no blocks", RecipeLink.decode(encode(ok().put("jsonld", JSONArray()))))
        assertNull(
            "no string blocks",
            RecipeLink.decode(encode(ok().put("jsonld", JSONArray().put(3).put(JSONObject.NULL))))
        )
        assertNull("not an object", RecipeLink.decode("#r1" + encodeRaw("[1,2]")))
        assertNull("not JSON", RecipeLink.decode("#r1" + encodeRaw("hello")))
        assertNull("empty", RecipeLink.decode("#r1"))
        assertNull("truncated deflate", RecipeLink.decode(encode(ok()).dropLast(6)))
    }

    @Test fun `http as well as https, in any case`() {
        assertNotNull(RecipeLink.decode(encode(ok().put("url", "http://a.example/r"))))
        assertNotNull(RecipeLink.decode(encode(ok().put("url", "HTTPS://A.example/r"))))
    }

    @Test fun `a non-string block is dropped, not fatal`() {
        val doc = ok().put("url", "http://a.example/r")
            .put("jsonld", JSONArray().put(5).put("{\"@type\":\"Recipe\"}"))
        assertEquals(listOf("{\"@type\":\"Recipe\"}"), RecipeLink.decode(encode(doc))!!.jsonld)
    }

    @Test fun `more than MAX_BYTES once inflated is refused, and exactly MAX_BYTES is not`() {
        fun sized(total: Int): String {
            val head = "{\"v\":1,\"url\":\"https://a.example/r\",\"jsonld\":[\""
            val tail = "\"]}"
            return head + "x".repeat(total - head.length - tail.length) + tail
        }
        assertEquals(RecipeLink.MAX_BYTES, sized(RecipeLink.MAX_BYTES).length)
        assertNotNull(RecipeLink.decode("#r1" + encodeRaw(sized(RecipeLink.MAX_BYTES))))
        assertNull(RecipeLink.decode("#r1" + encodeRaw(sized(RecipeLink.MAX_BYTES + 1))))
    }

    @Test fun `only an r1 fragment is a recipe fragment`() {
        assertTrue(RecipeLink.isRecipeFragment("r1abc"))
        assertFalse(RecipeLink.isRecipeFragment("1zabc"))
        assertFalse(RecipeLink.isRecipeFragment("1uabc"))
        assertFalse(RecipeLink.isRecipeFragment(""))
    }
}
