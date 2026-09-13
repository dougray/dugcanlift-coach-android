package com.dugcanlift.coach.data

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

    @Test fun `a second v2 restore replaces the cached library`() {
        PreservedLibraryStore.update(file(), JSONObject().put("recipes", "old"))
        val newer = JSONObject().put("recipes", "new")
        PreservedLibraryStore.update(file(), newer)
        assertEquals(newer.toString(), PreservedLibraryStore.load(file())?.toString())
    }

    @Test fun `no cache file yet loads as null`() {
        assertNull(PreservedLibraryStore.load(file()))
    }
}
