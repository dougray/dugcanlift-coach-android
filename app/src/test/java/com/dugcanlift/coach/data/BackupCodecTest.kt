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

    @Test fun `a key this codec does not model survives untouched`() {
        // All four library arrays are modelled now -- recipes and meals since
        // Cook, routines and sessions since Train -- so the byte-for-byte
        // guarantee has to be demonstrated with a key nothing understands.
        // That is the case it exists for: a future Coach iOS array nobody here
        // has heard of must not be dropped by the next Save Backup.
        val withCargo = JSONObject(fixture())
            .put("programs", org.json.JSONArray().put(JSONObject().put("id", "p9").put("name", "Block A")))
        val r = BackupCodec.restore(withCargo.toString())
        val out = JSONObject(BackupCodec.export(r.clients, r.preservedLibrary, r.recipes, r.meals, r.routines, r.sessions))
        assertEquals(withCargo.getJSONArray("programs").toString(), out.getJSONArray("programs").toString())
        assertEquals(2, out.getInt("v"))
    }

    @Test fun `the modelled arrays survive a round trip by value`() {
        val orig = JSONObject(fixture())
        val r = BackupCodec.restore(fixture())
        val out = JSONObject(BackupCodec.export(r.clients, r.preservedLibrary, r.recipes, r.meals, r.routines, r.sessions))

        for (key in listOf("recipes", "meals", "routines", "sessions")) {
            val before = orig.optJSONArray(key) ?: continue
            val after = out.getJSONArray(key)
            assertEquals("$key lost or gained rows", before.length(), after.length())
            for (i in 0 until before.length()) {
                // Every field the fixture carried is still there afterwards,
                // whether or not this app has a model for it. Key ORDER is not
                // asserted: org.json does not preserve it and it carries no
                // meaning, but a missing key would be real data loss.
                assertSurvives("$key[$i]", before.getJSONObject(i), after.getJSONObject(i))
            }
        }
    }

    @Test fun `a v1 file with no library restores clients and writes no library keys`() {
        val v1 = """{"v":1,"clients":[{"id":"a","name":"Doug","displayUnit":"lb","lastImportedAt":0,"days":[]}]}"""
        val out = JSONObject(BackupCodec.export(BackupCodec.restore(v1).clients, null, emptyList(), emptyList(), emptyList(), emptyList()))
        assertFalse(out.has("recipes")); assertEquals("a", out.getJSONArray("clients").getJSONObject(0).getString("id"))
    }

    @Test fun `dates written back are Foundation seconds so iOS reads them`() {
        val c = Client("a", "Doug", "lb", null, 1_758_307_200_000L, null, emptyList())
        assertEquals(780_000_000.0, JSONObject(BackupCodec.export(listOf(c), null, emptyList(), emptyList(), emptyList(), emptyList())).getJSONArray("clients").getJSONObject(0).getDouble("lastImportedAt"), 0.5)
    }

    // --- Beyond the brief's four ---

    // --- Finding 1: a missing top-level `clients` key must fail the decode, exactly like iOS's
    // non-optional `var clients: [BackupClient]` (BackupCodec.swift:15) does -- not decode to an
    // empty roster, which BackupService.restore would then pass straight to
    // ClientRepository.replaceAll and delete every client already on the device. An explicitly
    // empty `"clients": []` is a legitimate file (iOS accepts it too) and must still restore as an
    // empty roster, not fail.

    @Test(expected = Exception::class) fun `a file whose clients key is missing entirely fails to decode`() {
        BackupCodec.restore("""{"v":2}""")
    }

    @Test(expected = Exception::class) fun `a file whose clients key is not an array fails to decode`() {
        BackupCodec.restore("""{"v":2,"clients":"oops"}""")
    }

    @Test fun `a file with an explicitly empty clients array restores an empty roster`() {
        val r = BackupCodec.restore("""{"v":2,"clients":[]}""")
        assertTrue(r.clients.isEmpty())
        assertNull(r.preservedLibrary)
    }

    @Test fun `a client with no goal restores and re-exports with goal null`() {
        val json = """{"v":2,"clients":[{"id":"g1","name":"No Goal","displayUnit":"lb","lastImportedAt":0,"days":[]}]}"""
        val r = BackupCodec.restore(json)
        val c = r.clients.single()
        assertNull(c.goal)
        val out = JSONObject(BackupCodec.export(r.clients, r.preservedLibrary, r.recipes, r.meals, r.routines, r.sessions))
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

    // --- Item 4: Coach Android reads `days` more leniently than iOS's non-optional [BackupDay]
    // would -- see the doc comment on BackupCodec.clientFromJson for why that is a deliberate,
    // pinned choice rather than an accident.

    @Test fun `a client object missing its days key restores with an empty list, not a crash`() {
        val json = """{"v":2,"clients":[{"id":"nodays","name":"No Days","displayUnit":"lb","lastImportedAt":0}]}"""
        val r = BackupCodec.restore(json)
        assertEquals(emptyList<TrainingDay>(), r.clients.single().days)
    }

    // --- Item 5: an absent or null lastImportedAt falls back to "now", never a crash or a bogus
    // 1970/2001 date.

    @Test fun `a missing lastImportedAt falls back to roughly the current time`() {
        val json = """{"v":2,"clients":[{"id":"a","name":"Doug","displayUnit":"lb","days":[]}]}"""
        val before = System.currentTimeMillis()
        val c = BackupCodec.restore(json).clients.single()
        val after = System.currentTimeMillis()
        assertTrue(c.lastImportedAtEpochMs in before..after)
    }

    @Test fun `a null lastImportedAt falls back to roughly the current time`() {
        val json = """{"v":2,"clients":[{"id":"a","name":"Doug","displayUnit":"lb","lastImportedAt":null,"days":[]}]}"""
        val before = System.currentTimeMillis()
        val c = BackupCodec.restore(json).clients.single()
        val after = System.currentTimeMillis()
        assertTrue(c.lastImportedAtEpochMs in before..after)
    }

    // --- Round 6 [I-9]: this is [C-1]'s failure class reached through unknown top-level keys. A
    // newer Coach iOS file's own arrays must round-trip untouched, exactly as the four library
    // arrays do, or the next Save Backup writes them away permanently.
    @Test fun `an unknown top-level key from a newer file survives restore and export`() {
        val newer = """{"v":3,"clients":[],"programs":[{"id":"p1","name":"5-3-1"}],"coachNotes":{"x":1}}"""
        val r = BackupCodec.restore(newer)
        val out = JSONObject(BackupCodec.export(r.clients, r.preservedLibrary, r.recipes, r.meals, r.routines, r.sessions))
        assertEquals(JSONObject(newer).getJSONArray("programs").toString(), out.getJSONArray("programs").toString())
        assertEquals(JSONObject(newer).getJSONObject("coachNotes").toString(), out.getJSONObject("coachNotes").toString())
    }

    // `v` and `clients` are this codec's own envelope, not cargo -- they must not be duplicated
    // into the preserved set and written back out from two places.
    @Test fun `the codec's own envelope keys are not preserved as unknown cargo`() {
        val r = BackupCodec.restore("""{"v":2,"clients":[],"programs":[]}""")
        assertFalse(r.preservedLibrary!!.has("v"))
        assertFalse(r.preservedLibrary!!.has("clients"))
    }

    /**
     * Every field in [before] is still present in [after], with the same value,
     * however deeply nested.
     *
     * Deliberately one-directional: [after] gaining a key is not loss, so it is
     * not asserted. And deliberately not a string comparison of the whole
     * object -- org.json does not preserve key order, and the fixture writes
     * `2.0` where org.json re-emits `2`. Neither is a lost field; a missing key
     * is, which is the only thing this checks.
     */
    private fun assertSurvives(path: String, before: JSONObject, after: JSONObject) {
        for (field in before.keys()) {
            val expected = before.get(field)
            val actual = after.opt(field)
            when {
                // An explicit null that comes back absent is not loss: both
                // decode to null in every reader of this format, and omitting
                // them is what keeps a prescribed set from carrying five nulls
                // for the four fields it does not use. The fixture writes them
                // because Swift's Codable does; this app does not.
                expected === JSONObject.NULL && actual == null -> Unit
                expected is JSONObject && actual is JSONObject ->
                    assertSurvives("$path.$field", expected, actual)
                // Arrays too. `exercises` is one, and its elements are objects
                // whose own null-valued keys this app omits -- so comparing the
                // array as a string fails for the same reason a bare object
                // would, one level further in.
                expected is org.json.JSONArray && actual is org.json.JSONArray -> {
                    assertEquals("$path.$field length", expected.length(), actual.length())
                    for (i in 0 until expected.length()) {
                        val b = expected.opt(i)
                        val a = actual.opt(i)
                        if (b is JSONObject && a is JSONObject) assertSurvives("$path.$field[$i]", b, a)
                        else assertEquals("$path.$field[$i]", b?.toString(), a?.toString())
                    }
                }
                expected is Number && actual is Number ->
                    assertEquals("$path.$field", expected.toDouble(), actual.toDouble(), 1e-9)
                else -> assertEquals("$path.$field", expected.toString(), actual?.toString())
            }
        }
    }

}
