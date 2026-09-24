package com.dugcanlift.coach.data

import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * What you booked, and what they logged -- a port of Coach web's `coach/plan-log.test.mjs`, case
 * for case, plus what only Android has: the covered window coming off a real import, and the
 * kilograms a routine stores never reaching either row.
 *
 * `fixtures/plan-log-sent-plan.json`, `plan-log-share-link.txt` and `plan-log-expected.json` are
 * the same three files Coach web and Coach iPhone check their own port against. They were written
 * by hand against PLAN-FORMAT and SHARE-FORMAT -- **never regenerate them from this code**: a
 * fixture captured from the code under test proves only that the code agrees with itself.
 */
class PlanLogTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun fixture(name: String) =
        javaClass.getResourceAsStream("/fixtures/$name")!!.bufferedReader().readText().trim()

    /**
     * The fixture's lines are English, as all three Coach builds' are. The rule itself reads the
     * reader's own locale, exactly as the browser's `toLocaleDateString(undefined, ...)` does, so
     * the fixture test says which locale it is checking rather than depending on the machine.
     */
    private val locale = Locale.getDefault()
    @Before fun setUp() { Locale.setDefault(Locale.US) }
    @After fun tearDown() { Locale.setDefault(locale) }

    /* ---------------- building a plan and a log by hand ---------------- */

    private fun ex(
        name: String,
        equipment: String?,
        sets: List<List<Any?>>,
        eachSide: Boolean = false
    ): JSONObject {
        val o = JSONObject().put("n", name)
        if (equipment != null) o.put("q", equipment)
        if (eachSide) o.put("b", 1)
        o.put("s", JSONArray().also { a ->
            sets.forEach { t -> a.put(JSONArray().also { row -> t.forEach { row.put(it ?: JSONObject.NULL) } }) }
        })
        return o
    }

    private fun workout(name: String, exercises: List<JSONObject>): JSONObject =
        JSONObject().put("n", name).put("e", JSONArray(exercises))

    private fun plan(
        bookings: List<Pair<String, Int>>,
        workouts: List<JSONObject>,
        id: String = "p1",
        clientId: String = "c",
        sentAt: Long = 1000
    ): SentPlan {
        val payload = JSONObject()
            .put("v", 1).put("t", "plan").put("l", clientId).put("n", "")
            .put("r", JSONArray()).put("m", JSONArray())
            .put("w", JSONArray(workouts))
            .put("k", JSONArray(bookings.map { (d, x) -> JSONObject().put("d", d).put("x", x) }))
        return SentPlan(id, clientId, sentAt, "h", payload.toString())
    }

    private fun set(
        name: String,
        equipment: String?,
        weightLb: Double?,
        reps: Int?,
        warmup: Boolean = false,
        side: SetSide? = null
    ) = ExerciseSet(name, equipment, weightLb, reps, null, null, null, warmup, side)

    private fun day(key: String, name: String?, sets: List<ExerciseSet>) = TrainingDay(
        dayKey = key, sessionName = name, focus = null, bodyweightLb = null, steps = null,
        foodCalories = null, foodProteinG = null, foodFatG = null, foodCarbsG = null,
        foodFiberG = null, sets = sets, foodEntries = emptyList()
    )

    private fun run(
        bookings: List<Pair<String, Int>>,
        workouts: List<JSONObject>,
        days: List<TrainingDay>,
        coverage: Pair<String, String>? = "2026-10-01" to "2026-10-31",
        unit: String = "lb",
        today: String = "2026-10-20"
    ) = PlanLog.compare(
        clientId = "c",
        sentPlans = listOf(plan(bookings, workouts)),
        days = days.associateBy { it.dayKey },
        coverage = coverage,
        unit = unit,
        today = today
    )

    private fun day(result: PlanLog.Result, i: Int) = result.groups[0].days[i]

    /* ---------------- the fixture pair ---------------- */

    private val expected by lazy { JSONObject(fixture("plan-log-expected.json")) }

    /** The share link read across the real wire, by the importer that reads a coach's real links. */
    private fun fromFixture(): PlanLog.Result {
        val repo = ClientRepository(tmp.root)
        val result = ShareLinkImporter.import(fragmentFrom(fixture("plan-log-share-link.txt")), repo)
        assertTrue(result is ImportResult.Imported)
        val client = repo.get("client-fixture")!!
        val sent = SentPlan.fromJson(JSONObject(fixture("plan-log-sent-plan.json")))!!
        return PlanLog.compare(
            clientId = "client-fixture",
            sentPlans = listOf(sent),
            days = client.days.associateBy { it.dayKey },
            coverage = client.coveredFrom!! to client.coveredTo!!,
            unit = client.displayUnit,
            today = "2026-10-18",
            weeks = 8
        )
    }

    @Test fun `the fixture pair produces exactly the lines the fixture says`() {
        val lines = expected.getJSONArray("lines").let { a -> (0 until a.length()).map { a.getString(it) } }
        assertEquals(lines, PlanLog.lines(fromFixture()))
    }

    @Test fun `the fixture pair produces exactly the counts and day states it says`() {
        val result = fromFixture()
        assertEquals(1, result.groups.size)
        val counts = expected.getJSONObject("counts")
        assertEquals(
            PlanLog.Counts(
                counts.getInt("booked"), counts.getInt("training"), counts.getInt("logged"),
                counts.getInt("notLogged"), counts.getInt("outside"), counts.getInt("other"),
                counts.getInt("meals")
            ),
            result.groups[0].counts
        )
        val states = expected.getJSONArray("dayStates").let { a -> (0 until a.length()).map { a.getString(it) } }
        assertEquals(states, result.groups[0].days.map { it.state })
        assertEquals("12–17 Oct", result.groups[0].range)
    }

    @Test fun `the covered window comes off the link the client sent`() {
        val repo = ClientRepository(tmp.root)
        ShareLinkImporter.import(fragmentFrom(fixture("plan-log-share-link.txt")), repo)
        val client = repo.get("client-fixture")!!
        // `r` and `t` of the fixture link, which is what makes Sat 17 Oct "outside the log they
        // sent" rather than a day the client failed to train.
        assertEquals("2026-09-21", client.coveredFrom)
        assertEquals("2026-10-16", client.coveredTo)
    }

    /* ---------------- the join, case by case ---------------- */

    @Test fun `a booked day the client logged reads logged`() {
        val r = run(
            listOf("2026-10-12" to 0),
            listOf(workout("Lower A", listOf(ex("Back Squat", "Barbell", listOf(listOf(225, 5)))))),
            listOf(day("2026-10-12", "Lower A", listOf(set("Back Squat", "Barbell", 225.0, 5))))
        )
        assertEquals("Mon 12 Oct · Lower A · logged", day(r, 0).text)
        assertEquals(PlanLog.Counts(1, 1, 1, 0, 0, 0, 0), r.groups[0].counts)
    }

    @Test fun `a booked day with nothing logged reads not logged, never missed`() {
        val r = run(
            listOf("2026-10-12" to 0),
            listOf(workout("Lower A", listOf(ex("Back Squat", "Barbell", listOf(listOf(225, 5)))))),
            emptyList()
        )
        assertEquals("Mon 12 Oct · Lower A · not logged", day(r, 0).text)
        assertEquals("Booked 1 day, 12 Oct · logged 0", r.groups[0].head)
    }

    @Test fun `a booked day outside the window the client sent is never called not logged`() {
        val r = run(
            listOf("2026-10-12" to 0),
            listOf(workout("Lower A", listOf(ex("Back Squat", "Barbell", listOf(listOf(225, 5)))))),
            emptyList(),
            coverage = "2026-09-01" to "2026-10-05"
        )
        assertEquals("Mon 12 Oct · Lower A · outside the log they sent", day(r, 0).text)
        assertEquals("Booked 1 day, 12 Oct · no log covering them", r.groups[0].head)
        assertFalse(PlanLog.lines(r).joinToString(" ").contains("not logged"))
    }

    @Test fun `a client who has sent nothing at all gets no log covering them`() {
        val r = run(
            listOf("2026-10-12" to 0),
            listOf(workout("Lower A", listOf(ex("Back Squat", "Barbell", listOf(listOf(225, 5)))))),
            emptyList(),
            coverage = null
        )
        assertEquals("outside", day(r, 0).state)
    }

    @Test fun `a logged day inside the span with no booking reads not booked`() {
        val r = run(
            listOf("2026-10-12" to 0, "2026-10-16" to 0),
            listOf(workout("Lower A", listOf(ex("Back Squat", "Barbell", listOf(listOf(225, 5)))))),
            listOf(day("2026-10-13", "Upper B", listOf(set("Bench Press", "Barbell", 185.0, 5))))
        )
        assertEquals(
            listOf(
                "Mon 12 Oct · Lower A · not logged",
                "Tue 13 Oct · Upper B · not booked",
                "Fri 16 Oct · Lower A · not logged"
            ),
            r.groups[0].days.map { it.text }
        )
        assertEquals(1, r.groups[0].counts.other)
        assertTrue(r.groups[0].head.endsWith("1 other day logged"))
    }

    @Test fun `a substitution shows as one pair on name alone, labelled`() {
        val r = run(
            listOf("2026-10-12" to 0),
            listOf(workout("Lower A", listOf(ex("Lat Pulldown", "Cable", listOf(listOf(140, 10)))))),
            listOf(day("2026-10-12", null, listOf(set("Lat Pulldown", "Machine", 140.0, 10))))
        )
        val e = day(r, 0).exercises
        assertEquals(1, e.size)
        assertEquals("Asked Cable · logged Machine", e[0].substitution)
        assertEquals(0, day(r, 0).alsoLogged.size)
    }

    @Test fun `an exact name and equipment match always wins over a name-only one`() {
        val r = run(
            listOf("2026-10-12" to 0),
            listOf(workout("Lower A", listOf(ex("Lat Pulldown", "Cable", listOf(listOf(140, 10)))))),
            listOf(day("2026-10-12", null, listOf(
                set("Lat Pulldown", "Machine", 150.0, 10),
                set("Lat Pulldown", "Cable", 140.0, 10)
            )))
        )
        val e = day(r, 0).exercises
        assertNull("the cable one is the match", e[0].substitution)
        assertEquals("140 × 10", e[0].logged!!.text)
        assertEquals("Lat Pulldown (Machine) · 1 set", day(r, 0).alsoLogged[0].text)
    }

    @Test fun `the same lift prescribed twice in a day pools into one prescription`() {
        val r = run(
            listOf("2026-10-12" to 0),
            listOf(workout("Lower A", listOf(
                ex("Back Squat", "Barbell", listOf(listOf(225, 5), listOf(225, 5))),
                ex("Back Squat", "Barbell", listOf(listOf(245, 3)))
            ))),
            listOf(day("2026-10-12", null, listOf(
                set("Back Squat", "Barbell", 225.0, 5),
                set("Back Squat", "Barbell", 225.0, 5),
                set("Back Squat", "Barbell", 245.0, 3)
            )))
        )
        val e = day(r, 0).exercises
        assertEquals(1, e.size)
        assertEquals("225 × 5 · 225 × 5 · 245 × 3", e[0].asked!!.text)
        assertNull(e[0].countLine)
    }

    @Test fun `the same lift logged twice in a day pools too`() {
        val r = run(
            listOf("2026-10-12" to 0),
            listOf(workout("Lower A", listOf(
                ex("Back Squat", "Barbell", listOf(listOf(225, 5), listOf(225, 5), listOf(245, 3)))
            ))),
            listOf(day("2026-10-12", null, listOf(
                set("Back Squat", "Barbell", 225.0, 5),
                set("Back Squat", "Barbell", 225.0, 5),
                set("Back Squat", "Barbell", 245.0, 3)
            )))
        )
        val e = day(r, 0).exercises
        assertEquals(1, e.size)
        assertEquals("225 × 5 · 225 × 5 · 245 × 3", e[0].logged!!.text)
        assertEquals(0, day(r, 0).alsoLogged.size)
    }

    @Test fun `two sent plans booking two spans are two groups, newest first`() {
        val workouts = listOf(workout("Lower A", listOf(ex("Back Squat", "Barbell", listOf(listOf(225, 5))))))
        val r = PlanLog.compare(
            clientId = "c",
            sentPlans = listOf(
                plan(listOf("2026-10-05" to 0), workouts, id = "old", sentAt = 1),
                plan(listOf("2026-10-12" to 0), workouts, id = "new", sentAt = 2)
            ),
            days = emptyMap(),
            coverage = "2026-10-01" to "2026-10-31",
            unit = "lb",
            today = "2026-10-20"
        )
        assertEquals(listOf("new", "old"), r.groups.map { it.id })
        assertEquals(listOf("12 Oct", "5 Oct"), r.groups.map { it.range })
    }

    @Test fun `a plan booking nothing in the last eight weeks is not a group at all`() {
        val r = run(
            listOf("2026-05-01" to 0),
            listOf(workout("Lower A", listOf(ex("Back Squat", "Barbell", listOf(listOf(225, 5)))))),
            emptyList()
        )
        assertEquals(emptyList<PlanLog.Group>(), r.groups)
        assertEquals(emptyList<String>(), PlanLog.lines(r))
    }

    @Test fun `a meal booked against a recipe the payload does not carry books nothing`() {
        // `m.x` indexes `r`, exactly as `k.x` indexes `w`. A booking that indexes nothing is
        // skipped rather than drawn as a dish with no name.
        val payload = JSONObject().put("v", 1).put("t", "plan").put("l", "c")
            .put("r", JSONArray())
            .put("m", JSONArray().put(JSONObject().put("d", "2026-10-12").put("s", 2).put("x", 0).put("q", 1)))
        val r = PlanLog.compare(
            clientId = "c",
            sentPlans = listOf(SentPlan("p", "c", 1, "h", payload.toString())),
            days = emptyMap(),
            coverage = "2026-10-01" to "2026-10-31",
            unit = "lb",
            today = "2026-10-20"
        )
        assertEquals(emptyList<PlanLog.Group>(), r.groups)
    }

    @Test fun `no plan was ever sent, so no card and no explanation on screen`() {
        val r = PlanLog.compare("c", emptyList(), emptyMap(), null, "lb", "2026-10-20")
        assertEquals(emptyList<PlanLog.Group>(), r.groups)
        assertEquals(emptyList<PlanLog.LiftRows>(), r.byLift)
        assertEquals("not even the footer", emptyList<String>(), PlanLog.lines(r))
    }

    /* ---------------- sets ---------------- */

    @Test fun `sets are counted, never paired one to one`() {
        val r = run(
            listOf("2026-10-12" to 0),
            listOf(workout("Lower A", listOf(ex("Romanian Deadlift", "Barbell",
                listOf(listOf(185, 8), listOf(185, 8), listOf(185, 8), listOf(185, 8)))))),
            listOf(day("2026-10-12", null, listOf(
                set("Romanian Deadlift", "Barbell", 185.0, 8),
                set("Romanian Deadlift", "Barbell", 185.0, 8),
                set("Romanian Deadlift", "Barbell", 185.0, 6)
            )))
        )
        val e = day(r, 0).exercises[0]
        assertEquals("Asked 4 sets · logged 3", e.countLine)
        assertEquals("185 × 8 · 185 × 8 · 185 × 8 · 185 × 8", e.asked!!.text)
        assertEquals("185 × 8 · 185 × 8 · 185 × 6", e.logged!!.text)
    }

    @Test fun `warmups are excluded from both counts`() {
        val r = run(
            listOf("2026-10-12" to 0),
            listOf(workout("Lower A", listOf(ex("Back Squat", "Barbell", listOf(listOf(225, 5), listOf(225, 5)))))),
            listOf(day("2026-10-12", null, listOf(
                set("Back Squat", "Barbell", 135.0, 8, warmup = true),
                set("Back Squat", "Barbell", 225.0, 5),
                set("Back Squat", "Barbell", 225.0, 5)
            )))
        )
        val e = day(r, 0).exercises[0]
        assertEquals("225 × 5 · 225 × 5", e.logged!!.text)
        assertNull(e.countLine)
    }

    @Test fun `flags are masked, never compared, for all six values`() {
        // 0 both, 1 warmup, 2 left, 3 left warmup, 4 right, 5 right warmup -- read off the wire by
        // the importer, exactly as a real link brings them.
        val repo = ClientRepository(tmp.root)
        val wire = JSONArray()
        listOf(0, 1, 2, 3, 4, 5).forEach { flags ->
            wire.put(JSONArray(listOf(100, 5, JSONObject.NULL, JSONObject.NULL, JSONObject.NULL, flags)))
        }
        val payload = JSONObject()
            .put("v", 1)
            .put("c", JSONObject().put("i", "c").put("n", "Flags").put("u", "lb"))
            .put("z", 1)
            .put("r", "2026-10-12").put("t", "2026-10-12")
            .put("x", JSONArray(listOf("Curl|Dumbbell")))
            .put("d", JSONArray().put(JSONObject().put("k", 0).put("n", "Arms")
                .put("w", JSONArray().put(JSONArray().put(0).put(wire)))))
        val fragment = "1u" + com.dugcanlift.kit.CompactEncoding.base64Url(payload.toString().toByteArray())
        ShareLinkImporter.import(fragment, repo)
        val stored = repo.get("c")!!.days.associateBy { it.dayKey }

        val r = PlanLog.compare(
            clientId = "c",
            sentPlans = listOf(plan(
                listOf("2026-10-12" to 0),
                listOf(workout("Arms", listOf(ex("Curl", "Dumbbell", listOf(listOf(100, 5)), eachSide = true))))
            )),
            days = stored,
            coverage = "2026-10-01" to "2026-10-31",
            unit = "lb",
            today = "2026-10-20"
        )
        val e = day(r, 0).exercises[0]
        // Working sets only: flags 0 (both), 2 (left) and 4 (right). 1, 3 and 5 are warmups
        // whatever side they name.
        assertEquals("L 1/1 · R 1/1 · 1 both", e.sideLine)
        assertEquals("L 100 × 5   R 100 × 5   Both 100 × 5", e.logged!!.text)
    }

    @Test fun `sides read L 3 of 3 and R 2 of 3, and over is never capped`() {
        val left = (1..4).map { set("Bulgarian Split Squat", "Dumbbell", 40.0, 8, side = SetSide.LEFT) }
        val right = (1..2).map { set("Bulgarian Split Squat", "Dumbbell", 40.0, 8, side = SetSide.RIGHT) }
        val r = run(
            listOf("2026-10-12" to 0),
            listOf(workout("Lower A", listOf(ex("Bulgarian Split Squat", "Dumbbell",
                listOf(listOf(40, 8), listOf(40, 8), listOf(40, 8)), eachSide = true)))),
            listOf(day("2026-10-12", null, left + right))
        )
        assertEquals("L 4/3 · R 2/3", day(r, 0).exercises[0].sideLine)
    }

    @Test fun `an each-side exercise's ask is twice its tuples`() {
        val r = run(
            listOf("2026-10-12" to 0),
            listOf(workout("Lower A", listOf(ex("Lunge", "Dumbbell",
                listOf(listOf(40, 8), listOf(40, 8), listOf(40, 8)), eachSide = true)))),
            listOf(day("2026-10-12", null, listOf(set("Lunge", "Dumbbell", 40.0, 8, side = SetSide.LEFT))))
        )
        val e = day(r, 0).exercises[0]
        assertEquals("three tuples each side is six sets", "L 1/3 · R 0/3", e.sideLine)
        assertTrue(e.title.endsWith(" · each side"))
        assertTrue(e.asked!!.text.endsWith(" each side"))
    }

    @Test fun `a plan with no sides produces no side line at all`() {
        val r = run(
            listOf("2026-10-12" to 0),
            listOf(workout("Lower A", listOf(ex("Back Squat", "Barbell", listOf(listOf(225, 5)))))),
            listOf(day("2026-10-12", null, listOf(set("Back Squat", "Barbell", 225.0, 5))))
        )
        assertNull(day(r, 0).exercises[0].sideLine)
        assertEquals("Back Squat (Barbell)", day(r, 0).exercises[0].title)
    }

    @Test fun `blank stays blank, so null and 5 is five reps and never zero by five`() {
        assertEquals("5 reps", PlanLog.setText(PlanLog.PlanSet(weightLb = null, reps = 5), "lb"))
        assertEquals("225 lb", PlanLog.setText(PlanLog.PlanSet(weightLb = 225.0, reps = null), "lb"))
        assertEquals(
            "1,600 m · 10:00",
            PlanLog.setText(PlanLog.PlanSet(durationSec = 600.0, distanceM = 1600.0), "lb")
        )
        assertEquals("as written", PlanLog.setText(PlanLog.PlanSet(), "lb"))
    }

    /* ---------------- units ---------------- */

    @Test fun `a kg client reads kilograms on both rows, from one source`() {
        val r = run(
            listOf("2026-10-12" to 0),
            listOf(workout("Lower A", listOf(ex("Back Squat", "Barbell", listOf(listOf(220, 5)))))),
            listOf(day("2026-10-12", null, listOf(set("Back Squat", "Barbell", 220.0, 5)))),
            unit = "kg"
        )
        val e = day(r, 0).exercises[0]
        assertEquals("100 × 5", e.asked!!.text)
        assertEquals("100 × 5", e.logged!!.text)
        // The stored payload and the wire both stay pounds; only the display moved.
        val stored = plan(
            listOf("2026-10-12" to 0),
            listOf(workout("Lower A", listOf(ex("Back Squat", "Barbell", listOf(listOf(220, 5))))))
        )
        assertEquals(
            220.0,
            stored.payload().getJSONArray("w").getJSONObject(0)
                .getJSONArray("e").getJSONObject(0)
                .getJSONArray("s").getJSONArray(0).getDouble(0),
            0.0001
        )
    }

    /**
     * The whole chain, in the units it actually travels in: a routine's kilograms, out through
     * this app's own encoder as PLAN-FORMAT's pounds, recorded as sent, and read back beside a
     * LIFT log that is pounds too -- both rows in the client's kilograms, from one source.
     *
     * A missing conversion anywhere along it is silent and 2.2x wrong, and it is a number a coach
     * reads about a client. [PrescribedSet.targetWeightKg] must never reach this card.
     */
    @Test fun `a routine's kilograms never reach either row, and both rows agree`() {
        val routine = Routine(
            id = "r1",
            name = "Lower A",
            exercises = listOf(RoutineExercise(
                name = "Back Squat",
                equipment = "Barbell",
                sets = listOf(PrescribedSet(targetWeightKg = 100.0, targetReps = 5))
            ))
        )
        val session = ScheduledSession(id = "s1", clientId = "c", dayKey = "2026-10-12", routineId = "r1")
        val payload = TrainPlanEncoder.payload(listOf(routine), listOf(session), "c", "Coach")

        // Pounds on the wire, as PLAN-FORMAT says and TrainPlanEncoder.kgToLb does.
        val tuple = payload.getJSONArray("w").getJSONObject(0)
            .getJSONArray("e").getJSONObject(0).getJSONArray("s").getJSONArray(0)
        assertEquals(220.462262, tuple.getDouble(0), 0.0001)

        val sent = SentPlan("p", "c", 1, SentPlans.hash(payload), payload.toString())
        // The client logged it: LIFT sends pounds, and the importer stores pounds.
        val logged = day("2026-10-12", "Lower A", listOf(set("Back Squat", "Barbell", 220.462262, 5)))

        val kg = PlanLog.compare("c", listOf(sent), mapOf(logged.dayKey to logged),
            "2026-10-01" to "2026-10-31", "kg", "2026-10-20")
        val kgLines = day(kg, 0).exercises[0]
        assertEquals("100 × 5", kgLines.asked!!.text)
        assertEquals("100 × 5", kgLines.logged!!.text)

        // And a pounds client reads pounds on both, from the same two numbers.
        val lb = PlanLog.compare("c", listOf(sent), mapOf(logged.dayKey to logged),
            "2026-10-01" to "2026-10-31", "lb", "2026-10-20")
        val lbLines = day(lb, 0).exercises[0]
        assertEquals("220 × 5", lbLines.asked!!.text)
        assertEquals("220 × 5", lbLines.logged!!.text)
    }

    /* ---------------- by lift ---------------- */

    @Test fun `by lift stacks the same lines under one heading, by date`() {
        val workouts = listOf(workout("Lower A", listOf(ex("Back Squat", "Barbell", listOf(listOf(225, 5))))))
        val r = run(
            listOf("2026-10-12" to 0, "2026-10-15" to 0),
            workouts,
            listOf(
                day("2026-10-12", null, listOf(set("Back Squat", "Barbell", 225.0, 5))),
                day("2026-10-15", null, listOf(set("Back Squat", "Barbell", 230.0, 5)))
            )
        )
        assertEquals(1, r.byLift.size)
        assertEquals("Back Squat (Barbell)", r.byLift[0].title)
        assertEquals(listOf("12 Oct", "15 Oct"), r.byLift[0].entries.map { it.whenText })
        assertEquals(listOf("225 × 5", "230 × 5"), r.byLift[0].entries.map { it.exercise.logged!!.text })
    }

    @Test fun `by lift shows the weeks a lift was booked and not logged too`() {
        val workouts = listOf(workout("Lower A", listOf(ex("Back Squat", "Barbell", listOf(listOf(225, 5))))))
        val r = run(
            listOf("2026-10-12" to 0, "2026-10-15" to 0, "2026-10-19" to 0),
            workouts,
            listOf(day("2026-10-12", null, listOf(set("Back Squat", "Barbell", 225.0, 5)))),
            coverage = "2026-10-01" to "2026-10-16"
        )
        // Shown only on the weeks it was logged, a lift reads steadier than it was.
        assertEquals(
            listOf(
                "12 Oct" to "Back Squat (Barbell)",
                "15 Oct" to "Back Squat (Barbell) · not logged",
                "19 Oct" to "Back Squat (Barbell) · outside the log they sent"
            ),
            r.byLift[0].entries.map { it.whenText to it.exercise.title }
        )
        // And the heading is the lift, never one day's verdict on it.
        assertEquals("Back Squat (Barbell)", r.byLift[0].title)
    }

    @Test fun `the day view does not recite a missed day's prescription`() {
        val r = run(
            listOf("2026-10-12" to 0),
            listOf(workout("Lower A", listOf(ex("Back Squat", "Barbell", listOf(listOf(225, 5)))))),
            emptyList()
        )
        assertEquals("the row above already says it", emptyList<PlanLog.ExerciseLines>(), day(r, 0).exercises)
        assertEquals("but by lift still has it", 1, day(r, 0).booked.size)
    }

    @Test fun `a lift nobody asked for that was all warmups is not zero sets on screen`() {
        val r = run(
            listOf("2026-10-12" to 0),
            listOf(workout("Lower A", listOf(ex("Back Squat", "Barbell", listOf(listOf(225, 5)))))),
            listOf(day("2026-10-12", null, listOf(
                set("Back Squat", "Barbell", 225.0, 5),
                set("Treadmill", "Machine", null, null, warmup = true)
            )))
        )
        assertEquals(
            "working sets are the claim everywhere else",
            emptyList<PlanLog.AlsoLogged>(), day(r, 0).alsoLogged
        )
    }

    /* ---------------- meals ----------------
     *
     * A booked meal is a recipe in a slot on a day. What comes back is a list of food entries --
     * free text, barcode scans, a recipe logged as a meal -- named out of the client's own food
     * dictionary, with no id joining them to anything, and itemised only if the client chose to
     * itemise.
     *
     * So Coach says what it booked and what the log holds at that slot, and never that the two are
     * the same dish. The tests that matter most here are the negative ones: nothing this card
     * produces may tell a coach their client ate something they did not.
     */

    private fun recipe(name: String): JSONObject = JSONObject()
        .put("n", name).put("s", 4)
        .put("u", JSONArray(listOf(400, 30, 40, 12, 6)))
        .put("i", JSONArray()).put("t", JSONArray())

    private fun meal(date: String, slot: Int, x: Int, servings: Double = 1.0): JSONObject =
        JSONObject().put("d", date).put("s", slot).put("x", x).put("q", servings)

    private val breakfast = 0
    private val lunch = 1
    private val dinner = 2
    private val snack = 3

    private fun foodPlan(
        recipes: List<JSONObject>,
        meals: List<JSONObject>,
        workouts: List<JSONObject> = emptyList(),
        bookings: List<Pair<String, Int>> = emptyList()
    ): SentPlan {
        val payload = JSONObject()
            .put("v", 1).put("t", "plan").put("l", "c").put("n", "")
            .put("r", JSONArray(recipes)).put("m", JSONArray(meals))
            .put("w", JSONArray(workouts))
            .put("k", JSONArray(bookings.map { (d, x) -> JSONObject().put("d", d).put("x", x) }))
        return SentPlan("p1", "c", 1000, "h", payload.toString())
    }

    /**
     * A day's food as the importer stores it: macros already multiplied by servings, and [slot] the
     * wire's own meal index -- 0 breakfast through 3 snack. A slot outside that range is an entry
     * tied to no meal, which is what Coach web's importer writes as an empty word.
     */
    private fun food(name: String, slot: Int = 9) =
        ClientFoodEntry(name, 1.0, 400.0, 30.0, 12.0, 40.0, 6.0, slot)

    private fun foodDay(
        key: String,
        foods: List<ClientFoodEntry> = emptyList(),
        totals: Boolean = false,
        zeroTotals: Boolean = false,
        sets: List<ExerciseSet> = emptyList(),
        name: String? = null
    ) = TrainingDay(
        dayKey = key, sessionName = name, focus = null, bodyweightLb = null, steps = null,
        foodCalories = if (totals) 2100.0 else if (zeroTotals) 0.0 else null,
        foodProteinG = if (totals) 160.0 else if (zeroTotals) 0.0 else null,
        foodFatG = if (totals) 70.0 else if (zeroTotals) 0.0 else null,
        foodCarbsG = if (totals) 210.0 else if (zeroTotals) 0.0 else null,
        foodFiberG = if (totals) 28.0 else if (zeroTotals) 0.0 else null,
        sets = sets, foodEntries = foods
    )

    private fun runMeals(
        recipes: List<JSONObject>,
        meals: List<JSONObject>,
        days: List<TrainingDay> = emptyList(),
        coverage: Pair<String, String>? = "2026-10-01" to "2026-10-31"
    ) = PlanLog.compare(
        clientId = "c",
        sentPlans = listOf(foodPlan(recipes, meals)),
        days = days.associateBy { it.dayKey },
        coverage = coverage,
        unit = "lb",
        today = "2026-10-20"
    )

    @Test fun `a plan that books meals and no training is a card, not a skipped group`() {
        val r = runMeals(listOf(recipe("Beef Chilli")), listOf(meal("2026-10-12", dinner, 0, 2.0)))
        assertEquals("the training-only card skipped this entirely", 1, r.groups.size)
        assertEquals("Booked 1 day, 12 Oct · 1 meal booked", r.groups[0].head)
        assertEquals("Mon 12 Oct · 1 meal booked", day(r, 0).text)
        assertEquals("meals", day(r, 0).state)
    }

    @Test fun `a meals-only day is never called not logged`() {
        // There is no training booked to be logged or not. Saying "not logged" against one would be
        // Coach inventing a booking to hold against a client.
        val r = runMeals(listOf(recipe("Beef Chilli")), listOf(meal("2026-10-12", dinner, 0)))
        val every = PlanLog.lines(r).joinToString(" · ")
        assertFalse(every, every.contains("not logged"))
        assertEquals(PlanLog.Counts(1, 0, 0, 0, 0, 0, 1), r.groups[0].counts)
    }

    @Test fun `a booked meal names the slot, the dish and the servings, and no macros`() {
        val r = runMeals(listOf(recipe("Beef Chilli")), listOf(meal("2026-10-12", dinner, 0, 2.0)))
        assertEquals("Dinner · Beef Chilli · 2 servings", day(r, 0).meals[0].title)
        // The recipe's own figures are in the payload and deliberately not on the card: a planned
        // calorie beside a logged one is a coach's target measured against, which is the line this
        // card does not cross.
        val every = PlanLog.lines(r).joinToString(" · ")
        listOf("400", "30", "kcal", "protein").forEach { token ->
            assertFalse("$token reached the card", every.contains(token))
        }
    }

    @Test fun `one serving is one serving, and two dishes at one slot are two rows`() {
        val r = runMeals(
            listOf(recipe("Overnight Oats"), recipe("Protein Shake")),
            listOf(meal("2026-10-12", breakfast, 0), meal("2026-10-12", breakfast, 1, 1.0))
        )
        assertEquals(
            listOf("Breakfast · Overnight Oats · 1 serving", "Breakfast · Protein Shake · 1 serving"),
            day(r, 0).meals.map { it.title }
        )
    }

    @Test fun `half a serving is half a serving, never rounded into a dish nobody booked`() {
        val r = runMeals(listOf(recipe("Beef Chilli")), listOf(meal("2026-10-12", dinner, 0, 1.5)))
        assertEquals("Dinner · Beef Chilli · 1.5 servings", day(r, 0).meals[0].title)
    }

    @Test fun `meals read in the order a day is eaten, not the order they were booked`() {
        val r = runMeals(
            listOf(recipe("Chilli"), recipe("Oats"), recipe("Bar")),
            listOf(meal("2026-10-12", snack, 2), meal("2026-10-12", dinner, 0), meal("2026-10-12", breakfast, 1))
        )
        assertEquals(listOf("Breakfast", "Dinner", "Snack"), day(r, 0).meals.map { it.slotLabel })
    }

    /* ---------------- what the log can be asked ---------------- */

    @Test fun `an itemised slot says what the log holds there, and never that it is the dish`() {
        val r = runMeals(
            listOf(recipe("Beef Chilli")), listOf(meal("2026-10-12", dinner, 0, 2.0)),
            listOf(foodDay("2026-10-12", listOf(
                food("Porridge", breakfast), food("Beef Chilli", dinner), food("Greek yoghurt", dinner)
            )))
        )
        assertEquals("3 foods logged that day", day(r, 0).foodContext)
        assertEquals("Logged at dinner · Beef Chilli · Greek yoghurt", day(r, 0).meals[0].logged)
        // The two facts are printed one above the other. Nothing anywhere claims the logged Beef
        // Chilli is the booked one -- the coach makes that join, from the same two facts Coach has.
        val every = PlanLog.lines(r).joinToString(" · ").lowercase(Locale.US)
        listOf("ate", "as booked", "as planned", "matched", "they had").forEach { claim ->
            assertFalse("\"$claim\" claims a match: $every", every.contains(claim))
        }
    }

    @Test fun `a booked meal with nothing at that slot says so about the slot, not the client`() {
        val r = runMeals(
            listOf(recipe("Chicken & Rice")), listOf(meal("2026-10-12", lunch, 0)),
            listOf(foodDay("2026-10-12", listOf(food("Porridge", breakfast), food("Steak", dinner))))
        )
        assertEquals("Nothing logged at lunch", day(r, 0).meals[0].logged)
        // And the day's own count sits above it, so "nothing at lunch" cannot be read as "they ate
        // nothing".
        assertEquals("2 foods logged that day", day(r, 0).foodContext)
    }

    @Test fun `a day with meals booked and no food at all logged says exactly that`() {
        val r = runMeals(
            listOf(recipe("Beef Chilli")), listOf(meal("2026-10-12", dinner, 0)),
            listOf(foodDay("2026-10-12"))
        )
        assertEquals("No food logged that day", day(r, 0).foodContext)
        assertNull("nothing to say per slot", day(r, 0).meals[0].logged)
    }

    @Test fun `a day opened and left empty logged no food, and is not a slot verdict`() {
        // SHARE-FORMAT: `ft: [0,0,0,0,0]` is a day opened and nothing logged.
        val r = runMeals(
            listOf(recipe("Beef Chilli")), listOf(meal("2026-10-12", dinner, 0)),
            listOf(foodDay("2026-10-12", zeroTotals = true))
        )
        assertEquals("No food logged that day", day(r, 0).foodContext)
        assertNull(day(r, 0).meals[0].logged)
    }

    @Test fun `a client who sent totals and not items gets no slot verdict at all`() {
        // Itemisation is a choice the client makes per send. Calling a booked dinner "nothing
        // logged at dinner" here would contradict that choice with a fact Coach does not have.
        val r = runMeals(
            listOf(recipe("Beef Chilli")), listOf(meal("2026-10-12", dinner, 0)),
            listOf(foodDay("2026-10-12", totals = true))
        )
        assertEquals("Food logged that day, not itemised", day(r, 0).foodContext)
        assertNull(day(r, 0).meals[0].logged)
        assertFalse(PlanLog.lines(r).joinToString(" ").contains("Nothing logged"))
    }

    @Test fun `foods the client tied to no meal are counted, so a quiet slot is not a verdict`() {
        val r = runMeals(
            listOf(recipe("Chicken & Rice")), listOf(meal("2026-10-12", lunch, 0)),
            listOf(foodDay("2026-10-12", listOf(food("Flapjack"), food("Coffee"), food("Steak", dinner))))
        )
        assertEquals("3 foods logged that day · 2 not tied to a meal", day(r, 0).foodContext)
        assertEquals("Nothing logged at lunch", day(r, 0).meals[0].logged)
    }

    @Test fun `a booked meal outside the log the client sent is never a slot verdict`() {
        val r = runMeals(
            listOf(recipe("Beef Chilli")), listOf(meal("2026-10-12", dinner, 0)),
            coverage = "2026-09-01" to "2026-10-05"
        )
        assertEquals("Mon 12 Oct · 1 meal booked · outside the log they sent", day(r, 0).text)
        assertNull(day(r, 0).meals[0].logged)
        assertNull(day(r, 0).foodContext)
        assertEquals("Booked 1 day, 12 Oct · 1 meal booked · no log covering them", r.groups[0].head)
    }

    @Test fun `a client whose log predates the send gets no verdict on any meal of it`() {
        val r = runMeals(
            listOf(recipe("Beef Chilli"), recipe("Oats")),
            listOf(meal("2026-10-12", dinner, 0), meal("2026-10-13", breakfast, 1)),
            coverage = "2026-08-01" to "2026-09-30"
        )
        assertEquals(
            listOf(
                "Mon 12 Oct · 1 meal booked · outside the log they sent",
                "Tue 13 Oct · 1 meal booked · outside the log they sent"
            ),
            r.groups[0].days.map { it.text }
        )
        val every = PlanLog.lines(r).joinToString(" · ")
        assertFalse(every.contains("Nothing logged"))
        assertFalse(every.contains("not logged"))
    }

    @Test fun `a client's own food name is printed as they wrote it, percent sign and all`() {
        // The line discipline is about Coach's sentences, not about the client's data: "2% milk" is
        // what they logged and what Coach prints. Never rewritten, never trimmed to fit a rule
        // about the app's own words.
        val r = runMeals(
            listOf(recipe("Porridge")), listOf(meal("2026-10-12", breakfast, 0)),
            listOf(foodDay("2026-10-12", listOf(food("2% milk", breakfast))))
        )
        assertEquals("Logged at breakfast · 2% milk", day(r, 0).meals[0].logged)
    }

    /* ---------------- meals beside training ---------------- */

    @Test fun `a day that books both says the training verdict and the meal count`() {
        val r = PlanLog.compare(
            clientId = "c",
            sentPlans = listOf(foodPlan(
                listOf(recipe("Beef Chilli"), recipe("Oats")),
                listOf(meal("2026-10-12", dinner, 0, 2.0), meal("2026-10-13", breakfast, 1),
                       meal("2026-10-13", dinner, 0)),
                listOf(workout("Lower A", listOf(ex("Back Squat", "Barbell", listOf(listOf(225, 5)))))),
                listOf("2026-10-12" to 0)
            )),
            days = mapOf("2026-10-12" to foodDay(
                "2026-10-12", listOf(food("Beef Chilli", dinner)),
                sets = listOf(set("Back Squat", "Barbell", 225.0, 5)), name = "Lower A"
            )),
            coverage = "2026-10-01" to "2026-10-31", unit = "lb", today = "2026-10-20"
        )
        assertEquals(
            listOf("Mon 12 Oct · Lower A · logged · 1 meal booked", "Tue 13 Oct · 2 meals booked"),
            r.groups[0].days.map { it.text }
        )
        // "logged 2" under "Booked 5 days" would read as two of five when three of them booked no
        // training at all, so the figure names what it counts.
        assertEquals(
            "Booked 2 days, 12–13 Oct · 3 meals booked · 1 training day, 1 logged",
            r.groups[0].head
        )
        assertEquals(PlanLog.Counts(2, 1, 1, 0, 0, 0, 3), r.groups[0].counts)
    }

    @Test fun `a training-only send reads exactly as it did before meals existed`() {
        val r = run(
            listOf("2026-10-12" to 0),
            listOf(workout("Lower A", listOf(ex("Back Squat", "Barbell", listOf(listOf(225, 5)))))),
            listOf(day("2026-10-12", "Lower A", listOf(set("Back Squat", "Barbell", 225.0, 5))))
        )
        assertEquals("Booked 1 day, 12 Oct · logged 1", r.groups[0].head)
        assertEquals(emptyList<PlanLog.MealBooking>(), day(r, 0).meals)
        assertNull(day(r, 0).foodContext)
        assertNull("and no note about meals under a card with none", r.mealFooter)
    }

    @Test fun `a food plan does not hold up the training the coach never booked`() {
        // `not booked` exists so a session lifted the day after the one it was booked for sits
        // beside that booking. A send with no training booked none for it to sit beside, and
        // listing a client's own sessions under a meal plan would be Coach holding up work nobody
        // set out to book.
        val r = runMeals(
            listOf(recipe("Beef Chilli")),
            listOf(meal("2026-10-12", dinner, 0), meal("2026-10-14", dinner, 0)),
            listOf(day("2026-10-13", "Conditioning", listOf(set("Kettlebell Swing", "Kettlebell", 53.0, 20))))
        )
        assertEquals(
            listOf("Mon 12 Oct · 1 meal booked", "Wed 14 Oct · 1 meal booked"),
            r.groups[0].days.map { it.text }
        )
        assertEquals(0, r.groups[0].counts.other)
        assertFalse(PlanLog.lines(r).joinToString(" ").contains("not booked"))
    }

    @Test fun `a send that books both still shows a session it did not book`() {
        val r = PlanLog.compare(
            clientId = "c",
            sentPlans = listOf(foodPlan(
                listOf(recipe("Beef Chilli")), listOf(meal("2026-10-12", dinner, 0)),
                listOf(workout("Lower A", listOf(ex("Back Squat", "Barbell", listOf(listOf(225, 5)))))),
                listOf("2026-10-12" to 0, "2026-10-14" to 0)
            )),
            days = mapOf("2026-10-13" to day("2026-10-13", "Conditioning",
                listOf(set("Kettlebell Swing", "Kettlebell", 53.0, 20)))),
            coverage = "2026-10-01" to "2026-10-31", unit = "lb", today = "2026-10-20"
        )
        assertEquals("Tue 13 Oct · Conditioning · not booked", r.groups[0].days[1].text)
        assertEquals(1, r.groups[0].counts.other)
    }

    @Test fun `by lift stays about lifts, and a meals-only plan has none`() {
        val r = runMeals(listOf(recipe("Beef Chilli")), listOf(meal("2026-10-12", dinner, 0)))
        assertEquals(emptyList<PlanLog.LiftRows>(), r.byLift)
        assertFalse(PlanLog.lines(r).contains("By lift"))
    }

    /* ---------------- a plan with nothing booked into a day ---------------- */

    @Test fun `a library send -- recipes with nothing booked -- is not a card`() {
        val r = runMeals(listOf(recipe("Beef Chilli"), recipe("Oats")), emptyList())
        assertEquals(emptyList<PlanLog.Group>(), r.groups)
        assertEquals(emptyList<String>(), PlanLog.lines(r))
    }

    @Test fun `a picks-only plan is still nothing to show`() {
        val payload = JSONObject().put("v", 1).put("t", "plan").put("l", "c")
            .put("rf", JSONArray(listOf("wendys-large-chili", "snack-beef-jerky")))
        val r = PlanLog.compare(
            clientId = "c",
            sentPlans = listOf(SentPlan("p", "c", 1, "h", payload.toString())),
            days = mapOf("2026-10-12" to foodDay("2026-10-12", listOf(food("Wendy's chilli", lunch)))),
            coverage = "2026-10-01" to "2026-10-31", unit = "lb", today = "2026-10-20"
        )
        assertEquals(emptyList<PlanLog.Group>(), r.groups)
        assertNull(r.mealFooter)
    }

    @Test fun `the note about what a meal row does not claim is shown once, under a card that has one`() {
        val r = runMeals(
            listOf(recipe("Beef Chilli")), listOf(meal("2026-10-12", dinner, 0)),
            listOf(foodDay("2026-10-12", listOf(food("Beef Chilli", dinner))))
        )
        assertEquals(PlanLog.MEAL_NOTE, r.mealFooter)
        val out = PlanLog.lines(r)
        assertEquals(1, out.count { it == PlanLog.MEAL_NOTE })
        assertEquals("above the permanent footer", PlanLog.MEAL_NOTE, out[out.size - 2])
        assertEquals(PlanLog.FOOTER, out.last())
    }

    /**
     * Every meal state the card has, in one send: a slot the log holds something at, a slot it
     * holds nothing at, a day with no food at all, a day whose client sent totals and not items, a
     * day outside the window they sent, an entry tied to no meal, and a meal day beside a training
     * one.
     *
     * The food names here are plain on purpose. A client's own "2% milk" is printed as they wrote
     * it and would fail the forbidden list -- the discipline is about the sentences Coach writes,
     * not about the client's data, and the test above pins the passthrough.
     */
    private fun mealFixture(): PlanLog.Result = PlanLog.compare(
        clientId = "c",
        sentPlans = listOf(foodPlan(
            listOf(recipe("Beef Chilli"), recipe("Overnight Oats"), recipe("Chicken & Rice")),
            listOf(
                meal("2026-10-12", dinner, 0, 2.0), meal("2026-10-12", breakfast, 1),
                meal("2026-10-13", lunch, 2), meal("2026-10-14", dinner, 0),
                meal("2026-10-15", lunch, 2), meal("2026-10-19", dinner, 0)
            ),
            listOf(workout("Lower A", listOf(ex("Back Squat", "Barbell", listOf(listOf(225, 5)))))),
            listOf("2026-10-12" to 0)
        )),
        days = listOf(
            foodDay("2026-10-12", listOf(food("Beef Chilli", dinner), food("Greek yoghurt", dinner),
                                         food("Porridge", breakfast)),
                    sets = listOf(set("Back Squat", "Barbell", 225.0, 5)), name = "Lower A"),
            foodDay("2026-10-13", listOf(food("Steak", dinner), food("Flapjack"))),
            foodDay("2026-10-14"),
            foodDay("2026-10-15", totals = true)
        ).associateBy { it.dayKey },
        coverage = "2026-10-01" to "2026-10-16", unit = "lb", today = "2026-10-20"
    )

    /* ---------------- the line discipline ---------------- */

    /* The spirit of PerLimbSetsTest's own wording checks and Coach web's twin: the card counts,
     * and never grades. */
    private val forbidden = listOf(
        "should", "fix", "warning", "target", "too ", "concern", "missed", "skipped",
        "failed", "poor", "behind", "compliance", "adherence", "streak", "%"
    )

    @Test fun `nothing in this card tells a coach what to do`() {
        // Every state the card has: a logged day, a day with nothing logged, a day outside the
        // window the client sent, a day logged and not booked; and a matched lift, a substituted
        // one, one short on a side, one short on sets, one not logged at all and one nobody asked
        // for. The fixture exercises all of them.
        val training = PlanLog.lines(fromFixture())
        val meals = PlanLog.lines(mealFixture())
        assertTrue("the meal fixture should exercise every meal state", meals.size > 15)
        val every = (training + meals).joinToString(" · ").lowercase(Locale.US)
        assertTrue("the fixture should exercise the whole card", every.length > 200)
        forbidden.forEach { word ->
            assertFalse("\"$word\" reached a screen: $every", every.contains(word))
        }
    }

    @Test fun `no meal sentence claims a client ate anything`() {
        // The whole reason the booked and the logged sides of a meal are two separate statements.
        // Coach can see a dish it booked and a list of foods stamped with a slot; it cannot see
        // that they are the same dinner, and a wrong claim here tells a coach their client ate
        // something they did not.
        val every = PlanLog.lines(mealFixture()).joinToString(" · ").lowercase(Locale.US)
        listOf("ate", "eaten", "as booked", "as planned", "matched", "they had",
               "on plan", "off plan", "followed", "complied").forEach { claim ->
            assertFalse("\"$claim\" claims a meal was eaten: $every", every.contains(claim))
        }
        // And the one sentence that says what the rows do not claim is there.
        assertTrue(every.contains(PlanLog.MEAL_NOTE.lowercase(Locale.US)))
    }

    private val banned = Regex(
        "score|percent|rate|ratio|average|total|streak|grade|adherence|compliance",
        RegexOption.IGNORE_CASE
    )

    @Test fun `nothing here aggregates a client into a score`() {
        val result = fromFixture()

        // Counts hang off a day or a group of days, and there is nothing at the top of the result
        // to aggregate: no roster figure, no all-time total, no trend across weeks.
        assertEquals(
            listOf("byLift", "footer", "groups", "mealFooter"),
            PlanLog.Result::class.java.declaredFields.map { it.name }.filterNot { it.startsWith("$") }.sorted()
        )
        assertEquals(
            "a group counts the days and the meals it booked, and nothing else",
            listOf("booked", "logged", "meals", "notLogged", "other", "outside", "training"),
            PlanLog.Counts::class.java.declaredFields.map { it.name }.filterNot { it.startsWith("$") }.sorted()
        )

        // And nothing anywhere in the tree is a score, a rate or a percentage.
        walk(result, "result")

        // The public surface offers nothing per client, per block or per roster.
        assertEquals(
            emptyList<String>(),
            PlanLog::class.java.methods.map { it.name }.filter { banned.containsMatchIn(it) }
        )

        // Meals are counted the same way and scored no more than training is: a number of meals
        // booked, and no figure beside it claiming how many of them were eaten, because there is no
        // such figure.
        val meals = mealFixture()
        assertEquals(6, meals.groups[0].counts.meals)
        walk(meals, "meals")
    }

    /**
     * Every field of every value the card produces, by reflection -- names checked for a grade,
     * strings checked for a percentage. Computed text (a [PlanLog.SetRow]'s `text`) is checked
     * through its getter, because a sentence that is not stored is still a sentence on screen.
     */
    private fun walk(node: Any?, path: String) {
        when (node) {
            null -> return
            is String -> assertFalse("$path carries a percentage", node.contains("%"))
            is List<*> -> node.forEachIndexed { i, v -> walk(v, "$path[$i]") }
            is Number, is Boolean, is Enum<*> -> return
            else -> {
                node.javaClass.declaredFields
                    .filterNot { it.isSynthetic || it.name.startsWith("$") }
                    .forEach { field ->
                        assertFalse("$path.${field.name} reads as a grade", banned.containsMatchIn(field.name))
                        field.isAccessible = true
                        walk(field.get(node), "$path.${field.name}")
                    }
                node.javaClass.declaredMethods
                    .filter { it.parameterCount == 0 && it.name.startsWith("get") && it.returnType == String::class.java }
                    .forEach { getter ->
                        assertFalse("$path.${getter.name} reads as a grade", banned.containsMatchIn(getter.name))
                        walk(getter.invoke(node), "$path.${getter.name}")
                    }
            }
        }
    }

    @Test fun `the roster stays a roster, and nothing in this feature reaches a roster row`() {
        listOf("ui/RosterScreen.kt", "data/Roster.kt").forEach { name ->
            val source = java.io.File("src/main/java/com/dugcanlift/coach/$name").readText()
            assertTrue("$name moved; re-point this test", source.isNotEmpty())
            listOf("PlanLog", "SentPlan", "sentPlans", "Booked", "booked").forEach { token ->
                assertFalse(
                    "$name mentions $token -- the card counts per client and never across the roster",
                    source.contains(token)
                )
            }
        }
    }

    @Test fun `the card draws both halves of a meal row, and the context above them`() {
        // The same guard Coach web puts on its own view, for the half of the card where getting it
        // wrong is worse: a view that drew the booked dish and dropped the line saying what the log
        // holds at that meal would read as a claim the dish was eaten, which is the one thing this
        // feature refuses to say.
        val source = java.io.File("src/main/java/com/dugcanlift/coach/ui/ClientScreen.kt").readText()
        val start = source.indexOf("private fun BookedMeals(")
        assertTrue("BookedMeals moved; re-point this test", start > 0)
        val body = source.substring(start, source.indexOf("\nprivate fun ", start + 1).takeIf { it > start } ?: source.length)
        assertTrue("the day's own food count sits above the rows", body.contains("day.foodContext"))
        assertTrue("what was booked", body.contains("meal.title"))
        assertTrue("what the log holds at that meal", body.contains("meal.logged"))
    }

    @Test fun `the card carries the note about what a meal row does not claim`() {
        val source = java.io.File("src/main/java/com/dugcanlift/coach/ui/ClientScreen.kt").readText()
        assertTrue("MEAL_NOTE must reach the screen", source.contains("booked.mealFooter"))
        assertTrue("and so must the permanent one", source.contains("booked.footer"))
        assertTrue("and the meal rows must be drawn", source.contains("BookedMeals(day)"))
    }

    @Test fun `a lift with nothing logged prints no asked rows on the day view`() {
        val r = run(
            listOf("2026-10-12" to 0),
            listOf(workout("Lower A", listOf(
                ex("Back Squat", "Barbell", listOf(listOf(225, 5))),
                ex("Overhead Press", "Barbell", listOf(listOf(95, 8)))
            ))),
            listOf(day("2026-10-12", "Lower A", listOf(set("Back Squat", "Barbell", 225.0, 5))))
        )
        val press = day(r, 0).exercises[1]
        assertEquals("Overhead Press (Barbell) · not logged", press.title)
        assertNull("reciting what someone did not do is not a fact", press.asked)
        assertNull(press.logged)
    }
}
