package com.dugcanlift.coach.data
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * Pins Coach iOS's BackupCodec v2 file format (coach-ios/Sources/Shared/BackupCodec.swift): the
 * 2001-epoch date conversion, opaque round-tripping of the four library arrays Coach Android does
 * not model, and clients restoring by replace.
 *
 * The fixture (fixtures/coach-ios-backup-v2.json) is hand-written from BackupCodec.swift's DTO
 * field names -- not a real export from the simulator, which this task could not run. Its
 * `lastImportedAt` is exactly 780000000 Foundation seconds, matching the constants below.
 */
class BackupCodecTest {
    private fun fixture() = javaClass.getResourceAsStream("/fixtures/coach-ios-backup-v2.json")!!.bufferedReader().readText()

    @Test fun `reads an iOS v2 file and converts 2001-epoch dates`() {
        val r = BackupCodec.restore(fixture())
        val c = r.clients.single()
        // The fixture's lastImportedAt is 780000000 (Foundation) == 2025-09-19T... Unix 1758307200.
        assertEquals(780_000_000L + 978_307_200L, c.lastImportedAtEpochMs / 1000)
    }

    @Test fun `the library arrays survive a round trip untouched`() {
        val r = BackupCodec.restore(fixture())
        val out = JSONObject(BackupCodec.export(r.clients, r.preservedLibrary))
        val orig = JSONObject(fixture())
        for (k in listOf("recipes", "meals", "routines", "sessions")) assertEquals(orig.opt(k)?.toString(), out.opt(k)?.toString())
        assertEquals(2, out.getInt("v"))
    }

    @Test fun `a v1 file with no library restores clients and writes no library keys`() {
        val v1 = """{"v":1,"clients":[{"id":"a","name":"Doug","displayUnit":"lb","lastImportedAt":0,"days":[]}]}"""
        val out = JSONObject(BackupCodec.export(BackupCodec.restore(v1).clients, null))
        assertFalse(out.has("recipes")); assertEquals("a", out.getJSONArray("clients").getJSONObject(0).getString("id"))
    }

    @Test fun `dates written back are Foundation seconds so iOS reads them`() {
        val c = Client("a", "Doug", "lb", null, 1_758_307_200_000L, null, emptyList())
        assertEquals(780_000_000.0, JSONObject(BackupCodec.export(listOf(c), null)).getJSONArray("clients").getJSONObject(0).getDouble("lastImportedAt"), 0.5)
    }

    // --- Beyond the brief's four ---

    @Test fun `a file whose clients array is missing entirely restores an empty roster, not a crash`() {
        val r = BackupCodec.restore("""{"v":2}""")
        assertTrue(r.clients.isEmpty())
        assertNull(r.preservedLibrary)
    }

    @Test fun `a client with no goal restores and re-exports with goal null`() {
        val json = """{"v":2,"clients":[{"id":"g1","name":"No Goal","displayUnit":"lb","lastImportedAt":0,"days":[]}]}"""
        val r = BackupCodec.restore(json)
        val c = r.clients.single()
        assertNull(c.goal)
        val out = JSONObject(BackupCodec.export(r.clients, r.preservedLibrary))
        assertTrue(out.getJSONArray("clients").getJSONObject(0).isNull("goal"))
    }

    @Test fun `a day carrying both food totals and itemized entries stores both independently`() {
        val r = BackupCodec.restore(fixture())
        val day = r.clients.single().days.single()
        assertEquals(2410.0, day.foodCalories!!, 0.0)
        assertEquals(188.0, day.foodProteinG!!, 0.0)
        assertEquals(1, day.foodEntries.size)
        assertEquals("Chicken Breast", day.foodEntries[0].foodName)
        assertEquals(2, day.sets.size)
    }
}
