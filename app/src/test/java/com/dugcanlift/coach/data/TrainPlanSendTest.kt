package com.dugcanlift.coach.data

import com.dugcanlift.kit.CompactEncoding
import com.dugcanlift.kit.PlanDecodeResult
import com.dugcanlift.kit.PlanLinkCodec
import com.dugcanlift.kit.ShareSide
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale

/**
 * What Train's Send actually sends.
 *
 * Every assertion that concerns the wire reads the link back with the kit's
 * [PlanLinkCodec] -- the decoder LIFT Android ships -- rather than with a
 * second copy of the encoder's rules: checking the JSON by hand would only
 * prove this file agrees with itself, and what is on trial is whether a client
 * can read what a coach sends.
 */
class TrainPlanSendTest {

    private var original: Locale = Locale.getDefault()

    /** The size note formats for the coach's locale; pin one so the text is comparable. */
    @Before fun fixLocale() { original = Locale.getDefault(); Locale.setDefault(Locale.US) }
    @After fun restoreLocale() { Locale.setDefault(original) }

    private val week = PlanWeek("2026-09-14")

    private fun kg(lb: Double) = lb / LB_PER_KG

    private val lower = Routine(
        id = "w1", name = "Lower A", exercises = listOf(
            RoutineExercise(
                name = "Back Squat", equipment = "Barbell", note = "Belt on the last set.",
                sets = listOf(
                    PrescribedSet(targetWeightKg = 100.0, targetReps = 5),
                    PrescribedSet(targetWeightKg = 100.0, targetReps = 5)
                )
            ),
            RoutineExercise(
                name = "Bulgarian Split Squat", equipment = "Dumbbell", eachSide = true,
                sets = listOf(
                    PrescribedSet(targetWeightKg = kg(40.0), targetReps = 8),
                    PrescribedSet(targetWeightKg = kg(40.0), targetReps = 8, side = SetSide.LEFT)
                )
            ),
            RoutineExercise(
                name = "Sled Push", sets = listOf(
                    PrescribedSet(targetDurationSec = 600, targetDistanceMeters = 1600.0, side = SetSide.RIGHT)
                )
            )
        )
    )
    private val upper = Routine(id = "w2", name = "Upper A", exercises = listOf(
        RoutineExercise(name = "Bench Press", equipment = "Barbell",
                        sets = listOf(PrescribedSet(targetWeightKg = 80.0, targetReps = 5)))
    ))

    private fun session(day: String, routineId: String, client: String = "c1", id: String = day + routineId) =
        ScheduledSession(id = id, clientId = client, dayKey = day, routineId = routineId)

    private fun build(
        sessions: List<ScheduledSession>,
        routines: List<Routine> = listOf(lower, upper),
        clientId: String? = "c1",
        week: PlanWeek = this.week,
        coachName: String = "Doug"
    ) = TrainPlanSend.build(clientId, "Jordan", week, sessions, routines, coachName)

    private fun payload(send: TrainPlanSend, lifter: String = "c1") =
        (PlanLinkCodec.decode(send.link.substringAfter('#'), lifter) as PlanDecodeResult.Success).payload

    /** The raw JSON a link carries, for asking what was written rather than what was read. */
    private fun rawJson(link: String): JSONObject {
        val fragment = link.substringAfter('#')
        check(fragment.startsWith("1z")) { "expected a deflated fragment, got $fragment" }
        return JSONObject(
            String(
                CompactEncoding.inflateRaw(CompactEncoding.base64UrlDecode(fragment.substring(2))),
                Charsets.UTF_8
            )
        )
    }

    /* ---------------- the week a coach sees is the week that goes ---------------- */

    @Test fun `it sends this client's bookings in this week, and nobody else's`() {
        val send = build(listOf(
            session("2026-09-14", "w1"),
            session("2026-09-17", "w2"),
            session("2026-09-20", "w1"),
            // Another client's day in the same week.
            session("2026-09-15", "w1", client = "c2"),
            // This client, the week before and the week after.
            session("2026-09-13", "w1"),
            session("2026-09-21", "w2")
        ))

        assertEquals(listOf("2026-09-14", "2026-09-17", "2026-09-20"), send.sessions.map { it.dayKey })
        val plan = payload(send)
        assertEquals(listOf("2026-09-14", "2026-09-17", "2026-09-20"), plan.sessions.map { it.date })
        assertEquals("Doug", plan.coachName)
        // `x` indexes `w`, so the day names the workout the coach booked on it.
        assertEquals(listOf("Lower A", "Upper A", "Lower A"),
                     plan.sessions.map { plan.workouts[it.workoutIndex].name })
        assertEquals("each template inlined once", listOf("Lower A", "Upper A"), plan.workouts.map { it.name })
    }

    @Test fun `it is addressed to the client it was built for`() {
        val send = build(listOf(session("2026-09-14", "w1")))
        assertTrue(PlanLinkCodec.decode(send.link.substringAfter('#'), "c1") is PlanDecodeResult.Success)
        assertEquals(PlanDecodeResult.NotAddressedToYou,
                     PlanLinkCodec.decode(send.link.substringAfter('#'), "c2"))
    }

    @Test fun `the link is LIFT's address with the plan in the fragment`() {
        val send = build(listOf(session("2026-09-14", "w1")))
        assertTrue(send.link, send.link.startsWith("https://www.dugcanlift.com/lift/#1"))
        assertEquals("nothing before the # carries the plan", 1, send.link.count { it == '#' })
    }

    /* ---------------- training only ---------------- */

    @Test fun `a training send carries w and k and no r or m at all`() {
        val raw = rawJson(build(listOf(session("2026-09-14", "w1"))).link)
        assertEquals(setOf("v", "t", "l", "n", "w", "k"), raw.keys().asSequence().toSet())
        assertEquals(1, raw.getInt("v"))
        assertEquals("plan", raw.getString("t"))
        assertEquals("c1", raw.getString("l"))
    }

    /* ---------------- pounds on the wire ---------------- */

    @Test fun `a prescribed weight travels as pounds, not as the kilograms Coach stores`() {
        val plan = payload(build(listOf(session("2026-09-14", "w1"))))
        val squat = plan.workouts.single { it.name == "Lower A" }.exercises.first()

        // 100 kg is 220.46 lb. The number Coach stores must never reach a
        // client's phone unconverted: nothing fails, it is simply 2.2x wrong.
        assertEquals(220.46226218, squat.sets[0].weightLb!!, 1e-6)
        assertEquals(5, squat.sets[0].reps)
        assertTrue("a kilogram value must not ship as itself", squat.sets.none { it.weightLb == 100.0 })

        // And the raw tuple, so the check does not depend on the decoder either.
        val raw = rawJson(build(listOf(session("2026-09-14", "w1"))).link)
        val tuple = raw.getJSONArray("w").getJSONObject(0)
            .getJSONArray("e").getJSONObject(0).getJSONArray("s").getJSONArray(0)
        assertEquals(220.46226218, tuple.getDouble(0), 1e-6)

        // A pound value the coach typed comes back out as the pounds it was.
        assertEquals(40.0, plan.workouts[0].exercises[1].sets[0].weightLb!!, 1e-9)
    }

    /* ---------------- per-side prescriptions survive the send ---------------- */

    @Test fun `each side and a named side reach the client`() {
        val plan = payload(build(listOf(session("2026-09-14", "w1"))))
        val exercises = plan.workouts.single { it.name == "Lower A" }.exercises

        assertFalse(exercises[0].eachSide)
        assertNull(exercises[0].sets[0].side)

        assertTrue("b: 1 rides with the exercise", exercises[1].eachSide)
        assertEquals(listOf(null, ShareSide.LEFT), exercises[1].sets.map { it.side })

        // A conditioning piece keeps its leading nulls: trimming them would
        // slide the distance into the weight slot.
        val carry = exercises[2].sets.single()
        assertEquals(ShareSide.RIGHT, carry.side)
        assertEquals(600, carry.durationSec)
        assertEquals(1600.0, carry.distanceMeters!!, 1e-9)
        assertNull(carry.weightLb)

        assertEquals("Belt on the last set.", exercises[0].note)
        assertEquals("Barbell", exercises[0].equipment)
    }

    /* ---------------- a booking whose workout is gone ---------------- */

    @Test fun `a removed workout is not sent, is counted, and does not shift the indexes`() {
        val send = build(
            listOf(session("2026-09-14", "gone"), session("2026-09-15", "w2")),
            routines = listOf(lower, upper)
        )
        assertEquals(1, send.removedBookings)
        assertEquals(listOf("2026-09-15"), send.sessions.map { it.dayKey })

        val plan = payload(send)
        assertEquals("only the workout actually booked is inlined", listOf("Upper A"), plan.workouts.map { it.name })
        assertEquals(1, plan.sessions.size)
        assertEquals("Upper A", plan.workouts[plan.sessions.single().workoutIndex].name)
    }

    @Test fun `a week of nothing but removed workouts is not sendable`() {
        val send = build(listOf(session("2026-09-14", "gone")))
        assertFalse(send.isSendable)
        assertEquals("", send.link)
        assertEquals(1, send.removedBookings)
    }

    /* ---------------- empty and edge states ---------------- */

    @Test fun `nothing booked this week is not sendable and says so`() {
        val send = build(listOf(session("2026-09-28", "w1")))
        assertFalse(send.isSendable)
        assertEquals("Nothing booked for this client this week yet.", send.note)
        assertEquals(0, send.removedBookings)
    }

    @Test fun `no client picked sends nothing`() {
        assertFalse(build(listOf(session("2026-09-14", "w1")), clientId = null).isSendable)
        assertFalse(build(listOf(session("2026-09-14", "w1")), clientId = "").isSendable)
    }

    @Test fun `a coach who has not given their name is Your coach, never an empty n`() {
        assertEquals("Your coach", TrainPlanSend.coachName(null))
        assertEquals("Your coach", TrainPlanSend.coachName(""))
        assertEquals("Your coach", TrainPlanSend.coachName("  "))
        assertEquals("Doug", TrainPlanSend.coachName("Doug"))
        assertEquals("Your coach",
                     payload(build(listOf(session("2026-09-14", "w1")), coachName = TrainPlanSend.coachName(""))).coachName)
    }

    /* ---------------- what the coach reads ---------------- */

    @Test fun `the note counts the sessions and the kilobytes`() {
        val one = build(listOf(session("2026-09-14", "w1")))
        assertEquals("1 session", one.contents)
        assertTrue(one.note, one.note.startsWith("1 session · about 0."))
        assertTrue(one.note, one.note.endsWith("KB of email."))

        val two = build(listOf(session("2026-09-14", "w1"), session("2026-09-16", "w2")))
        assertEquals("2 sessions", two.contents)
    }

    @Test fun `a plan long enough to break in the mail says so`() {
        // Names and notes that do not compress away, because the link is
        // measured after DEFLATE: a repetitive routine of the same size would
        // ride well inside the ceiling, which is the point of measuring the
        // link rather than the payload.
        val random = java.util.Random(1)
        fun noise(length: Int) = (1..length).map { ('a' + random.nextInt(26)) }.joinToString("")
        val fat = Routine(id = "w1", name = "Everything", exercises = (1..400).map {
            RoutineExercise(
                name = noise(40), equipment = noise(20), note = noise(60),
                sets = listOf(PrescribedSet(targetWeightKg = it.toDouble(), targetReps = it))
            )
        })
        val send = build(listOf(session("2026-09-14", "w1")), routines = listOf(fat))
        assertTrue("fixture should exceed the ceiling: ${send.link.length}",
                   send.link.length > PlanEnvelope.RISKY_LINK_LENGTH)
        assertTrue(send.note, send.note.contains("some mail apps will break it — send fewer days"))
    }

    @Test fun `the message names what the link contains`() {
        val send = build(listOf(session("2026-09-14", "w1"), session("2026-09-16", "w2")))
        assertEquals("Here's your training — 2 sessions.\n\n${send.link}", send.message)
    }

    /* ---------------- the week itself ---------------- */

    @Test fun `a week is seven days from its start, and moves a week at a time`() {
        assertEquals(
            listOf("2026-09-14", "2026-09-15", "2026-09-16", "2026-09-17",
                   "2026-09-18", "2026-09-19", "2026-09-20"),
            week.days
        )
        assertEquals("2026-09-21", week.advanced(1).startDayKey)
        assertEquals("2026-09-07", week.advanced(-1).startDayKey)
        assertEquals("2026-09-14", week.advanced(1).advanced(-1).startDayKey)
    }

    @Test fun `day arithmetic crosses a DST fall-back without repeating a day`() {
        // 1 November 2026 is the US fall-back; adding seconds would give two
        // 1 Novembers and no 7th day.
        assertEquals(
            listOf("2026-10-30", "2026-10-31", "2026-11-01", "2026-11-02",
                   "2026-11-03", "2026-11-04", "2026-11-05"),
            PlanWeek("2026-10-30").days
        )
        assertEquals(7, PlanWeek("2026-10-30").days.distinct().size)
    }

    @Test fun `the label says This week only when today is in it`() {
        assertEquals("This week", PlanWeek("2026-09-14").label(today = "2026-09-14"))
        assertEquals("This week", PlanWeek("2026-09-14").label(today = "2026-09-20"))
        assertEquals("2026-09-14 – 2026-09-20", PlanWeek("2026-09-14").label(today = "2026-09-21"))
        assertEquals("2026-09-14 – 2026-09-20", PlanWeek("2026-09-14").label(today = "2026-09-13"))
    }

    @Test fun `a day key that is not a day does not take the screen down`() {
        assertEquals(emptyList<String>(), PlanWeek("not-a-day").days)
        assertEquals("not-a-day", PlanWeek("not-a-day").advanced(1).startDayKey)
        assertEquals("not-a-day", PlanWeek("not-a-day").label(today = "2026-09-14"))
        assertFalse(build(listOf(session("2026-09-14", "w1")), week = PlanWeek("not-a-day")).isSendable)
    }
}
