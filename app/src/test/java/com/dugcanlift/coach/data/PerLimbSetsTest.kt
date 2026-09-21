package com.dugcanlift.coach.data

import com.dugcanlift.kit.CompactEncoding
import com.dugcanlift.kit.ShareClient
import com.dugcanlift.kit.ShareDay
import com.dugcanlift.kit.ShareExercise
import com.dugcanlift.kit.ShareLinkCodec
import com.dugcanlift.kit.SharePayload
import com.dugcanlift.kit.ShareSet
import com.dugcanlift.kit.ShareSide
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Per-limb sets end to end: the share link's flags bits, the backup file's named field, and the
 * grouping that keeps a left-arm row out of a right-arm row's series.
 *
 * The bit values are asserted against **hand-built raw payloads**, not against this app's own
 * encoder, because the encoders Coach must read are three other apps' (SHARE-FORMAT, "Not every
 * encoder can produce every value": LIFT for Android has no warmup flag and writes only 0, 2 and 4,
 * while the iPhone and the browser can also send 1, 3 and 5). A decoder must handle all of them and
 * must not infer the platform from the byte.
 */
class PerLimbSetsTest {
    @get:Rule val tmp = TemporaryFolder()

    /**
     * A link carrying one Split Squat set whose flags field is exactly [flags] -- or, when [flags]
     * is null, a set tuple that stops at reps, which is every set any encoder wrote before per-limb
     * logging existed.
     *
     * Built as raw JSON and sent uncompressed (`1u`), so the number under test is the number on the
     * wire rather than whatever this build's own encoder would have chosen to write.
     */
    private fun rawLink(flags: Int?): String {
        val tuple = JSONArray().put(60.0).put(8)
        if (flags != null) tuple.put(JSONObject.NULL).put(JSONObject.NULL).put(JSONObject.NULL).put(flags)
        val json = JSONObject()
            .put("v", 1)
            .put("c", JSONObject().put("i", "a1b2c3d4").put("n", "Doug").put("u", "lb"))
            .put("r", "2026-09-01").put("t", "2026-09-01").put("z", 1)
            .put("x", JSONArray().put("Split Squat|Dumbbell"))
            .put("d", JSONArray().put(JSONObject().put("k", 0)
                .put("w", JSONArray().put(JSONArray().put(0).put(JSONArray().put(tuple))))))
        return "1u" + CompactEncoding.base64Url(json.toString().toByteArray())
    }

    private fun importedSet(flags: Int?): ExerciseSet {
        val repo = ClientRepository(tmp.newFolder())
        val result = ShareLinkImporter.import(rawLink(flags), repo)
        assertTrue("flags=$flags did not import: $result", result is ImportResult.Imported)
        return repo.get("a1b2c3d4")!!.days.single().sets.single()
    }

    /* ---------- decode: flags bits 1-2 ---------- */

    // 0 both, 1 left, 2 right in bits 1-2, beside bit 0's warmup flag, so the byte is
    // (side shl 1) or warmup: 0 and 1 are both-sided, 2 and 3 left, 4 and 5 right.
    @Test fun `every flags value an encoder can write decodes to the right side and warmup`() {
        val expected = mapOf(
            0 to (null as SetSide? to false),
            1 to (null as SetSide? to true),
            2 to (SetSide.LEFT to false),
            3 to (SetSide.LEFT to true),
            4 to (SetSide.RIGHT to false),
            5 to (SetSide.RIGHT to true)
        )
        for ((flags, want) in expected) {
            val set = importedSet(flags)
            assertEquals("side for flags=$flags", want.first, set.side)
            assertEquals("warmup for flags=$flags", want.second, set.isWarmup)
        }
    }

    // The warmup bit is read by masking, never by comparing the whole byte. `flags == 1` was right
    // while warmup was the only bit and calls a left-side warmup (3) a working set today.
    @Test fun `a left-side warmup is still a warmup`() {
        val warmupLeft = importedSet(3)
        assertTrue(warmupLeft.isWarmup)
        assertEquals(SetSide.LEFT, warmupLeft.side)
        // And a warmup is excluded from progression whichever limb it was, so its side changes no figure.
        assertNull(Stats.e1rm(warmupLeft))
    }

    // Bits 1-2 holding 3 is a value SHARE-FORMAT does not define and no encoder writes. Reading it
    // as both is what stops a bit added later quietly turning a two-sided set into a left one.
    @Test fun `an undefined side value reads as both, not as a side this build invented`() {
        assertNull(importedSet(6).side)   // bits 1-2 == 3, warmup clear
        assertNull(importedSet(7).side)   // the same, with the warmup bit set
        assertTrue(importedSet(7).isWarmup)
    }

    // The whole point of putting the side in the byte that already exists: a link from before
    // per-limb logging has a six-field tuple truncated after reps, and still reads as a set.
    @Test fun `a link written before per-limb logging reads as both`() {
        val set = importedSet(null)
        assertNull(set.side)
        assertFalse(set.isWarmup)
        assertEquals(60.0, set.weightLb!!, 0.0)
        assertEquals(8, set.reps)
    }

    @Test fun `the kit's own encoder round-trips a side through a real link`() {
        val repo = ClientRepository(tmp.newFolder())
        val payload = SharePayload(
            ShareClient("a1b2c3d4", "Doug", platform = "android"), null, "2026-09-01", "2026-09-01", 1,
            listOf(ShareDay(0, null, null, null, null, foodTotals = null, food = null, exercises = listOf(ShareExercise("Split Squat", "Dumbbell", listOf(
                ShareSet(60.0, 8, null, null, null, false, ShareSide.LEFT),
                ShareSet(55.0, 8, null, null, null, false, ShareSide.RIGHT),
                ShareSet(45.0, 5, null, null, null, false, null)
            )))))
        )
        ShareLinkImporter.import(ShareLinkCodec.encodeFragment(payload), repo)
        val sets = repo.get("a1b2c3d4")!!.days.single().sets
        assertEquals(listOf(SetSide.LEFT, SetSide.RIGHT, null), sets.map { it.side })
    }

    /* ---------- grouping ---------- */

    private fun set(name: String, equipment: String?, weight: Double, reps: Int, side: SetSide?) =
        ExerciseSet(name, equipment, weight, reps, null, null, null, false, side)

    private fun client(vararg days: TrainingDay) = Client("a", "Doug", "lb", null, 0, null, days.toList())

    private fun day(key: String, vararg sets: ExerciseSet) =
        TrainingDay(key, null, null, null, null, null, null, null, null, null, sets.toList(), emptyList())

    @Test fun `left and right never collapse into one series`() {
        val c = client(day("2026-09-01",
            set("Split Squat", "Dumbbell", 60.0, 8, SetSide.LEFT),
            set("Split Squat", "Dumbbell", 55.0, 8, SetSide.RIGHT),
            set("Split Squat", "Dumbbell", 65.0, 8, SetSide.LEFT)
        ))
        val series = Stats.perLiftE1rm(c)
        assertEquals(setOf("Split Squat|Dumbbell|left", "Split Squat|Dumbbell|right"), series.keys)
        // One point per day per side, the day's best: the left's two sets are one session.
        assertEquals(65.0 * (1 + 8 / 30.0), series.getValue("Split Squat|Dumbbell|left").single().second, 1e-9)
        assertEquals(55.0 * (1 + 8 / 30.0), series.getValue("Split Squat|Dumbbell|right").single().second, 1e-9)
    }

    @Test fun `a two-sided lift is one series and is unchanged`() {
        val c = client(day("2026-09-01",
            set("Bench Press", "Barbell", 185.0, 5, null),
            set("Bench Press", "Barbell", 185.0, 5, null)
        ))
        assertEquals(setOf("Bench Press|Barbell|"), Stats.perLiftE1rm(c).keys)
        val progression = Stats.perLiftProgressions(c).single()
        assertEquals("Bench Press|Barbell", progression.key)
        // One unmarked series, no sides to speak of, so no legend and no figure.
        assertEquals(listOf<SetSide?>(null), progression.series.map { it.side })
        assertFalse(progression.sided)
        assertFalse(progression.hasBothLimbs)
        // One point per day here too: a two-sided lift must not read on a different scale from a
        // per-limb one, and "session" has to mean the same thing on every series.
        assertEquals(1, progression.pointsFor(null).size)
        assertNull(progression.imbalance)
    }

    // Equipment already split a cable pulldown from a machine one; the side joins it for the same
    // reason, and the two rules have to hold at once.
    @Test fun `side and equipment both stay in the identity`() {
        val c = client(day("2026-09-01",
            set("Row", "Barbell", 135.0, 8, null),
            set("Row", "Cable", 100.0, 10, SetSide.LEFT),
            set("Row", "Cable", 95.0, 10, SetSide.RIGHT)
        ))
        assertEquals(
            setOf("Row|Barbell|", "Row|Cable|left", "Row|Cable|right"),
            Stats.perLiftE1rm(c).keys
        )
        assertEquals(listOf("Row|Barbell", "Row|Cable"), Stats.perLiftProgressions(c).map { it.key })
    }

    // Sessions, not sets: a day's best per side is one point, which is the figure the imbalance
    // averages, so the lines and the number under them cannot disagree.
    @Test fun `side sessions take the day's best per side and leave an untrained side null`() {
        val c = client(
            day("2026-09-08",
                set("Split Squat", "Dumbbell", 60.0, 8, SetSide.LEFT),
                set("Split Squat", "Dumbbell", 70.0, 8, SetSide.LEFT)
            ),
            day("2026-09-01",
                set("Split Squat", "Dumbbell", 60.0, 8, SetSide.LEFT),
                set("Split Squat", "Dumbbell", 55.0, 8, SetSide.RIGHT)
            )
        )
        val sessions = Stats.sideSessions(c, "Split Squat", "Dumbbell")
        // Oldest first, whatever order the days were stored in -- the first/last three of the
        // imbalance rule are meaningless otherwise.
        assertEquals(listOf("2026-09-01", "2026-09-08"), sessions.map { it.dayKey })
        assertEquals(60.0 * (1 + 8 / 30.0), sessions[0].leftE1rm!!, 1e-9)
        assertEquals(70.0 * (1 + 8 / 30.0), sessions[1].leftE1rm!!, 1e-9)
        assertNull("a day that trained only the left is not a right-side zero", sessions[1].rightE1rm)
    }

    @Test fun `volume counts both sides, as it always did`() {
        val d = day("2026-09-01",
            set("Split Squat", "Dumbbell", 60.0, 8, SetSide.LEFT),
            set("Split Squat", "Dumbbell", 55.0, 8, SetSide.RIGHT)
        )
        assertEquals(60.0 * 8 + 55.0 * 8, Stats.dayVolume(d), 0.0)
        val week = Stats.weeklyBuckets(client(d), 1, endKey = "2026-09-01").single()
        assertEquals(1, week.sessions)
        assertEquals(2, week.sets)
        assertEquals(920.0, week.volume, 0.0)
    }

    // A lift logged unmarked and then per side keeps all three series: those earlier sets are real,
    // and Coach web's order is left, right, then unmarked.
    @Test fun `sets logged before the toggle went on are still charted`() {
        val c = client(
            day("2026-09-01", set("Split Squat", "Dumbbell", 50.0, 8, null)),
            day("2026-09-08",
                set("Split Squat", "Dumbbell", 60.0, 8, SetSide.LEFT),
                set("Split Squat", "Dumbbell", 55.0, 8, SetSide.RIGHT)
            )
        )
        val progression = Stats.perLiftProgressions(c).single()
        assertEquals(listOf(SetSide.LEFT, SetSide.RIGHT, null), progression.series.map { it.side })
        assertTrue(progression.sided)
        assertTrue(progression.hasBothLimbs)
        assertEquals(1, progression.pointsFor(null).size)
        assertEquals(1, progression.pointsFor(SetSide.LEFT).size)
        assertEquals(1, progression.pointsFor(SetSide.RIGHT).size)
    }

    // Coach web's `if (left && right)`: one limb on its own is a series to chart and nothing to
    // compare it against, so there are no sessions and no figure -- not a "needs 3 a side" prompt
    // for a limb the client never said they were training.
    @Test fun `a lift logged on one limb alone has a series but no gap to report`() {
        val c = client(
            day("2026-09-01", set("Single-Arm Row", "Dumbbell", 60.0, 8, SetSide.LEFT)),
            day("2026-09-08", set("Single-Arm Row", "Dumbbell", 65.0, 8, SetSide.LEFT)),
            day("2026-09-15", set("Single-Arm Row", "Dumbbell", 70.0, 8, SetSide.LEFT))
        )
        val progression = Stats.perLiftProgressions(c).single()
        assertEquals(listOf(SetSide.LEFT), progression.series.map { it.side })
        // A single series, but a sided one, so its line is still named Left rather than left bare.
        assertTrue(progression.sided)
        assertFalse(progression.hasBothLimbs)
        assertTrue(progression.sessions.isEmpty())
        assertNull(progression.imbalance)
    }

    // A one-session side still counts in "1 left, 3 right so far" -- the chart may not draw a line
    // through a single point, but the client did train it, and the count is what says how far off
    // a figure is.
    @Test fun `a side with one session counts toward the session counts`() {
        val c = client(
            day("2026-09-01",
                set("Split Squat", "Dumbbell", 60.0, 8, SetSide.LEFT),
                set("Split Squat", "Dumbbell", 55.0, 8, SetSide.RIGHT)),
            day("2026-09-08", set("Split Squat", "Dumbbell", 65.0, 8, SetSide.LEFT)),
            day("2026-09-15", set("Split Squat", "Dumbbell", 70.0, 8, SetSide.LEFT))
        )
        val progression = Stats.perLiftProgressions(c).single()
        assertEquals(3 to 1, SideBalance.sessionCounts(progression.sessions))
        assertNull(progression.imbalance)
    }

    /* ---------- backup ---------- */

    @Test fun `side survives the model's own JSON, and both is written as nothing at all`() {
        val left = set("Split Squat", "Dumbbell", 60.0, 8, SetSide.LEFT)
        val both = set("Bench Press", "Barbell", 185.0, 5, null)
        assertEquals("left", left.toJson().getString("side"))
        assertFalse("both is absent, never \"both\" and never null", both.toJson().has("side"))
        assertEquals(SetSide.LEFT, ExerciseSet.fromJson(left.toJson()).side)
        assertNull(ExerciseSet.fromJson(both.toJson()).side)
        assertEquals(SetSide.RIGHT, ExerciseSet.fromJson(set("a", null, 1.0, 1, SetSide.RIGHT).toJson()).side)
    }

    @Test fun `a backup round trips both sides and restores them`() {
        val c = client(day("2026-09-01",
            set("Split Squat", "Dumbbell", 60.0, 8, SetSide.LEFT),
            set("Split Squat", "Dumbbell", 55.0, 8, SetSide.RIGHT),
            set("Bench Press", "Barbell", 185.0, 5, null)
        ))
        val file = BackupCodec.export(listOf(c), null, emptyList(), emptyList(), emptyList(), emptyList())
        val restored = BackupCodec.restore(file).clients.single()
        assertEquals(
            listOf(SetSide.LEFT, SetSide.RIGHT, null),
            restored.days.single().sets.map { it.side }
        )
    }

    // BACKUP-FORMAT asks for leniency everywhere: an unrecognised value is both, not a failed
    // import, because the alternative is losing a roster to one string a newer writer invented.
    @Test fun `an unrecognised side in a backup reads as both rather than failing the restore`() {
        val c = client(day("2026-09-01", set("Split Squat", "Dumbbell", 60.0, 8, SetSide.LEFT)))
        val file = JSONObject(BackupCodec.export(listOf(c), null, emptyList(), emptyList(), emptyList(), emptyList()))
        file.getJSONArray("clients").getJSONObject(0)
            .getJSONArray("days").getJSONObject(0)
            .getJSONArray("sets").getJSONObject(0)
            .put("side", "portside")
        val restored = BackupCodec.restore(file.toString()).clients.single()
        assertNull(restored.days.single().sets.single().side)
        assertEquals(60.0, restored.days.single().sets.single().weightLb!!, 0.0)
    }

    @Test fun `a set written before this field restores as both`() {
        val c = client(day("2026-09-01", set("Bench Press", "Barbell", 185.0, 5, null)))
        val file = JSONObject(BackupCodec.export(listOf(c), null, emptyList(), emptyList(), emptyList(), emptyList()))
        val set = file.getJSONArray("clients").getJSONObject(0)
            .getJSONArray("days").getJSONObject(0).getJSONArray("sets").getJSONObject(0)
        assertFalse("a roster with no per-limb sets writes the file it always wrote", set.has("side"))
        assertNull(BackupCodec.restore(file.toString()).clients.single().days.single().sets.single().side)
    }

    @Test fun `the model's wire spellings are the ones the backup format names`() {
        assertEquals("left", SetSide.LEFT.wire)
        assertEquals("right", SetSide.RIGHT.wire)
        assertEquals(SetSide.LEFT, SetSide.fromWire("LEFT"))
        assertEquals(SetSide.RIGHT, SetSide.fromWire(" right "))
        listOf(null, "", "both", "L", "centre").forEach { assertNull(SetSide.fromWire(it)) }
    }
}
