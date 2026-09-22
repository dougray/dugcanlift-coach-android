package com.dugcanlift.coach.data

import com.dugcanlift.kit.CompactEncoding
import com.dugcanlift.kit.PlanDecodeResult
import com.dugcanlift.kit.PlanLinkCodec
import com.dugcanlift.kit.PlanSet
import com.dugcanlift.kit.PlanWorkoutExercise
import com.dugcanlift.kit.ShareSide
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Per-side prescriptions (PLAN-FORMAT "Sides"): Coach's model, its editor rules,
 * its wire encoding and its backup. A port of Coach web's
 * `coach/prescriptions.test.mjs` where the two share a rule.
 *
 * Two fixtures, both written by **Coach web's own encoder** and never to be
 * regenerated from Kotlin: `web-plan-link.txt`, captured before sides existed
 * (the same bytes Coach iOS pins), and `web-plan-per-side.txt`, with `b` and
 * six-field tuples. Every link here is read with the kit's `PlanLinkCodec` --
 * the decoder LIFT Android ships -- rather than with a second copy of the rules.
 */
class PerSidePrescriptionsTest {

    private fun fixture(name: String) =
        javaClass.classLoader!!.getResource("fixtures/$name")!!.readText().trim()

    private fun decodeLink(link: String, lifter: String) =
        (PlanLinkCodec.decode(link.substringAfter('#'), lifter) as PlanDecodeResult.Success).payload

    /** The raw JSON a link carries, for comparing what was written rather than what was read. */
    private fun rawJson(link: String): JSONObject {
        val fragment = link.substringAfter('#')
        check(fragment.startsWith("1z"))
        return JSONObject(String(CompactEncoding.inflateRaw(CompactEncoding.base64UrlDecode(fragment.substring(2))), Charsets.UTF_8))
    }

    /**
     * JSON as plain values, numbers as doubles, for comparing two documents by
     * what they say. (Key order is not compared: the JVM's org.json does not keep
     * it, though Android's does and `TrainPlanEncoder` inserts `n q b s c`.)
     */
    private fun plain(v: Any?): Any? = when (v) {
        is JSONObject -> v.keys().asSequence().associateWith { plain(v.get(it)) }
        is JSONArray -> (0 until v.length()).map { plain(v.get(it)) }
        is Number -> v.toDouble()
        JSONObject.NULL -> null
        else -> v
    }

    private fun JSONObject.keyNames() = keys().asSequence().toSet()

    /** A decoded plan exercise as Coach stores it: kilograms, and the side as Coach spells it. */
    private fun fromPlan(e: PlanWorkoutExercise) = RoutineExercise(
        name = e.name, equipment = e.equipment, note = e.note.ifBlank { null }, eachSide = e.eachSide,
        sets = e.sets.map { s ->
            PrescribedSet(
                targetWeightKg = s.weightLb?.let { it / LB_PER_KG }, targetReps = s.reps, targetRpe = s.rpe,
                targetDurationSec = s.durationSec, targetDistanceMeters = s.distanceMeters,
                side = SetSide.fromShare(s.side)
            )
        }
    )

    /** Coach's encoding read back by LIFT's decoder, as a `u` link so nothing but the JSON is on trial. */
    private fun throughLift(routine: Routine): List<PlanWorkoutExercise> {
        val json = JSONObject().put("v", 1).put("t", "plan").put("l", "c1").put("n", "Doug")
            .put("w", JSONArray().put(TrainPlanEncoder.workoutWire(routine)))
        val link = "1u" + Base64.getUrlEncoder().withoutPadding().encodeToString(json.toString().toByteArray())
        return decodeLink(link, "c1").workouts.single().exercises
    }

    private fun kg(lb: Double) = lb / LB_PER_KG
    private fun set(lb: Double?, reps: Int?, side: SetSide? = null) =
        PrescribedSet(targetWeightKg = lb?.let(::kg), targetReps = reps, side = side)

    /* ---------- a plan written today is unchanged ---------- */

    @Test fun `the pre-sides web fixture re-encodes to the same JSON, with no b and no sixth position`() {
        val link = fixture("web-plan-link.txt")
        val raw = rawJson(link)
        val payload = decodeLink(link, raw.getString("l"))
        val again = payload.workouts.map { w -> TrainPlanEncoder.workoutWire(Routine(name = w.name, exercises = w.exercises.map(::fromPlan))) }
        assertEquals("same JSON as Coach web wrote", plain(raw.getJSONArray("w")), plain(JSONArray(again)))
        assertFalse(raw.toString().contains("\"b\""))
        payload.workouts.flatMap { it.exercises }.forEach { e ->
            assertFalse(e.eachSide)
            e.sets.forEach { assertNull(it.side) }
        }
    }

    @Test fun `a set with no side writes the five-field tuple it always did`() {
        assertEquals("[225,5]", TrainPlanEncoder.setTuple(set(225.0, 5)).toString())
        assertEquals("[null,5]", TrainPlanEncoder.setTuple(set(null, 5)).toString())
        assertEquals("[null,null,null,600,1600]", TrainPlanEncoder.setTuple(
            PrescribedSet(targetDurationSec = 600, targetDistanceMeters = 1600.0)).toString())
        assertEquals("[]", TrainPlanEncoder.setTuple(PrescribedSet()).toString())
        // A kilogram value from pounds goes back to the pounds it was, not 30.000000000000004.
        assertEquals("[30,8]", TrainPlanEncoder.setTuple(set(30.0, 8)).toString())
    }

    /* ---------- the wire ---------- */

    @Test fun `a named side always writes a sixth position, and only trailing nulls are trimmed`() {
        assertEquals("[30,8,null,null,null,2]", TrainPlanEncoder.setTuple(set(30.0, 8, SetSide.LEFT)).toString())
        assertEquals("[30,8,null,null,null,4]", TrainPlanEncoder.setTuple(set(30.0, 8, SetSide.RIGHT)).toString())
        assertEquals("[null,null,null,600,1600,2]", TrainPlanEncoder.setTuple(
            PrescribedSet(targetDurationSec = 600, targetDistanceMeters = 1600.0, side = SetSide.LEFT)).toString())
        assertEquals("[null,5,null,null,null,4]", TrainPlanEncoder.setTuple(set(null, 5, SetSide.RIGHT)).toString())
    }

    @Test fun `each side is b 1, omitted when not, never 0`() {
        val ex = RoutineExercise(name = "Split Squat", equipment = "Dumbbell", sets = listOf(set(40.0, 8)))
        assertFalse(TrainPlanEncoder.exerciseWire(ex).has("b"))
        assertEquals(1, TrainPlanEncoder.exerciseWire(ex.copy(eachSide = true)).getInt("b"))
        assertEquals(plain(JSONObject("""{"n":"Split Squat","q":"Dumbbell","b":1,"s":[[40,8]]}""")),
            plain(TrainPlanEncoder.exerciseWire(ex.copy(eachSide = true))))
        // No equipment, no note: no q, no c.
        assertEquals(setOf("n", "s"), TrainPlanEncoder.exerciseWire(RoutineExercise(name = "Plank", note = " ")).keyNames())
    }

    @Test fun `flags are masked, never compared - 2, 4, 3, 5, 6`() {
        val tuples = listOf(2, 4, 3, 5, 6, 0, 1).joinToString(",") { "[30,8,null,null,null,$it]" }
        val json = """{"v":1,"t":"plan","l":"c1","w":[{"n":"x","e":[{"n":"Row","s":[$tuples,[30,8]]}]}]}"""
        val link = "1u" + Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())
        val sets = decodeLink(link, "c1").workouts.single().exercises.single().sets
        assertEquals(listOf(ShareSide.LEFT, ShareSide.RIGHT, ShareSide.LEFT, ShareSide.RIGHT, null, null, null, null),
            sets.map { it.side })
        sets.forEach { assertEquals(30.0, it.weightLb); assertEquals(8, it.reps) }
    }

    /* ---------- round trips ---------- */

    private val perSide = Routine(id = "r", name = "Per side", exercises = listOf(
        RoutineExercise(name = "Bench Press", equipment = "Barbell", sets = listOf(set(185.0, 5))),
        RoutineExercise(name = "Row", equipment = "Dumbbell", sets = listOf(set(30.0, 8, SetSide.LEFT), set(35.0, 8, SetSide.RIGHT))),
        RoutineExercise(name = "Split Squat", equipment = "Dumbbell", eachSide = true, sets = listOf(set(40.0, 8), set(40.0, 8))),
        RoutineExercise(name = "Lunge", equipment = "Dumbbell", note = "Extra on the left", eachSide = true,
            sets = listOf(set(40.0, 8), set(40.0, 8), set(40.0, 8), set(40.0, 8, SetSide.LEFT)))
    ))

    @Test fun `round trip through LIFT's decoder - both, left, right, each side and the combination`() {
        val back = throughLift(perSide)
        assertEquals(listOf(PlanSet(185.0, 5)), back[0].sets)
        assertFalse(back[0].eachSide)
        assertEquals(listOf(PlanSet(30.0, 8, side = ShareSide.LEFT), PlanSet(35.0, 8, side = ShareSide.RIGHT)), back[1].sets)
        assertTrue(back[2].eachSide)
        assertEquals(List(2) { PlanSet(40.0, 8) }, back[2].sets)
        assertTrue(back[3].eachSide)
        assertEquals(listOf(null, null, null, ShareSide.LEFT), back[3].sets.map { it.side })
        assertEquals("Extra on the left", back[3].note)
        // And back into Coach's model, equal to what went out.
        back.zip(perSide.exercises).forEach { (wire, mine) ->
            val again = fromPlan(wire)
            assertEquals(mine.eachSide, again.eachSide)
            assertEquals(mine.sets.map { it.side }, again.sets.map { it.side })
            assertEquals(mine.sets.map { it.targetReps }, again.sets.map { it.targetReps })
        }
    }

    @Test fun `round trip through the backup - eachSide and side as Coach web and iOS spell them`() {
        val out = JSONObject(BackupCodec.export(emptyList(), null, emptyList(), emptyList(), listOf(perSide), emptyList()))
        val exercises = out.getJSONArray("routines").getJSONObject(0).getJSONArray("exercises")
        assertFalse("false is never written", exercises.getJSONObject(0).has("eachSide"))
        assertFalse("both is never written", exercises.getJSONObject(0).getJSONArray("sets").getJSONObject(0).has("side"))
        assertEquals("left", exercises.getJSONObject(1).getJSONArray("sets").getJSONObject(0).getString("side"))
        assertEquals("right", exercises.getJSONObject(1).getJSONArray("sets").getJSONObject(1).getString("side"))
        assertEquals(true, exercises.getJSONObject(2).get("eachSide"))
        assertEquals("left", exercises.getJSONObject(3).getJSONArray("sets").getJSONObject(3).getString("side"))

        val restored = BackupCodec.restore(out.toString()).routines.single()
        assertEquals(perSide.exercises.map { it.eachSide }, restored.exercises.map { it.eachSide })
        assertEquals(perSide.exercises.map { e -> e.sets.map { it.side } }, restored.exercises.map { e -> e.sets.map { it.side } })
    }

    @Test fun `a routine without sides writes the backup object it always did`() {
        val squat = RoutineExercise(name = "Squat", equipment = "Barbell", sets = listOf(PrescribedSet(100.0, 5)))
        assertEquals(setOf("name", "equipment", "sets"), squat.toJson().keyNames())
        assertEquals(setOf("targetWeightKg", "targetReps"), squat.sets[0].toJson().keyNames())
    }

    @Test fun `a file written before sides restores unchanged, and junk reads as today's meaning`() {
        val old = routineExerciseFromJson(JSONObject("""{"name":"Row","equipment":"","sets":[{"targetWeightKg":20,"targetReps":8}]}"""))
        assertFalse(old.eachSide)
        assertNull(old.sets.single().side)
        val junk = routineExerciseFromJson(JSONObject(
            """{"name":"Row","eachSide":"yes","sets":[{"targetReps":8,"side":"sideways"},{"targetReps":8,"side":"Left"},{"targetReps":8,"side":"both"}]}"""))
        assertFalse("only true is each side", junk.eachSide)
        assertEquals(listOf(null, SetSide.LEFT, null), junk.sets.map { it.side })
        assertFalse("an unknown side is not kept to be written back", junk.sets[0].toJson().has("side"))
        // Coach web's spelling of a set reads too, pounds and all.
        val web = prescribedSetFromJson(JSONObject("""{"weightLb":30,"reps":8,"side":"right"}"""))
        assertEquals(SetSide.RIGHT, web.side)
        assertEquals(30.0, web.targetWeightLb!!, 1e-9)
    }

    /* ---------- what it asks for ---------- */

    @Test fun `the seven-set case - each side plus one left is three right and four left`() {
        val ex = perSide.exercises[3]
        assertEquals(PrescriptionSides.Targets(left = 4, right = 3, both = 0), PrescriptionSides.targets(ex))
        assertEquals(PrescriptionSides.Targets(left = 4, right = 3, both = 0),
            PrescriptionSides.targets(fromPlan(throughLift(perSide)[3])))
    }

    @Test fun `a named side on a two-sided lift is once on that side`() {
        val ex = RoutineExercise(name = "Bench", sets = listOf(set(185.0, 5), set(185.0, 5), set(95.0, 10, SetSide.RIGHT)))
        assertEquals(PrescriptionSides.Targets(left = 0, right = 1, both = 2), PrescriptionSides.targets(ex))
    }

    /* ---------- how it reads ---------- */

    private fun kgSet(kg: Double?, reps: Int?, side: SetSide? = null) = PrescribedSet(targetWeightKg = kg, targetReps = reps, side = side)

    @Test fun `an exercise with no side reads as a plain list`() {
        fun summary(vararg s: PrescribedSet) = PrescriptionSides.summary(RoutineExercise(name = "x", sets = s.toList()))
        assertEquals("3 × 100 × 5", summary(kgSet(100.0, 5), kgSet(100.0, 5), kgSet(100.0, 5)))
        assertEquals("100 × 5, 110 × 3", summary(kgSet(100.0, 5), kgSet(110.0, 3)))
        assertEquals("no sets yet", summary())
        assertEquals("22.5 × 10", summary(kgSet(22.5, 10)))
    }

    @Test fun `each side reads 3 x 30 x 8 each side, and an extra set + 1 L`() {
        val each = List(3) { kgSet(30.0, 8) }
        fun summary(sets: List<PrescribedSet>) = PrescriptionSides.summary(RoutineExercise(name = "x", eachSide = true, sets = sets))
        assertEquals("3 × 30 × 8 each side", summary(each))
        assertEquals("3 × 30 × 8 each side + 1 L", summary(each + kgSet(30.0, 8, SetSide.LEFT)))
        assertEquals("3 × 30 × 8 each side + 20 × 12 L", summary(each + kgSet(20.0, 12, SetSide.LEFT)))
        assertEquals("30 × 8 each side", summary(listOf(kgSet(30.0, 8))))
    }

    @Test fun `a named set reads with its side - 30 x 8 L`() {
        assertEquals("30 × 8 L", PrescriptionSides.setText(kgSet(30.0, 8, SetSide.LEFT)))
        assertEquals("30 × 8", PrescriptionSides.setText(kgSet(30.0, 8)))
        assertEquals("80 × 5, 40 × 10 R", PrescriptionSides.summary(RoutineExercise(name = "x",
            sets = listOf(kgSet(80.0, 5), kgSet(40.0, 10, SetSide.RIGHT)))))
    }

    /* ---------- the interop fixture ---------- */

    @Test fun `fixture - the raw wire carries b and six-field tuples as specified`() {
        val raw = rawJson(fixture("web-plan-per-side.txt"))
        val e = raw.getJSONArray("w").getJSONObject(0).getJSONArray("e")
        assertEquals(listOf(null, 1, 1, null, null), (0 until e.length()).map { e.getJSONObject(it).opt("b") })
        assertEquals("[40,8,null,null,null,2]", e.getJSONObject(2).getJSONArray("s").getJSONArray(3).toString())
        assertEquals("[40,10,null,null,null,4]", e.getJSONObject(3).getJSONArray("s").getJSONArray(2).toString())
        assertEquals("[[null,null,null,600,1600,2]]", e.getJSONObject(4).getJSONArray("s").toString())
    }

    @Test fun `fixture - sets per side, exercise by exercise`() {
        val exercises = decodeLink(fixture("web-plan-per-side.txt"), "a1b2c3d4").workouts.single().exercises.map(::fromPlan)
        val t = exercises.associate { it.name to PrescriptionSides.targets(it) }
        assertEquals(PrescriptionSides.Targets(0, 0, 3), t["Back Squat"])
        assertEquals(PrescriptionSides.Targets(3, 3, 0), t["Single-Arm Dumbbell Row"])
        assertEquals("the seven-set case", PrescriptionSides.Targets(4, 3, 0), t["Bulgarian Split Squat"])
        assertEquals(PrescriptionSides.Targets(0, 1, 2), t["Dumbbell Bench Press"])
        assertEquals(PrescriptionSides.Targets(1, 0, 0), t["Suitcase Carry"])
    }

    @Test fun `fixture - it reads the way the editor shows it, in kilograms`() {
        val exercises = decodeLink(fixture("web-plan-per-side.txt"), "a1b2c3d4").workouts.single().exercises.map(::fromPlan)
        assertEquals(listOf(
            "3 × 102.1 × 5 · @8",
            "3 × 13.6 × 8 each side",
            "3 × 18.1 × 8 each side + 1 L",
            "27.2 × 8, 27.2 × 8, 18.1 × 10 R",
            "1,600 m · 10:00 L"
        ), exercises.map(PrescriptionSides::summary))
    }

    @Test fun `fixture - decoded and encoded again, it is the same JSON`() {
        val link = fixture("web-plan-per-side.txt")
        val raw = rawJson(link).getJSONArray("w")
        val payload = decodeLink(link, "a1b2c3d4")
        val again = JSONArray(payload.workouts.map { w -> TrainPlanEncoder.workoutWire(Routine(name = w.name, exercises = w.exercises.map(::fromPlan))) })
        assertEquals(plain(raw), plain(again))
    }

    /* ---------- the editor ---------- */

    @Test fun `each side starts ticked by LIFT's own unilateral guess`() {
        listOf("Bulgarian Split Squat", "Single-Arm Dumbbell Row", "One Arm Overhead Press", "Walking Lunge",
            "Pistol Squat", "Step-Up", "Kettlebell One-Legged Deadlift").forEach {
            assertTrue(it, PrescriptionSides.looksUnilateral(it))
        }
        listOf("Barbell Bench Press", "Back Squat", "Cold Plunge", "Stepmill", "Arm Curl").forEach {
            assertFalse(it, PrescriptionSides.looksUnilateral(it))
        }
    }

    @Test fun `a new exercise takes the guess, a remembered choice beats it, and a saved one keeps what it stored`() {
        val routine = Routine(name = "x", exercises = listOf(
            RoutineExercise(name = "Walking Lunge", equipment = "Dumbbell", sets = listOf(kgSet(20.0, 10)))))
        val state = RoutineEditing.initial(routine)
        val parsed = com.dugcanlift.coach.ui.parseExercises(
            "Walking Lunge | Dumbbell | 1 x 10 @ 20\nBulgarian Split Squat | Dumbbell | 3 x 8 @ 18\nStep-Up | Dumbbell | 3 x 8 @ 18")
        val noMemory = RoutineEditing.apply(parsed, routine, state) { null }
        assertEquals("saved unticked stays unticked", false, noMemory[0].eachSide)
        assertEquals("new and unilateral-looking starts ticked", true, noMemory[1].eachSide)
        val remembered = RoutineEditing.apply(parsed, routine, state) { lift -> if (lift == "step-up|dumbbell") false else null }
        assertEquals("the coach's own answer wins", false, remembered[2].eachSide)
    }

    @Test fun `a set's side lands on the saved set, padded with both`() {
        val parsed = com.dugcanlift.coach.ui.parseExercises("Bench Press | Barbell | 3 x 5 @ 80")
        val key = RoutineEditing.keys(parsed).single()
        val sides = RoutineEditing.withSide(RoutineEditing.ExerciseSides(eachSide = false), 2, SetSide.RIGHT)
        val saved = RoutineEditing.apply(parsed, Routine(name = "x"), mapOf(key to sides)) { null }.single()
        assertEquals(listOf(null, null, SetSide.RIGHT), saved.sets.map { it.side })
        assertEquals("80 × 5, 80 × 5, 80 × 5 R", PrescriptionSides.summary(saved))
        assertTrue(PrescriptionSides.showsSides(saved, asked = false))
        assertFalse(PrescriptionSides.showsSides(saved.copy(sets = saved.sets.map { it.copy(side = null) }), asked = false))
    }

    @Test fun `the editor's sides survive a recreation`() {
        val state = mapOf("row|dumbbell#0" to RoutineEditing.ExerciseSides(true, listOf(null, SetSide.LEFT), asked = true))
        assertEquals(state, RoutineEditing.decode(RoutineEditing.encode(state)))
        assertEquals(emptyMap<String, RoutineEditing.ExerciseSides>(), RoutineEditing.decode("not json"))
    }
}
