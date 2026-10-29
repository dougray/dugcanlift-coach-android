package com.dugcanlift.coach.data

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * Unwraps an `r1` recipe link, the one the "Send to Coach" bookmarklet opens:
 * `dugcanliftcoach://recipe#r1<base64url(deflate-raw(JSON))>`, JSON
 * `{"v":1,"url":"<page address>","jsonld":["<block text>", ...]}`.
 *
 * A port of the kit's reference decoder, `dugcanlift-kit/bookmarklet/recipe-link.mjs`,
 * refusal for refusal, and checked against its fixture pair
 * (`fixtures/recipe-link.txt`, `recipe-link-expected.json`, copied unchanged --
 * never regenerate them). The kit's `bookmarklet/README.md` is the contract.
 *
 * It only unwraps. What comes out is what Safari's share extension hands the
 * iPhones: the page address and the raw `ld+json` blocks. [RecipeLinkImport]
 * passes the blocks to the kit's `RecipeJsonLd`, the reader every Android build
 * shares. Nothing here, and nothing after it, fetches the page.
 *
 * Refuses (null) rather than guessing: no `r1` prefix, bad base64url, bad
 * deflate, more than [MAX_BYTES] once inflated, JSON that is not `v: 1` with an
 * `http`/`https` `url` and a `jsonld` array, or no string blocks. A non-string
 * block is dropped, not fatal.
 */
object RecipeLink {
    const val PREFIX = "r1"
    const val MAX_BYTES = 512 * 1024

    data class Decoded(val url: String, val jsonld: List<String>)

    private val BASE64URL = Regex("^[A-Za-z0-9_-]+$")
    private val HTTP = Regex("^https?://", RegexOption.IGNORE_CASE)

    /** True when [fragment] (the text after `#`) is a recipe link's, whatever it carries. A log
     *  link's fragment begins `1z` or `1u`, so the two can never be read as each other. */
    fun isRecipeFragment(fragment: String): Boolean = fragment.startsWith(PREFIX)

    fun decode(text: String): Decoded? {
        val hash = text.indexOf('#')
        val fragment = if (hash >= 0) text.substring(hash + 1) else text
        if (!fragment.startsWith(PREFIX)) return null
        val data = fragment.substring(PREFIX.length)
        if (!BASE64URL.matches(data)) return null

        val bytes = try {
            Base64.getUrlDecoder().decode(data)
        } catch (e: IllegalArgumentException) {
            return null
        }
        val json = inflateRaw(bytes) ?: return null

        val doc = try {
            JSONTokener(json).nextValue() as? JSONObject
        } catch (e: Exception) {
            null
        } ?: return null
        // `v === 1` in the reference: a number equal to one, never "1" or true.
        val v = doc.opt("v")
        if (v !is Number || v.toDouble() != 1.0) return null
        val url = doc.opt("url") as? String ?: return null
        val blocks = doc.opt("jsonld") as? JSONArray ?: return null
        if (!HTTP.containsMatchIn(url)) return null
        val jsonld = (0 until blocks.length()).mapNotNull { blocks.opt(it) as? String }
        if (jsonld.isEmpty()) return null
        return Decoded(url, jsonld)
    }

    /**
     * Raw deflate (no zlib header, `Inflater(true)`), refusing anything that does not finish or
     * that inflates past [MAX_BYTES] -- `inflateRawSync(..., { maxOutputLength })`. Stops reading
     * as soon as the cap is passed, so a small link cannot make this allocate a large one.
     */
    private fun inflateRaw(input: ByteArray): String? {
        val inflater = Inflater(true)
        try {
            inflater.setInput(input)
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            while (!inflater.finished()) {
                val n = try {
                    inflater.inflate(buffer)
                } catch (e: DataFormatException) {
                    return null
                }
                if (n == 0) {
                    // Out of input (truncated) or asking for a dictionary: either way not a
                    // stream the bookmarklet wrote.
                    if (inflater.needsInput() || inflater.needsDictionary()) return null
                }
                out.write(buffer, 0, n)
                if (out.size() > MAX_BYTES) return null
            }
            return String(out.toByteArray(), Charsets.UTF_8)
        } finally {
            inflater.end()
        }
    }
}
