package com.dugcanlift.coach.data

import com.dugcanlift.kit.CompactEncoding
import com.dugcanlift.kit.PlanDecodeResult
import com.dugcanlift.kit.PlanLinkCodec
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
import kotlin.math.round

/**
 * Set types (PLAN-FORMAT "Set types"): supersets and circuits (`g`), drop sets and rest-pause
 * (flags bits 3-4). Coach's model, its editor rules, its wire encoding and its backup. A port of
 * Coach web's `coach/set-types.test.mjs` where the two share a rule; [PerSidePrescriptionsTest] is
 * the precedent.
 *
 * `fixtures/web-plan-set-types.txt` was written by **Coach web's own encoder** and is copied here
 * unchanged. Never regenerate it from Kotlin: it is the one thing that proves interoperability
 * rather than agreement with itself. The kit's `PlanLinkCodec` -- the decoder LIFT Android ships
 * today -- knows neither `g` nor bits 3-4, so it is also the "old decoder" these tests check.
 */
class SetTypesTest {

    private fun fixture(name: String) =
        javaClass.classLoader!!.getResource("fixtures/$name")!!.readText().trim()

    private fun decodeLink(link: String, lifter: String) =
        (PlanLinkCodec.decode(link.substringAfter('#'), lifter) as PlanDecodeResult.Success).payload

    private fun rawJson(link: String): JSONObject {
        val fragment = link.substringAfter('#')
        check(fragment.startsWith("1z"))
        return JSONObject(String(CompactEncoding.inflateRaw(CompactEncoding.base64UrlDecode(fragment.substring(2))), Charsets.UTF_8))
    }

    /** JSON as plain values, numbers as doubles; key order is not compared (the JVM's org.json does not keep it). */
    private fun plain(v: Any?): Any? = when (v) {
        is JSONObject -> v.keys().asSequence().associateWith { plain(v.get(it)) }
        is JSONArray -> (0 until v.length()).map { plain(v.get(it)) }
        is Number -> v.toDouble()
        JSONObject.NULL -> null
        else -> v
    }

    private fun kg(lb: Double) = lb / LB_PER_KG
    private fun set(lb: Double?, reps: Int?, side: SetSide? = null, kind: SetKind? = null, sec: Int? = null, rpe: Double? = null) =
        PrescribedSet(targetWeightKg = lb?.let(::kg), targetReps = reps, targetRpe = rpe, targetDurationSec = sec, side = side, kind = kind)
    private fun ex(name: String, group: Int? = null, sets: List<PrescribedSet> = listOf(set(100.0, 5), set(100.0, 5)),
                   equipment: String = "", eachSide: Boolean = false, note: String? = null) =
        RoutineExercise(name = name, equipment = equipment, note = note, sets = sets, eachSide = eachSide, group = group)

    /**
     * A plan workout back into Coach's model, the way Coach web's `decodeWorkout` reads one: the
     * kit for everything an old decoder knows, then `g` by adjacency and the kind bits masked out
     * of the raw tuples, never on a first set.
     */
    private fun typed(raw: JSONObject, decoded: List<PlanWorkoutExercise>): List<RoutineExercise> {
        val e = raw.getJSONArray("e")
        val groups = PrescriptionTypes.runs((0 until e.length()).map { PrescriptionTypes.groupOf(e.getJSONObject(it).opt("g")) })
        return decoded.mapIndexed { i, x ->
            val tuples = e.getJSONObject(i).getJSONArray("s")
            RoutineExercise(
                name = x.name, equipment = x.equipment, note = x.note.ifBlank { null }, eachSide = x.eachSide,
                group = groups[i],
                sets = x.sets.mapIndexed { j, s ->
                    PrescribedSet(
                        targetWeightKg = s.weightLb?.let(::kg), targetReps = s.reps, targetRpe = s.rpe,
                        targetDurationSec = s.durationSec, targetDistanceMeters = s.distanceMeters,
                        side = SetSide.fromShare(s.side),
                        kind = if (j == 0) null else PrescriptionTypes.kindFromFlags(tuples.getJSONArray(j).opt(5))
                    )
                }
            )
        }
    }

    /** The kind a tuple Coach wrote says. */
    private fun kindOf(tuple: JSONArray) = PrescriptionTypes.kindFromFlags(tuple.opt(5))

    /* ---------------- a plan written today is unchanged ---------------- */

    /**
     * `setTuple`, `exerciseWire` and `workoutWire` as they stood on main before set types
     * (coach-android 6945c27, TrainPlanEncoder.kt), verbatim. Frozen on purpose: the promise is
     * that a plan with no groups and no kinds is the same bytes it always was, sides included, and
     * the only honest check is against the code that wrote those bytes.
     */
    private object Main {
        fun kgToLb(kg: Double): Double = round(kg * LB_PER_KG * 1_000_000.0) / 1_000_000.0
        fun sideBits(side: SetSide): Int = when (side) {
            SetSide.LEFT -> 1
            SetSide.RIGHT -> 2
        } shl 1
        fun setTuple(set: PrescribedSet): JSONArray {
            val values = mutableListOf<Any?>(
                set.targetWeightKg?.let(::kgToLb), set.targetReps, set.targetRpe, set.targetDurationSec, set.targetDistanceMeters
            )
            val side = set.side
            if (side != null) values += sideBits(side)
            else while (values.isNotEmpty() && values.last() == null) values.removeAt(values.lastIndex)
            return JSONArray().also { array -> values.forEach { array.put(it ?: JSONObject.NULL) } }
        }
        fun exerciseWire(exercise: RoutineExercise): JSONObject {
            val o = JSONObject().put("n", exercise.name)
            if (exercise.equipment.isNotBlank()) o.put("q", exercise.equipment)
            if (exercise.eachSide) o.put("b", 1)
            o.put("s", JSONArray().also { array -> exercise.sets.forEach { array.put(setTuple(it)) } })
            exercise.note?.takeIf { it.isNotBlank() }?.let { o.put("c", it) }
            return o
        }
        fun workoutWire(routine: Routine): JSONObject = JSONObject()
            .put("n", routine.name)
            .put("e", JSONArray().also { array -> routine.exercises.forEach { array.put(exerciseWire(it)) } })
    }

    @Test fun `a plan with no groups or kinds encodes byte for byte as main did`() {
        val routine = Routine(name = "Lower A", exercises = listOf(
            ex("Back Squat", equipment = "Barbell", note = "Belt on the last set.",
                sets = listOf(set(225.0, 5, rpe = 8.0), set(225.0, 5, rpe = 8.0), set(245.0, 3, rpe = 9.0))),
            ex("Walking Lunge", eachSide = true, sets = listOf(set(null, 12), set(0.0, 12))),
            ex("Row", equipment = "Machine", sets = listOf(
                PrescribedSet(targetDurationSec = 600, targetDistanceMeters = 1600.0, side = SetSide.LEFT), PrescribedSet())),
            ex("Split Squat", eachSide = true, sets = listOf(set(40.0, 8), set(40.0, 8, SetSide.RIGHT))),
            ex("Empty", equipment = "Cable", sets = emptyList())
        ))
        assertEquals(Main.workoutWire(routine).toString(), TrainPlanEncoder.workoutWire(routine).toString())
    }

    @Test fun `a lone or junk group and a first-set kind change no byte`() {
        val routine = Routine(name = "Junk", exercises = listOf(
            ex("A", group = 1),
            ex("B", group = 0),
            ex("C", group = 1, sets = listOf(set(100.0, 5, kind = SetKind.DROP), set(90.0, 5))),
            ex("D", group = -3),
            ex("E", group = 2)
        ))
        val stripped = routine.copy(exercises = routine.exercises.map { e -> e.copy(sets = e.sets.map { it.copy(kind = null) }) })
        assertEquals(Main.workoutWire(stripped).toString(), TrainPlanEncoder.workoutWire(routine).toString())
    }

    @Test fun `the sibling fixtures re-encode with no g and no kind bits`() {
        listOf("web-plan-link.txt", "web-plan-per-side.txt", "web-plan-road-picks.txt").forEach { file ->
            val link = fixture(file)
            val raw = rawJson(link)
            val rawW = raw.getJSONArray("w")
            val payload = decodeLink(link, raw.getString("l"))
            val again = JSONArray(payload.workouts.mapIndexed { i, w ->
                TrainPlanEncoder.workoutWire(Routine(name = w.name, exercises = typed(rawW.getJSONObject(i), w.exercises)))
            })
            assertEquals(file, plain(rawW), plain(again))
            assertFalse(file, again.toString().contains("\"g\""))
            for (w in 0 until again.length()) {
                val e = again.getJSONObject(w).getJSONArray("e")
                for (x in 0 until e.length()) {
                    val s = e.getJSONObject(x).getJSONArray("s")
                    for (t in 0 until s.length()) assertNull(file, kindOf(s.getJSONArray(t)))
                }
            }
        }
    }

    /* ---------------- kinds on the wire ---------------- */

    @Test fun `a kind is flags bits 3-4 - drop 8, rest-pause 16, OR'd with the side`() {
        assertEquals("[50,10,null,null,null,8]", TrainPlanEncoder.setTuple(set(50.0, 10, kind = SetKind.DROP)).toString())
        assertEquals("[65,4,null,null,null,16]", TrainPlanEncoder.setTuple(set(65.0, 4, kind = SetKind.REST_PAUSE)).toString())
        assertEquals("[30,12,null,null,null,10]", TrainPlanEncoder.setTuple(set(30.0, 12, SetSide.LEFT, SetKind.DROP)).toString())
        assertEquals("[30,12,null,null,null,12]", TrainPlanEncoder.setTuple(set(30.0, 12, SetSide.RIGHT, SetKind.DROP)).toString())
        assertEquals("[30,12,null,null,null,18]", TrainPlanEncoder.setTuple(set(30.0, 12, SetSide.LEFT, SetKind.REST_PAUSE)).toString())
        assertEquals("[30,12,null,null,null,20]", TrainPlanEncoder.setTuple(set(30.0, 12, SetSide.RIGHT, SetKind.REST_PAUSE)).toString())
    }

    @Test fun `a straight both-sides set still writes no sixth position`() {
        assertEquals("[225,5]", TrainPlanEncoder.setTuple(set(225.0, 5)).toString())
        assertEquals("[null,5]", TrainPlanEncoder.setTuple(set(null, 5)).toString())
    }

    @Test fun `only trailing nulls are trimmed - a rest-pause hold keeps its slots`() {
        assertEquals("[null,null,null,20,null,16]",
            TrainPlanEncoder.setTuple(set(null, null, kind = SetKind.REST_PAUSE, sec = 20)).toString())
        assertEquals("[null,6,null,null,null,8]", TrainPlanEncoder.setTuple(set(null, 6, kind = SetKind.DROP)).toString())
    }

    @Test fun `an exercise's first set is never written as a drop or rest-pause`() {
        val wire = TrainPlanEncoder.exerciseWire(ex("Pushdown", sets = listOf(set(70.0, 12, kind = SetKind.DROP), set(50.0, 10, kind = SetKind.DROP))))
        assertEquals("[[70,12],[50,10,null,null,null,8]]", wire.getJSONArray("s").toString())
        // The side on a first set survives; only the kind goes.
        val sided = TrainPlanEncoder.exerciseWire(ex("Row", sets = listOf(set(30.0, 8, SetSide.LEFT, SetKind.REST_PAUSE))))
        assertEquals("[[30,8,null,null,null,2]]", sided.getJSONArray("s").toString())
    }

    /* ---------------- kinds read back ---------------- */

    @Test fun `kinds are masked, never compared - left and drop is 10`() {
        val cases = mapOf(
            8 to SetKind.DROP, 16 to SetKind.REST_PAUSE, 10 to SetKind.DROP, 12 to SetKind.DROP,
            18 to SetKind.REST_PAUSE, 20 to SetKind.REST_PAUSE,
            9 to SetKind.DROP, 11 to SetKind.DROP, // bit 0 is ignored
            24 to null, 26 to null, // 3 in bits 3-4 is never written and reads straight
            2 to null, 0 to null,
            40 to SetKind.DROP // bits above 4 are not ours
        )
        cases.forEach { (flags, kind) -> assertEquals("flags $flags", kind, PrescriptionTypes.kindFromFlags(flags)) }
    }

    @Test fun `the side reads through the kind bits, by the decoder LIFT Android ships`() {
        val tuples = listOf(8, 16, 10, 12, 18, 20, 26).joinToString(",") { "[30,8,null,null,null,$it]" }
        val json = """{"v":1,"t":"plan","l":"c1","w":[{"n":"x","e":[{"n":"Row","s":[[30,8],$tuples]}]}]}"""
        val link = "1u" + Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())
        val sets = decodeLink(link, "c1").workouts.single().exercises.single().sets
        assertEquals(listOf(null, null, null, ShareSide.LEFT, ShareSide.RIGHT, ShareSide.LEFT, ShareSide.RIGHT, ShareSide.LEFT),
            sets.map { it.side })
        sets.forEach { assertEquals(30.0, it.weightLb); assertEquals(8, it.reps) }
    }

    @Test fun `a junk sixth position is a straight set`() {
        listOf(null, "x", "8", JSONObject(), JSONArray(), Double.NaN, -1, 2.5).forEach { flags ->
            assertNull("$flags", PrescriptionTypes.kindFromFlags(flags))
        }
    }

    /* ---------------- groups on the wire ---------------- */

    @Test fun `g is written on a run of two or more adjacent exercises sharing a group`() {
        val wire = TrainPlanEncoder.workoutWire(Routine(name = "Upper B", exercises = listOf(ex("Bench", 1), ex("Row", 1), ex("Curl"))))
        val e = wire.getJSONArray("e")
        assertEquals(listOf(1, 1, null), (0 until e.length()).map { e.getJSONObject(it).opt("g") })
    }

    @Test fun `keys are n q b g s c, inserted in that order`() {
        // Android's org.json keeps insertion order; the JVM's does not, so the order is checked on
        // the insertion itself, through a JSONObject that records it.
        val wire = TrainPlanEncoder.workoutWire(Routine(name = "x", exercises = listOf(
            ex("Split Squat", 3, listOf(set(40.0, 8)), equipment = "Dumbbell", eachSide = true, note = "Slow."),
            ex("Row", 3, listOf(set(100.0, 10)), equipment = "Cable")
        )))
        val e = wire.getJSONArray("e")
        assertEquals(plain(JSONObject("""{"n":"Split Squat","q":"Dumbbell","b":1,"g":1,"s":[[40,8]],"c":"Slow."}""")), plain(e.getJSONObject(0)))
        assertEquals(plain(JSONObject("""{"n":"Row","q":"Cable","g":1,"s":[[100,10]]}""")), plain(e.getJSONObject(1)))
        val source = java.io.File("src/main/java/com/dugcanlift/coach/data/TrainPlanEncoder.kt").readText()
        val body = source.substringAfter("fun exerciseWire(").substringBefore("fun workoutWire(")
        val order = Regex("""put\("([a-z])"""").findAll(body).map { it.groupValues[1] }.toList()
        assertEquals(listOf("n", "q", "b", "g", "s", "c"), order)
    }

    @Test fun `groups are renumbered 1, 2, 3 in order of appearance`() {
        val wire = TrainPlanEncoder.workoutWire(Routine(name = "x", exercises = listOf(
            ex("A", 7), ex("B", 7), ex("C"), ex("D", 3), ex("E", 3), ex("F", 3), ex("G", 7), ex("H", 7))))
        val e = wire.getJSONArray("e")
        assertEquals(listOf(1, 1, null, 2, 2, 2, 3, 3), (0 until e.length()).map { e.getJSONObject(it).opt("g") })
    }

    @Test fun `adjacency makes the group, not the number`() {
        fun g(vararg n: Int?) = PrescriptionTypes.runs(n.toList())
        assertEquals("two groups of one are no group", listOf(null, null, null), g(1, null, 1))
        assertEquals("a group of one is an ordinary exercise", listOf<Int?>(null), g(1))
        assertEquals(listOf(1, 1, 2, 2), g(1, 1, 2, 2))
        assertEquals(listOf(null, null, null), g(1, 2, 1))
        assertEquals(listOf(1, 1, 1, 1), g(5, 5, 5, 5))
        assertEquals("never 0", listOf(null, null), g(0, 0))
        assertEquals(listOf(null, null), g(-1, -1))
        assertEquals(listOf(1, 1, null, 2, 2), g(1, 1, 0, 1, 1))
    }

    @Test fun `a g or group that is not a JSON positive integer is not grouped`() {
        listOf(0, -2, 1.5, "1", "3", true, null, JSONObject.NULL, JSONArray(), Double.NaN).forEach {
            assertNull("$it", PrescriptionTypes.groupOf(it as Any?))
        }
        assertEquals(3, PrescriptionTypes.groupOf(3 as Any))
        assertEquals(3, PrescriptionTypes.groupOf(3L as Any))
        assertEquals("2.0 is an integer in JSON, as in Coach web", 2, PrescriptionTypes.groupOf(2.0 as Any))
    }

    @Test fun `an exercise encoded on its own never carries g`() {
        assertFalse(TrainPlanEncoder.exerciseWire(ex("Bench", 1)).has("g"))
    }

    /* ---------------- round trips ---------------- */

    private val typedRoutine = Routine(id = "r", name = "Set types", exercises = listOf(
        ex("Back Squat", sets = listOf(set(225.0, 5), set(225.0, 5))),
        ex("Bench", 1, listOf(set(60.0, 10), set(60.0, 10))),
        ex("Row", 1, listOf(set(120.0, 12), set(120.0, 12))),
        ex("Swing", 2, listOf(set(53.0, 15))),
        ex("Push-Up", 2, listOf(set(null, 12))),
        ex("Plank", 2, listOf(set(null, null, sec = 45))),
        ex("Pushdown", sets = listOf(set(70.0, 12), set(50.0, 10, kind = SetKind.DROP), set(35.0, 10, kind = SetKind.DROP))),
        ex("Curl", sets = listOf(set(65.0, 8), set(65.0, 4, kind = SetKind.REST_PAUSE))),
        ex("One-Arm Row", eachSide = true, sets = listOf(set(50.0, 10), set(30.0, 12, SetSide.LEFT, SetKind.DROP)))
    ))

    @Test fun `round trip - superset, circuit, drop, rest-pause and a left-side drop`() {
        val wire = TrainPlanEncoder.workoutWire(typedRoutine)
        val json = JSONObject().put("v", 1).put("t", "plan").put("l", "c1").put("n", "Doug").put("w", JSONArray().put(wire))
        val link = "1u" + Base64.getUrlEncoder().withoutPadding().encodeToString(json.toString().toByteArray())
        val back = typed(wire, decodeLink(link, "c1").workouts.single().exercises)
        assertEquals(typedRoutine.exercises.map { it.group }, back.map { it.group })
        assertEquals(typedRoutine.exercises.map { e -> e.sets.map { it.kind } }, back.map { e -> e.sets.map { it.kind } })
        assertEquals(typedRoutine.exercises.map { e -> e.sets.map { it.side } }, back.map { e -> e.sets.map { it.side } })
        assertEquals(typedRoutine.exercises.map { e -> e.sets.map { it.targetReps } }, back.map { e -> e.sets.map { it.targetReps } })
        assertEquals(typedRoutine.exercises.map(PrescriptionSides::summary), back.map(PrescriptionSides::summary))
    }

    /* ---------------- storage and the backup ---------------- */

    @Test fun `round trip through the backup - group and kind as Coach web and iOS spell them`() {
        val out = JSONObject(BackupCodec.export(emptyList(), null, emptyList(), emptyList(), listOf(typedRoutine), emptyList(), emptyMap(), emptyList()))
        val exercises = out.getJSONArray("routines").getJSONObject(0).getJSONArray("exercises")
        fun e(i: Int) = exercises.getJSONObject(i)
        assertFalse("not grouped is never written", e(0).has("group"))
        assertEquals(listOf(null, 1, 1, 2, 2, 2, null, null, null), (0 until exercises.length()).map { e(it).opt("group") })
        assertFalse("straight is never written", e(6).getJSONArray("sets").getJSONObject(0).has("kind"))
        assertEquals("drop", e(6).getJSONArray("sets").getJSONObject(1).getString("kind"))
        assertEquals("restPause", e(7).getJSONArray("sets").getJSONObject(1).getString("kind"))
        assertEquals("left", e(8).getJSONArray("sets").getJSONObject(1).getString("side"))
        assertEquals("drop", e(8).getJSONArray("sets").getJSONObject(1).getString("kind"))

        val restored = BackupCodec.restore(out.toString()).routines.single()
        assertEquals(typedRoutine.exercises.map { it.group }, restored.exercises.map { it.group })
        assertEquals(typedRoutine.exercises.map { e -> e.sets.map { it.kind } }, restored.exercises.map { e -> e.sets.map { it.kind } })
        assertEquals(typedRoutine.exercises.map { e -> e.sets.map { it.side } }, restored.exercises.map { e -> e.sets.map { it.side } })
    }

    @Test fun `a backup reads group and kind leniently, renumbered, with no group of one and no first-set kind`() {
        val routine = routineFromJson(JSONObject("""{"id":"w","name":"x","exercises":[
            {"name":"A","group":5,"sets":[{"targetReps":1},{"targetReps":1,"kind":"Drop"},{"targetReps":1,"kind":" restpause "}]},
            {"name":"B","group":5,"sets":[{"targetReps":1,"kind":"drop"},{"targetReps":1,"kind":"sideways"},{"targetReps":1,"kind":null}]},
            {"name":"C","group":"3","sets":[{"targetReps":1},{"targetReps":1,"kind":"straight"}]},
            {"name":"D","group":0,"sets":[]},
            {"name":"E","group":1.5,"sets":[]},
            {"name":"F","group":9,"sets":[]},
            {"name":"G","group":true,"sets":[]}
        ]}"""))
        val (a, b, c, d) = routine.exercises
        assertEquals("renumbered as the wire would", 1, a.group)
        assertEquals(1, b.group)
        assertEquals(listOf(null, SetKind.DROP, SetKind.REST_PAUSE), a.sets.map { it.kind })
        assertEquals("a first set has no kind; junk and null read straight", listOf<SetKind?>(null, null, null), b.sets.map { it.kind })
        assertNull("a string is not a group", c.group)
        assertNull("\"straight\" is how nothing is spelt", c.sets[1].kind)
        assertNull(d.group)
        assertTrue("a fraction, a lone number and a boolean are not groups", routine.exercises.drop(4).all { it.group == null })
        // And not written back.
        val again = routine.toJson().getJSONArray("exercises")
        assertFalse(again.getJSONObject(1).getJSONArray("sets").getJSONObject(1).has("kind"))
        assertFalse(again.getJSONObject(2).has("group"))
        assertFalse(again.getJSONObject(5).has("group"))
    }

    @Test fun `a group of one is not kept in storage, and a first-set kind is not written`() {
        val routine = Routine(id = "w", name = "x", exercises = listOf(
            ex("A", 1), ex("B"), ex("C", 1, listOf(set(100.0, 5, kind = SetKind.DROP), set(90.0, 5, kind = SetKind.DROP)))))
        val o = routine.toJson().getJSONArray("exercises")
        (0 until o.length()).forEach { assertFalse(o.getJSONObject(it).has("group")) }
        val sets = o.getJSONObject(2).getJSONArray("sets")
        assertFalse(sets.getJSONObject(0).has("kind"))
        assertEquals("drop", sets.getJSONObject(1).getString("kind"))
        assertTrue(routineFromJson(routine.toJson()).exercises.all { it.group == null })
    }

    @Test fun `a routine without types writes the backup object it always did, and an old file restores unchanged`() {
        val squat = RoutineExercise(name = "Squat", equipment = "Barbell", sets = listOf(PrescribedSet(100.0, 5), PrescribedSet(100.0, 5)))
        assertEquals(setOf("name", "equipment", "sets"), squat.toJson().keys().asSequence().toSet())
        assertEquals(setOf("targetWeightKg", "targetReps"), squat.sets[1].toJson().keys().asSequence().toSet())
        val text = """{"id":"w","name":"Lower A","exercises":[{"name":"Back Squat","equipment":"Barbell","sets":[{"targetWeightKg":100,"targetReps":5}]},{"name":"Split Squat","equipment":"Dumbbell","eachSide":true,"sets":[{"targetWeightKg":20,"targetReps":8},{"targetWeightKg":20,"targetReps":8,"side":"left"}]}]}"""
        val old = routineFromJson(JSONObject(text))
        assertTrue(old.exercises.all { it.group == null && it.sets.all { s -> s.kind == null } })
        assertEquals(plain(JSONObject(text)), plain(old.toJson()))
    }

    /* ---------------- the editor's join and split ---------------- */

    private fun join(groups: List<Int?>, i: Int, on: Boolean) = PrescriptionTypes.settingGroupedWithNext(groups, i, on)

    @Test fun `Superset with next joins two, and again makes a circuit of three`() {
        val none = listOf<Int?>(null, null, null, null)
        assertFalse(PrescriptionTypes.groupedWithNext(none, 0))
        val two = join(none, 0, true)
        assertEquals(listOf(1, 1, null, null), two)
        assertTrue(PrescriptionTypes.groupedWithNext(two, 0))
        assertEquals(listOf(1, 1, 1, null), join(two, 1, true))
        // Joining the exercise before a group takes the whole group.
        assertEquals(listOf(1, 1, 1), join(listOf(null, 1, 1), 0, true))
    }

    @Test fun `joining two groups makes one`() {
        assertEquals(listOf(1, 1, 1, 1), join(listOf(1, 1, 2, 2), 1, true))
    }

    @Test fun `splitting keeps what is before the split and gives the rest a group of their own`() {
        assertEquals("a superset is left behind", listOf(null, 1, 1, null), join(listOf(1, 1, 1, null), 0, false))
        assertEquals(listOf(1, 1, 2, 2), join(listOf(1, 1, 1, 1), 1, false))
        assertEquals(listOf(null, null), join(listOf(1, 1), 0, false))
    }

    @Test fun `the last exercise has no next, and asking changes nothing`() {
        assertEquals(listOf(null, null), join(listOf(null, null), 1, true))
        assertFalse(PrescriptionTypes.groupedWithNext(listOf(null, null), 1))
    }

    @Test fun `removing the middle of a superset leaves no group of one, and of a circuit a superset`() {
        assertEquals(listOf(null, null), PrescriptionTypes.runs(listOf(1, null)))
        assertEquals(listOf(1, 1), PrescriptionTypes.runs(listOf(1, 1)))
        // Through the editor: a line deleted from the box.
        val routine = Routine(name = "x", exercises = listOf(ex("A", 1), ex("B", 1), ex("C")))
        val state = RoutineEditing.initial(routine)
        val parsed = com.dugcanlift.coach.ui.parseExercises("A | | 2 x 5 @ 100\nC | | 2 x 5 @ 100")
        assertTrue(RoutineEditing.apply(parsed, routine, state) { null }.all { it.group == null })
    }

    @Test fun `the editor's toggle writes groups as the wire numbers them, and they land on save`() {
        val parsed = com.dugcanlift.coach.ui.parseExercises("Bench | Dumbbell | 3 x 10 @ 27\nRow | Cable | 3 x 12 @ 54\nSwing | Kettlebells | 3 x 15 @ 24\nCurl | Barbell | 2 x 8 @ 29")
        var state = emptyMap<String, RoutineEditing.ExerciseSides>()
        state = RoutineEditing.withGroupedWithNext(state, parsed, 0, true) { null }
        assertEquals(listOf(1, 1, null, null), RoutineEditing.apply(parsed, Routine(name = "x"), state) { null }.map { it.group })
        state = RoutineEditing.withGroupedWithNext(state, parsed, 1, true) { null }
        val circuit = RoutineEditing.apply(parsed, Routine(name = "x"), state) { null }
        assertEquals(listOf(1, 1, 1, null), circuit.map { it.group })
        assertEquals(listOf("Circuit A · A1", "Circuit A · A2", "Circuit A · A3", null),
            PrescriptionTypes.groupLabels(circuit).map { it?.text })
        state = RoutineEditing.withGroupedWithNext(state, parsed, 0, false) { null }
        assertEquals(listOf(null, 1, 1, null), RoutineEditing.apply(parsed, Routine(name = "x"), state) { null }.map { it.group })
        // Moving a line away from its partner leaves no group of one.
        val moved = com.dugcanlift.coach.ui.parseExercises("Row | Cable | 3 x 12 @ 54\nBench | Dumbbell | 3 x 10 @ 27\nCurl | Barbell | 2 x 8 @ 29\nSwing | Kettlebells | 3 x 15 @ 24")
        assertTrue(RoutineEditing.apply(moved, Routine(name = "x"), state) { null }.all { it.group == null })
    }

    @Test fun `a set's kind lands on the saved set, and never on the first`() {
        val parsed = com.dugcanlift.coach.ui.parseExercises("Pushdown | Cable | 3 x 12 @ 30")
        val key = RoutineEditing.keys(parsed).single()
        var sides = RoutineEditing.ExerciseSides(eachSide = false)
        sides = RoutineEditing.withKind(sides, 2, SetKind.DROP)
        sides = RoutineEditing.withKind(sides, 0, SetKind.REST_PAUSE)
        val saved = RoutineEditing.apply(parsed, Routine(name = "x"), mapOf(key to sides)) { null }.single()
        assertEquals(listOf(null, null, SetKind.DROP), saved.sets.map { it.kind })
        assertEquals("30 × 12, 30 × 12, 30 × 12 drop", PrescriptionSides.summary(saved))
        assertTrue(PrescriptionTypes.showsKinds(saved, asked = false))
        assertFalse(PrescriptionTypes.showsKinds(saved.copy(sets = saved.sets.map { it.copy(kind = null) }), asked = false))
    }

    @Test fun `a saved routine opens with its groups and kinds, and keeps them unchanged`() {
        val routine = typedRoutine.copy(exercises = typedRoutine.exercises.map { it.copy(equipment = "Dumbbell") })
        val state = RoutineEditing.initial(routine)
        val lines = routine.exercises.joinToString("\n", transform = RoutineEditing::renderLine)
        val again = RoutineEditing.apply(com.dugcanlift.coach.ui.parseExercises(lines), routine, state) { null }
        assertEquals(routine.exercises.map { it.group }, again.map { it.group })
        assertEquals(routine.exercises.map { e -> e.sets.map { it.kind } }, again.map { e -> e.sets.map { it.kind } })
    }

    @Test fun `the editor's types survive a recreation`() {
        val state = mapOf("row|dumbbell#0" to RoutineEditing.ExerciseSides(true, listOf(null, SetSide.LEFT), asked = true,
            group = 2, kinds = listOf(null, SetKind.DROP, SetKind.REST_PAUSE), askedKinds = true))
        assertEquals(state, RoutineEditing.decode(RoutineEditing.encode(state)))
        val plainState = mapOf("row|#0" to RoutineEditing.ExerciseSides(false))
        assertEquals(plainState, RoutineEditing.decode(RoutineEditing.encode(plainState)))
    }

    /* ---------------- how it reads ---------------- */

    @Test fun `a group is labelled Superset A for two and Circuit B for three, members A1, A2`() {
        val labels = PrescriptionTypes.groupLabels(listOf(
            ex("Plain"), ex("Bench", 1), ex("Row", 1), ex("Swing", 2), ex("Push-Up", 2), ex("Plank", 2), ex("Curl", 9)))
        assertNull(labels[0])
        assertEquals(listOf("Superset A · A1", "Superset A · A2", "Circuit B · B1", "Circuit B · B2", "Circuit B · B3"),
            labels.subList(1, 6).map { it!!.text })
        assertNull("a group of one is not labelled", labels[6])
        assertEquals(listOf(null, true, false, true, false, false, null), labels.map { it?.first })
    }

    @Test fun `the letters run on past Z`() {
        val many = (1..28).flatMap { listOf<Int?>(it, it) }
        val labels = PrescriptionTypes.labels(many)
        assertEquals("Superset Z", labels[50]!!.group)
        assertEquals("Superset AA", labels[52]!!.group)
        assertEquals("AB2", labels[55]!!.member)
    }

    private fun kgSet(kg: Double?, reps: Int?, side: SetSide? = null, kind: SetKind? = null) =
        PrescribedSet(targetWeightKg = kg, targetReps = reps, side = side, kind = kind)

    @Test fun `a set reads with its kind as it reads with its side`() {
        assertEquals("50 × 10 drop", PrescriptionSides.setText(kgSet(50.0, 10, kind = SetKind.DROP)))
        assertEquals("65 × 4 rest-pause", PrescriptionSides.setText(kgSet(65.0, 4, kind = SetKind.REST_PAUSE)))
        assertEquals("30 × 12 L drop", PrescriptionSides.setText(kgSet(30.0, 12, SetSide.LEFT, SetKind.DROP)))
        assertEquals("30 × 12", PrescriptionSides.setText(kgSet(30.0, 12)))
        assertEquals("70 × 12, 70 × 12, 50 × 10 drop, 35 × 10 drop", PrescriptionSides.summary(ex("Pushdown", sets = listOf(
            kgSet(70.0, 12), kgSet(70.0, 12), kgSet(50.0, 10, kind = SetKind.DROP), kgSet(35.0, 10, kind = SetKind.DROP)))))
        assertEquals("65 × 8, 65 × 4 rest-pause", PrescriptionSides.summary(ex("Curl", sets = listOf(
            kgSet(65.0, 8), kgSet(65.0, 4, kind = SetKind.REST_PAUSE)))))
    }

    @Test fun `each side keeps a kind in the summary, on the plain sets and the extras`() {
        fun summary(vararg s: PrescribedSet) = PrescriptionSides.summary(ex("Row", eachSide = true, sets = s.toList()))
        assertEquals("50 × 10, 30 × 12 drop each side", summary(kgSet(50.0, 10), kgSet(30.0, 12, kind = SetKind.DROP)))
        assertEquals("2 × 50 × 10 each side + 30 × 12 L drop",
            summary(kgSet(50.0, 10), kgSet(50.0, 10), kgSet(30.0, 12, SetSide.LEFT, SetKind.DROP)))
        // An extra that is the same set, kind and all, is still "+ 1 L" -- here the plain sets differ, so it is spelt out.
        assertEquals("50 × 10, 30 × 12 drop each side + 30 × 12 L drop",
            summary(kgSet(50.0, 10), kgSet(30.0, 12, kind = SetKind.DROP), kgSet(30.0, 12, SetSide.LEFT, SetKind.DROP)))
        assertEquals("2 × 30 × 12 drop each side + 1 L",
            summary(kgSet(30.0, 12, kind = SetKind.DROP), kgSet(30.0, 12, kind = SetKind.DROP), kgSet(30.0, 12, SetSide.LEFT, SetKind.DROP)))
    }

    @Test fun `a kind does not change what an exercise asks for`() {
        assertEquals(PrescriptionSides.Targets(left = 2, right = 1, both = 0),
            PrescriptionSides.targets(ex("Row", eachSide = true, sets = listOf(kgSet(50.0, 10), kgSet(30.0, 12, SetSide.LEFT, SetKind.DROP)))))
    }

    /* ---------------- the interop fixture ---------------- */

    private val fixtureLink by lazy { fixture("web-plan-set-types.txt") }

    @Test fun `fixture - a v1 plan link, like its siblings`() {
        assertTrue(fixtureLink.startsWith("https://www.dugcanlift.com/lift/#1z"))
        val raw = rawJson(fixtureLink)
        assertEquals(1, raw.getInt("v"))
        assertEquals("plan", raw.getString("t"))
        assertEquals("a1b2c3d4", raw.getString("l"))
    }

    @Test fun `fixture - the raw wire carries g and the kind bits as specified`() {
        val raw = rawJson(fixtureLink)
        assertEquals(plain(JSONArray("""[{"d":"2026-11-02","x":0}]""")), plain(raw.getJSONArray("k")))
        val e = raw.getJSONArray("w").getJSONObject(0).getJSONArray("e")
        fun x(i: Int) = e.getJSONObject(i)
        assertEquals(listOf("Back Squat", "Dumbbell Bench Press", "Seated Cable Rows", "Kettlebell Swing", "Push-Up", "Plank",
            "Triceps Pushdown", "Barbell Curl", "Single-Arm Dumbbell Row"), (0 until e.length()).map { x(it).getString("n") })
        assertEquals(listOf(null, 1, 1, 2, 2, 2, null, null, null), (0 until e.length()).map { x(it).opt("g") })
        assertEquals(listOf(null, null, null, null, null, null, null, null, 1), (0 until e.length()).map { x(it).opt("b") })
        assertEquals("[[70,12],[70,12],[50,10,null,null,null,8],[35,10,null,null,null,8]]", x(6).getJSONArray("s").toString())
        assertEquals("[[65,8],[65,4,null,null,null,16]]", x(7).getJSONArray("s").toString())
        assertEquals("left and drop is 10", "[[50,10],[50,10],[30,12,null,null,null,10]]", x(8).getJSONArray("s").toString())
        // No first set anywhere carries a kind.
        (0 until e.length()).forEach { assertNull(kindOf(x(it).getJSONArray("s").getJSONArray(0))) }
    }

    @Test fun `fixture - it decodes to the groups and kinds Coach web's editor wrote`() {
        val raw = rawJson(fixtureLink).getJSONArray("w").getJSONObject(0)
        val exercises = typed(raw, decodeLink(fixtureLink, "a1b2c3d4").workouts.single().exercises)
        assertEquals(listOf(null, 1, 1, 2, 2, 2, null, null, null), exercises.map { it.group })
        assertEquals(listOf(null, "Superset A · A1", "Superset A · A2", "Circuit B · B1", "Circuit B · B2", "Circuit B · B3",
            null, null, null), PrescriptionTypes.groupLabels(exercises).map { it?.text })
        assertEquals(listOf(null, null, SetKind.DROP, SetKind.DROP), exercises[6].sets.map { it.kind })
        assertEquals(listOf(null, SetKind.REST_PAUSE), exercises[7].sets.map { it.kind })
        assertEquals(listOf(null, null, SetKind.DROP), exercises[8].sets.map { it.kind })
        assertEquals(SetSide.LEFT, exercises[8].sets[2].side)
        assertTrue(exercises[8].eachSide)
        // Coach web's summaries, in the kilograms this app shows: 225 lb is 102.1 kg.
        assertEquals(listOf(
            "3 × 102.1 × 5 · @8",
            "3 × 27.2 × 10",
            "3 × 54.4 × 12",
            "3 × 24 × 15",
            "3 × 12 reps",
            "2 × 45s",
            "31.8 × 12, 31.8 × 12, 22.7 × 10 drop, 15.9 × 10 drop",
            "29.5 × 8, 29.5 × 4 rest-pause",
            "2 × 22.7 × 10 each side + 13.6 × 12 L drop"
        ), exercises.map(PrescriptionSides::summary))
    }

    @Test fun `fixture - an old decoder reads the right exercises, sets, numbers and sides`() {
        // The kit's PlanLinkCodec has never heard of g or bits 3-4: it is LIFT Android as it ships.
        val e = decodeLink(fixtureLink, "a1b2c3d4").workouts.single().exercises
        assertEquals(9, e.size)
        assertEquals(listOf(3, 3, 3, 3, 3, 2, 4, 2, 3), e.map { it.sets.size })
        assertEquals(listOf(70.0 to 12, 70.0 to 12, 50.0 to 10, 35.0 to 10), e[6].sets.map { it.weightLb to it.reps })
        assertEquals("the side bits read through the kind bits", ShareSide.LEFT, e[8].sets[2].side)
        assertTrue("a drop or rest-pause set is not mistaken for a side", e.take(8).all { x -> x.sets.all { it.side == null } })
    }

    @Test fun `fixture - decoded and encoded again, it is the same JSON`() {
        val raw = rawJson(fixtureLink).getJSONArray("w")
        val payload = decodeLink(fixtureLink, "a1b2c3d4")
        val again = JSONArray(payload.workouts.mapIndexed { i, w ->
            TrainPlanEncoder.workoutWire(Routine(name = w.name, exercises = typed(raw.getJSONObject(i), w.exercises)))
        })
        assertEquals(plain(raw), plain(again))
    }

    /**
     * The same templates, built as a coach builds them -- the superset and the circuit joined with
     * "Superset with next" -- and sent through the encoder Train's Send calls, are the payload
     * Coach web sent. Compared as parsed JSON: the JVM's org.json does not keep key order (key
     * order is pinned on the insertion, above). Coach web always sends `r` and `m`, even empty,
     * where Coach leaves an empty one out (PLAN-FORMAT allows both), so the fixture's two empty
     * arrays are set aside first. Everything else -- every number, every null, every `g`, every
     * flags value -- must be the same.
     */
    @Test fun `fixture - Coach Android writes what Coach web wrote`() {
        val exercises = listOf(
            ex("Back Squat", equipment = "Barbell", note = "Belt on the last set.", sets = List(3) { set(225.0, 5, rpe = 8.0) }),
            ex("Dumbbell Bench Press", equipment = "Dumbbell", sets = List(3) { set(60.0, 10) }),
            ex("Seated Cable Rows", equipment = "Cable", sets = List(3) { set(120.0, 12) }),
            ex("Kettlebell Swing", equipment = "Kettlebells", sets = List(3) { set(53.0, 15) }),
            ex("Push-Up", equipment = "Body Only", sets = List(3) { set(null, 12) }),
            ex("Plank", equipment = "Body Only", note = "Two rounds only.", sets = List(2) { set(null, null, sec = 45) }),
            ex("Triceps Pushdown", equipment = "Cable", sets = listOf(set(70.0, 12), set(70.0, 12),
                set(50.0, 10, kind = SetKind.DROP), set(35.0, 10, kind = SetKind.DROP))),
            ex("Barbell Curl", equipment = "Barbell", sets = listOf(set(65.0, 8), set(65.0, 4, kind = SetKind.REST_PAUSE))),
            ex("Single-Arm Dumbbell Row", equipment = "Dumbbell", note = "Drop on the left only.", eachSide = true,
                sets = listOf(set(50.0, 10), set(50.0, 10), set(30.0, 12, SetSide.LEFT, SetKind.DROP)))
        )
        var groups: List<Int?> = exercises.map { null }
        groups = PrescriptionTypes.settingGroupedWithNext(groups, 1, true)
        groups = PrescriptionTypes.settingGroupedWithNext(groups, 3, true)
        groups = PrescriptionTypes.settingGroupedWithNext(groups, 4, true)
        val routine = Routine(name = "Set types A", exercises = exercises.zip(groups) { e, g -> e.copy(group = g) })
        // Stored and read back first, as a saved routine is before anyone sends it.
        val stored = routineFromJson(routine.toJson())
        val session = ScheduledSession(clientId = "a1b2c3d4", dayKey = "2026-11-02", routineId = stored.id)

        val web = rawJson(fixtureLink)
        assertEquals(0, (web.remove("r") as JSONArray).length())
        assertEquals(0, (web.remove("m") as JSONArray).length())
        val mine = TrainPlanEncoder.payload(listOf(stored), listOf(session), "a1b2c3d4", "Doug")
        assertEquals(plain(web), plain(mine))
    }

    /* ---------------- what the Booked card counts ---------------- */

    @Test fun `a set type changes no count on the Booked card`() {
        fun sent(flags: List<Int?>): SentPlan {
            val sets = JSONArray().also { a ->
                flags.forEach { f -> a.put(JSONArray(listOfNotNull(40, 8)).also { t ->
                    if (f != null) { t.put(JSONObject.NULL); t.put(JSONObject.NULL); t.put(JSONObject.NULL); t.put(f) } }) }
            }
            val payload = JSONObject().put("v", 1).put("t", "plan").put("l", "c").put("n", "")
                .put("w", JSONArray().put(JSONObject().put("n", "Arms").put("e", JSONArray()
                    .put(JSONObject().put("n", "Row").put("q", "Dumbbell").put("g", 1).put("s", sets))
                    .put(JSONObject().put("n", "Curl").put("q", "Dumbbell").put("g", 1).put("s", JSONArray().put(JSONArray(listOf(30, 10))))))))
                .put("k", JSONArray().put(JSONObject().put("d", "2026-10-12").put("x", 0)))
            return SentPlan("p", "c", 1000, "h", payload.toString())
        }
        val day = TrainingDay(dayKey = "2026-10-12", sessionName = null, focus = null, bodyweightLb = null, steps = null,
            foodCalories = null, foodProteinG = null, foodFatG = null, foodCarbsG = null, foodFiberG = null,
            sets = listOf(
                ExerciseSet("Row", "Dumbbell", 40.0, 8, null, null, null, false, null),
                ExerciseSet("Row", "Dumbbell", 40.0, 8, null, null, null, false, SetSide.LEFT),
                ExerciseSet("Row", "Dumbbell", 40.0, 8, null, null, null, false, SetSide.RIGHT)
            ), foodEntries = emptyList())
        fun card(plan: SentPlan) = PlanLog.compare(clientId = "c", sentPlans = listOf(plan), days = mapOf(day.dayKey to day),
            coverage = "2026-10-01" to "2026-10-31", unit = "lb", today = "2026-10-20").groups[0].days[0].exercises[0]
        val typed = card(sent(listOf(null, 10, 20)))
        val plainCard = card(sent(listOf(null, 2, 4)))
        assertEquals(plainCard, typed)
        assertEquals("the left-drop and right-rest-pause sets keep their sides", plainCard.sideLine, typed.sideLine)
    }
}
