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
                counts.getInt("booked"), counts.getInt("logged"), counts.getInt("notLogged"),
                counts.getInt("outside"), counts.getInt("other")
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
        assertEquals(PlanLog.Counts(1, 1, 0, 0, 0), r.groups[0].counts)
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

    @Test fun `a plan sent with no training at all books nothing`() {
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
        assertEquals("meals are not compared", emptyList<PlanLog.Group>(), r.groups)
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
        val every = PlanLog.lines(fromFixture()).joinToString(" · ").lowercase(Locale.US)
        assertTrue("the fixture should exercise the whole card", every.length > 200)
        forbidden.forEach { word ->
            assertFalse("\"$word\" reached a screen: $every", every.contains(word))
        }
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
            listOf("byLift", "footer", "groups"),
            PlanLog.Result::class.java.declaredFields.map { it.name }.filterNot { it.startsWith("$") }.sorted()
        )
        assertEquals(
            "a group counts days and nothing else",
            listOf("booked", "logged", "notLogged", "other", "outside"),
            PlanLog.Counts::class.java.declaredFields.map { it.name }.filterNot { it.startsWith("$") }.sorted()
        )

        // And nothing anywhere in the tree is a score, a rate or a percentage.
        walk(result, "result")

        // The public surface offers nothing per client, per block or per roster.
        assertEquals(
            emptyList<String>(),
            PlanLog::class.java.methods.map { it.name }.filter { banned.containsMatchIn(it) }
        )
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
