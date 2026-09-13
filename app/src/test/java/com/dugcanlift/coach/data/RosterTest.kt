package com.dugcanlift.coach.data
import org.junit.Assert.*
import org.junit.Test

class RosterTest {
    private fun day(key: String, sets: List<ExerciseSet> = emptyList()) =
        TrainingDay(key, null, null, null, null, null, null, null, null, null, sets, emptyList())
    private fun loggedDay(key: String) = day(key, listOf(ExerciseSet("Back Squat", "Barbell", 225.0, 5, null, null, null, false)))
    private fun client(id: String, name: String, days: List<TrainingDay> = emptyList()) = Client(id, name, "lb", null, 0, null, days)

    @Test fun `a client who has never logged sorts first and is labelled Nothing logged yet`() {
        val never = client("a", "Never")
        val active = client("b", "Active", listOf(loggedDay("2026-09-13")))
        val state = Roster.buildViewState(listOf(active, never), today = "2026-09-13")
        assertEquals(listOf("Never", "Active"), state.rows.map { it.client.name })
        assertEquals("Nothing logged yet", state.rows[0].label)
    }

    @Test fun `quietest first orders by days since last logged descending`() {
        val a = client("a", "A", listOf(loggedDay("2026-09-10"))) // 3 days silent
        val b = client("b", "B", listOf(loggedDay("2026-09-06"))) // 7 days silent
        val c = client("c", "C", listOf(loggedDay("2026-09-13"))) // 0 days silent
        val state = Roster.buildViewState(listOf(a, b, c), today = "2026-09-13")
        assertEquals(listOf("B", "A", "C"), state.rows.map { it.client.name })
    }

    @Test fun `label is Logged today at day zero`() {
        val c = client("a", "A", listOf(loggedDay("2026-09-13")))
        assertEquals("Logged today", Roster.buildViewState(listOf(c), today = "2026-09-13").rows[0].label)
    }

    @Test fun `label is Logged yesterday at day one`() {
        val c = client("a", "A", listOf(loggedDay("2026-09-12")))
        assertEquals("Logged yesterday", Roster.buildViewState(listOf(c), today = "2026-09-13").rows[0].label)
    }

    @Test fun `label is Logged N days ago for two or more days`() {
        val c = client("a", "A", listOf(loggedDay("2026-09-10")))
        assertEquals("Logged 3 days ago", Roster.buildViewState(listOf(c), today = "2026-09-13").rows[0].label)
    }

    @Test fun `the silence banner names clients at 7 or more days but not at 6, quietest first`() {
        val six = client("a", "Six", listOf(loggedDay("2026-09-07")))     // 6 days
        val seven = client("b", "Seven", listOf(loggedDay("2026-09-06"))) // 7 days
        val eight = client("c", "Eight", listOf(loggedDay("2026-09-05"))) // 8 days
        val state = Roster.buildViewState(listOf(six, seven, eight), today = "2026-09-13")
        assertEquals(listOf("Eight", "Seven"), state.silentNames)
    }

    @Test fun `a client who has never logged counts toward the silence banner too`() {
        val never = client("a", "Never")
        val state = Roster.buildViewState(listOf(never), today = "2026-09-13")
        assertEquals(listOf("Never"), state.silentNames)
    }

    @Test fun `an empty client list yields no rows and no silence banner`() {
        val state = Roster.buildViewState(emptyList(), today = "2026-09-13")
        assertTrue(state.rows.isEmpty()); assertTrue(state.silentNames.isEmpty())
    }

    @Test fun `a future-dated last log renders Logged today, never a negative count`() {
        // A day two days ahead of "today" (clock skew, or a client on a different device clock).
        val c = client("a", "A", listOf(loggedDay("2026-09-15")))
        val row = Roster.buildViewState(listOf(c), today = "2026-09-13").rows.single()
        assertEquals("Logged today", row.label)
        assertFalse(row.label.contains("-"))
    }

    @Test fun `two clients with the same silence count keep their original relative order, not alphabetical`() {
        val alpha = client("a", "Alpha", listOf(loggedDay("2026-09-06"))) // 7 days silent
        val bravo = client("b", "Bravo", listOf(loggedDay("2026-09-06"))) // also 7 days silent
        assertEquals(
            listOf("Alpha", "Bravo"),
            Roster.buildViewState(listOf(alpha, bravo), today = "2026-09-13").rows.map { it.client.name }
        )
        // Reversed input stays reversed -- proves this is input-order stability, not a coincidental match.
        assertEquals(
            listOf("Bravo", "Alpha"),
            Roster.buildViewState(listOf(bravo, alpha), today = "2026-09-13").rows.map { it.client.name }
        )
    }

    // --- Round 6 [C-2]: a client already on disk carrying a day key ISO_LOCAL_DATE rejects must
    // not be able to throw out of buildViewState. It runs inside RosterScreen's composition, and
    // Connect -- the only screen with "Restore from Backup" -- is reachable only through the
    // roster, so a throw here is unrecoverable without clearing app data.
    @Test fun `one client with an unparseable day key does not take the whole roster down`() {
        val bad = client("bad", "Bad", listOf(loggedDay("2026-9-3")))
        val good = client("good", "Good", listOf(loggedDay("2026-09-13")))
        val state = Roster.buildViewState(listOf(bad, good), today = "2026-09-13")
        assertEquals(setOf("Bad", "Good"), state.rows.map { it.client.name }.toSet())
        assertNull(state.rows.single { it.client.id == "bad" }.daysSinceLastLogged)
        assertEquals(0, state.rows.single { it.client.id == "good" }.daysSinceLastLogged)
    }

    // "2026-9-3" sorts AFTER "2026-09-06" as a string, so the unparseable key is the one the old
    // maxOfOrNull picked -- the newest key the app can actually reason about is the right answer.
    @Test fun `an unparseable day key is ignored in favour of the newest parseable logged day`() {
        val c = client("a", "A", listOf(loggedDay("2026-9-3"), loggedDay("2026-09-06")))
        assertEquals(7, c.daysSinceLastLoggedDay("2026-09-13"))
    }
}
