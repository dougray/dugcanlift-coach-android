package com.dugcanlift.coach.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Train half of the library stopped being opaque cargo when this arrived.
 * These pin the two things that can silently corrupt a coach's prescription.
 */
class TrainTest {

    // MARK: - Kilograms, and the 2.2x

    @Test fun `an iOS routine's kilograms are read as kilograms`() {
        val set = prescribedSetFromJson(JSONObject("""{ "targetWeightKg": 100, "targetReps": 5 }"""))
        assertEquals(100.0, set.targetWeightKg!!, 0.001)
        assertEquals(5, set.targetReps)
    }

    @Test fun `a web routine's pounds are converted, not copied`() {
        // The failure this guards is silent: reading weightLb into targetWeightKg
        // turns a 225 lb prescription into 225 kg -- 496 lb on the client's phone.
        val set = prescribedSetFromJson(JSONObject("""{ "weightLb": 225, "reps": 5 }"""))
        assertEquals(225 / LB_PER_KG, set.targetWeightKg!!, 0.001)
        assertEquals(225.0, set.targetWeightLb!!, 0.001)
        assertEquals(5, set.targetReps)
    }

    @Test fun `a web set survives a round trip at the weight it was written`() {
        val out = prescribedSetFromJson(JSONObject("""{ "weightLb": 225 }""")).toJson()
        // Written in this app's spelling -- kilograms -- but the same load.
        assertEquals(225 / LB_PER_KG, out.getDouble("targetWeightKg"), 0.001)
        assertEquals(225.0, out.getDouble("targetWeightKg") * LB_PER_KG, 0.001)
    }

    @Test fun `the web's distance spelling is accepted`() {
        // distanceM, not targetDistanceMeters.
        val set = prescribedSetFromJson(JSONObject("""{ "distanceM": 400, "durationSec": 90 }"""))
        assertEquals(400.0, set.targetDistanceMeters!!, 0.001)
        assertEquals(90, set.targetDurationSec)
    }

    @Test fun `a set with no weight has no weight, not a zero`() {
        val set = prescribedSetFromJson(JSONObject("""{ "targetReps": 10 }"""))
        assertNull("a bodyweight set is not a 0 kg set", set.targetWeightKg)
        assertNull(set.targetWeightLb)
    }

    // MARK: - Preservation, as Cook does it

    @Test fun `a field a newer Coach iOS adds to a set survives`() {
        val out = prescribedSetFromJson(
            JSONObject("""{ "targetReps": 5, "targetTempo": "3-1-1" }""")
        ).toJson()
        assertEquals("3-1-1", out.getString("targetTempo"))
    }

    @Test fun `a routine round-trips with its exercises and unknown keys`() {
        val json = JSONObject("""
            { "id": "t1", "name": "Push A", "colour": "red",
              "exercises": [ { "name": "Bench", "equipment": "Barbell", "note": "slow",
                               "sets": [ { "targetWeightKg": 60, "targetReps": 8 } ] } ] }
        """.trimIndent())
        val out = routineFromJson(json).toJson()
        assertEquals("Push A", out.getString("name"))
        assertEquals("red", out.getString("colour"))
        val ex = out.getJSONArray("exercises").getJSONObject(0)
        assertEquals("Bench", ex.getString("name"))
        assertEquals("slow", ex.getString("note"))
        assertEquals(60.0, ex.getJSONArray("sets").getJSONObject(0).getDouble("targetWeightKg"), 0.001)
    }

    // MARK: - Sessions

    @Test fun `an iOS session decodes`() {
        val s = scheduledSessionFromJson(JSONObject("""
            { "id": "s1", "clientID": "c1", "dayKey": "2026-09-14", "routineID": "t1" }
        """.trimIndent()))!!
        assertEquals("c1", s.clientId)
        assertEquals("t1", s.routineId)
    }

    @Test fun `a web session spells every field differently and still decodes`() {
        val s = scheduledSessionFromJson(JSONObject("""
            { "id": "s1", "clientId": "c1", "date": "2026-09-14", "workoutId": "t1" }
        """.trimIndent()))!!
        assertEquals("c1", s.clientId)
        assertEquals("2026-09-14", s.dayKey)
        assertEquals("t1", s.routineId)
    }

    @Test fun `a session naming no client is not a session`() {
        // Coach iOS's BackupSession has clientID non-optional. A row without one
        // cannot be shown in anyone's calendar, and guessing an owner would put
        // someone else's training on a client's day.
        assertNull(scheduledSessionFromJson(JSONObject("""{ "dayKey": "2026-09-14", "routineID": "t1" }""")))
    }

    @Test fun `sessions for a client come back oldest first`() {
        val all = listOf(
            ScheduledSession(clientId = "c1", dayKey = "2026-09-16", routineId = "t1"),
            ScheduledSession(clientId = "c2", dayKey = "2026-09-14", routineId = "t1"),
            ScheduledSession(clientId = "c1", dayKey = "2026-09-14", routineId = "t1")
        )
        assertEquals(listOf("2026-09-14", "2026-09-16"), all.forClient("c1").map { it.dayKey })
    }

    @Test fun `a routine counts its sets across every exercise`() {
        val r = Routine(name = "Push", exercises = listOf(
            RoutineExercise(name = "Bench", sets = listOf(PrescribedSet(), PrescribedSet())),
            RoutineExercise(name = "Press", sets = listOf(PrescribedSet()))
        ))
        assertEquals(3, r.setCount)
        assertTrue(r.exercises.first().displayName == "Bench")
    }
}
