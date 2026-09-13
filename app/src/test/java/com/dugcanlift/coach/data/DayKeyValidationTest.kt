package com.dugcanlift.coach.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * Round 6 [C-2]: a day key ISO_LOCAL_DATE rejects must be caught where it enters the app, so it
 * can never reach [Client.daysSinceLastLoggedDay] or [Stats.weeklyBuckets] -- both of which run
 * inside composition, where a throw is an unrecoverable crash loop (Connect, the only screen with
 * "Restore from Backup", is reachable only through the roster).
 */
class DayKeyValidationTest {
    private fun dayJson(key: String) = JSONObject()
        .put("dayKey", key)
        .put("sessionName", JSONObject.NULL).put("focus", JSONObject.NULL)
        .put("bodyweightLb", 180.0).put("steps", JSONObject.NULL)
        .put("foodCalories", JSONObject.NULL).put("foodProteinG", JSONObject.NULL)
        .put("foodFatG", JSONObject.NULL).put("foodCarbsG", JSONObject.NULL).put("foodFiberG", JSONObject.NULL)

    @Test fun `a day key ISO_LOCAL_DATE rejects never loads as a valid day`() {
        // "2026-9-3" is the review's own reproduction: unpadded, so ISO_LOCAL_DATE rejects it
        // while JSONObject parses the file around it perfectly happily.
        for (bad in listOf("2026-9-3", "", "yesterday", "2026-13-01", "2026-09-3")) {
            try {
                TrainingDay.fromJson(dayJson(bad))
                fail("expected \"$bad\" to be rejected as a day key")
            } catch (e: Exception) {
                // expected -- rejected at the boundary, not stored and detonated later
            }
        }
    }

    @Test fun `a well-formed day key still reads normally`() {
        assertEquals("2026-09-03", TrainingDay.fromJson(dayJson("2026-09-03")).dayKey)
    }
}
