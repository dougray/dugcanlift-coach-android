package com.dugcanlift.coach.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Pins the rule coach-ios's BackupCodec.swift documents for the library: a restore that carries
 * NO library must leave the device's cached copy untouched, never delete it -- see
 * [RestoreResult.preservedLibrary] and [BackupCodec]. This is the only state spanning two separate
 * user actions (Restore now, Save later), which is why it gets its own file-level tests instead of
 * only Compose-level ones.
 */
class PreservedLibraryStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun file(): File = File(tmp.root, "preserved-library.json")

    @Test fun `restoring a v2 file with a library stores it`() {
        val library = JSONObject().put("recipes", "stub")
        PreservedLibraryStore.update(file(), library)
        assertEquals(library.toString(), PreservedLibraryStore.load(file())?.toString())
    }

    @Test fun `restoring a v1 file afterwards leaves the cached library intact`() {
        val library = JSONObject().put("recipes", "stub")
        PreservedLibraryStore.update(file(), library)

        // A v1 restore (or a v2 restore of a file with no library keys) decodes to a null
        // preservedLibrary -- see RestoreResult -- and must never wipe the cache.
        PreservedLibraryStore.update(file(), null)

        assertEquals(library.toString(), PreservedLibraryStore.load(file())?.toString())
    }

    @Test fun `a later export still emits the preserved arrays byte-identically`() {
        val library = JSONObject().put("recipes", "stub").put("meals", "stub")
        PreservedLibraryStore.update(file(), library)

        val reloaded = PreservedLibraryStore.load(file())
        val out = JSONObject(BackupCodec.export(emptyList(), reloaded))
        for (k in listOf("recipes", "meals")) assertEquals(library.opt(k)?.toString(), out.opt(k)?.toString())
    }

    @Test fun `no cache file yet loads as null`() {
        assertNull(PreservedLibraryStore.load(file()))
    }

    // --- Round 5: a second v2 restore must MERGE by id, the way coach-ios's BackupCodec.swift
    // does for its four typed arrays (`restore`, ~lines 244-308: `for row in backup.recipes ?? []
    // where !existingRecipes.contains(row.id)`) -- never replace the cache wholesale. Replacing
    // silently drops any id the cache held that the newer restore's file doesn't mention, which is
    // exactly the "restore an older/thinner backup after a richer one" case a coach hits every time
    // they move between an iPhone and an Android phone.

    private fun entry(id: String, name: String) = JSONObject().put("id", id).put("name", name)

    @Test fun `restoring a thinner backup afterwards preserves the cached ids it does not mention`() {
        val a = entry("A", "Recipe A")
        val b = entry("B", "Recipe B")
        PreservedLibraryStore.update(file(), JSONObject().put("recipes", JSONArray(listOf(a, b))))

        // A later, thinner restore's file carries only A.
        PreservedLibraryStore.update(file(), JSONObject().put("recipes", JSONArray(listOf(entry("A", "Recipe A")))))

        val recipes = PreservedLibraryStore.load(file())!!.getJSONArray("recipes")
        val ids = (0 until recipes.length()).map { recipes.getJSONObject(it).getString("id") }.toSet()
        assertEquals("both A and B must survive", setOf("A", "B"), ids)
    }

    @Test fun `a colliding id keeps the device's cached copy, matching BackupCodec swift's existing-wins rule`() {
        PreservedLibraryStore.update(file(), JSONObject().put("recipes", JSONArray(listOf(entry("A", "Cached Name")))))

        // Incoming file has an entry with the same id "A" but different content, plus a new id "B".
        PreservedLibraryStore.update(
            file(),
            JSONObject().put("recipes", JSONArray(listOf(entry("A", "Incoming Name"), entry("B", "Recipe B"))))
        )

        val recipes = PreservedLibraryStore.load(file())!!.getJSONArray("recipes")
        assertEquals(2, recipes.length())
        val byId = (0 until recipes.length()).associate {
            recipes.getJSONObject(it).getString("id") to recipes.getJSONObject(it).getString("name")
        }
        // Swift's `existingRecipes.contains(row.id)` skips the incoming row entirely on a
        // collision -- the device's copy is never overwritten by an older/other backup's version.
        assertEquals("Cached Name", byId["A"])
        assertEquals("Recipe B", byId["B"])
    }

    @Test fun `a key present in the cache but absent from the incoming file is left untouched`() {
        PreservedLibraryStore.update(file(), JSONObject().put("meals", JSONArray(listOf(entry("M1", "Meal 1")))))

        // Incoming file carries recipes but no meals key at all.
        PreservedLibraryStore.update(file(), JSONObject().put("recipes", JSONArray(listOf(entry("R1", "Recipe 1")))))

        val cached = PreservedLibraryStore.load(file())!!
        assertEquals(1, cached.getJSONArray("meals").length())
        assertEquals("M1", cached.getJSONArray("meals").getJSONObject(0).getString("id"))
        assertEquals(1, cached.getJSONArray("recipes").length())
    }

    @Test fun `a key absent from the cache but present in the incoming file is stored`() {
        PreservedLibraryStore.update(file(), JSONObject().put("recipes", JSONArray(listOf(entry("R1", "Recipe 1")))))

        PreservedLibraryStore.update(file(), JSONObject().put("routines", JSONArray(listOf(entry("RT1", "Routine 1")))))

        val cached = PreservedLibraryStore.load(file())!!
        assertEquals(1, cached.getJSONArray("routines").length())
        assertEquals("RT1", cached.getJSONArray("routines").getJSONObject(0).getString("id"))
        assertEquals(1, cached.getJSONArray("recipes").length())
    }

    @Test fun `an entry with no id at all is never silently dropped from either side`() {
        PreservedLibraryStore.update(file(), JSONObject().put("recipes", JSONArray(listOf(JSONObject().put("name", "No ID Cached")))))

        PreservedLibraryStore.update(file(), JSONObject().put("recipes", JSONArray(listOf(JSONObject().put("name", "No ID Incoming")))))

        // Neither side can be matched by id, so nothing is treated as a duplicate -- both survive.
        val recipes = PreservedLibraryStore.load(file())!!.getJSONArray("recipes")
        assertEquals(2, recipes.length())
    }

    @Test fun `a later export still emits the merged arrays byte-comparably`() {
        PreservedLibraryStore.update(
            file(),
            JSONObject().put("recipes", JSONArray(listOf(entry("A", "A")))).put("meals", JSONArray(listOf(entry("M", "M"))))
        )
        PreservedLibraryStore.update(file(), JSONObject().put("recipes", JSONArray(listOf(entry("B", "B")))))

        val reloaded = PreservedLibraryStore.load(file())
        val out = JSONObject(BackupCodec.export(emptyList(), reloaded))
        for (k in listOf("recipes", "meals")) assertEquals(reloaded?.opt(k)?.toString(), out.opt(k)?.toString())

        val ids = (0 until out.getJSONArray("recipes").length())
            .map { out.getJSONArray("recipes").getJSONObject(it).getString("id") }.toSet()
        assertEquals(setOf("A", "B"), ids)
    }

    // --- Round 6 [C-1]: this cache holds the one thing Coach Android cannot regenerate (the
    // coach's recipes and routines), so it must be written the way ClientRepository.save already
    // writes a client -- staged, then moved into place -- and a cache that cannot be read must be
    // surfaced, never silently equivalent to "this device has no library".

    @Test fun `a staged write that cannot complete leaves the previous cache intact`() {
        PreservedLibraryStore.update(file(), JSONObject().put("recipes", JSONArray(listOf(entry("A", "Recipe A")))))

        // Occupy the staging path so the write fails before the live cache is touched. A bare
        // writeText has no staging file: it truncates the live cache first, which is exactly how a
        // device killed mid-write ends up with a truncated preserved-library.json.
        File(tmp.root, "preserved-library.json.tmp").mkdirs()
        try {
            PreservedLibraryStore.update(file(), JSONObject().put("recipes", JSONArray(listOf(entry("B", "Recipe B")))))
            fail("expected the staged write to fail rather than overwrite the live cache")
        } catch (e: Exception) {
            // expected -- a genuine failure propagates instead of destroying the cache
        }

        val recipes = PreservedLibraryStore.load(file())!!.getJSONArray("recipes")
        assertEquals(1, recipes.length())
        assertEquals("A", recipes.getJSONObject(0).getString("id"))
    }

    @Test fun `a truncated cache is surfaced, never read as no library at all`() {
        // Half a write: valid JSON prefix, no closing braces -- what a kill mid-writeText leaves.
        file().writeText("{\"recipes\": [{\"id\": \"A\"")
        try {
            PreservedLibraryStore.load(file())
            fail("expected an unreadable cache to be reported, not read as null")
        } catch (e: Exception) {
            // expected -- the caller must be able to refuse to export a backup with no library
        }
    }

    // --- Round 6 [I-9]: the cache is where a newer iOS file's unknown top-level keys have to
    // survive between Restore and the next Save Backup, so merge cannot be scoped to four names.
    @Test fun `an unknown top-level key survives the cache merge alongside the known arrays`() {
        PreservedLibraryStore.update(file(), JSONObject().put("recipes", JSONArray(listOf(entry("A", "A")))).put("programs", JSONArray(listOf(entry("P1", "5-3-1")))))
        PreservedLibraryStore.update(file(), JSONObject().put("recipes", JSONArray(listOf(entry("B", "B")))))

        val cached = PreservedLibraryStore.load(file())!!
        assertEquals(1, cached.getJSONArray("programs").length())
        assertEquals("P1", cached.getJSONArray("programs").getJSONObject(0).getString("id"))
    }
}
