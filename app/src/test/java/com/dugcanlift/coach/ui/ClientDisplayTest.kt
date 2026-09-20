package com.dugcanlift.coach.ui

import com.dugcanlift.coach.data.ExerciseSet
import com.dugcanlift.coach.data.LiftProgression
import com.dugcanlift.coach.data.SetSide
import com.dugcanlift.coach.data.SideBalance
import com.dugcanlift.coach.data.SideSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClientDisplayTest {
    private fun set(
        weightLb: Double? = null,
        reps: Int? = null,
        rpe: Double? = null,
        durationSec: Double? = null,
        distanceMeters: Double? = null,
        warm: Boolean = false,
        side: SetSide? = null
    ) = ExerciseSet("Back Squat", "Barbell", weightLb, reps, rpe, durationSec, distanceMeters, warm, side)

    // --- formatWeight: the kilogram conversion and the em-dash-for-null rule ---

    @Test fun `a pound weight passes through unchanged in lb`() =
        assertEquals("225", formatWeight(225.0, "lb"))

    @Test fun `a pound weight converts to kg for display only, rounded to one decimal`() =
        assertEquals("102.1", formatWeight(225.0, "kg"))

    @Test fun `a null weight is an em dash in either unit, never zero`() {
        assertEquals("—", formatWeight(null, "lb"))
        assertEquals("—", formatWeight(null, "kg"))
    }

    @Test fun `a whole-number kg conversion drops the trailing decimal`() =
        assertEquals("100", formatWeight(220.462262, "kg"))

    // --- set line rendering ---

    @Test fun `a full set renders weight times reps at RPE`() =
        assertEquals("225 × 5 @ RPE 8", formatSetLine(set(weightLb = 225.0, reps = 5, rpe = 8.0), "lb"))

    @Test fun `a set with no RPE omits the RPE suffix entirely`() =
        assertEquals("225 × 5", formatSetLine(set(weightLb = 225.0, reps = 5), "lb"))

    @Test fun `a set with no weight shows reps alone, not a zero weight`() =
        assertEquals("8 reps", formatSetLine(set(reps = 8), "lb"))

    @Test fun `a set with no weight still carries its RPE`() =
        assertEquals("8 reps @ RPE 7.5", formatSetLine(set(reps = 8, rpe = 7.5), "lb"))

    @Test fun `a duration-only set renders its duration under a minute as seconds`() =
        assertEquals("45s", formatSetLine(set(durationSec = 45.0), "lb"))

    @Test fun `a duration-only set at a minute or more renders as mm colon ss`() =
        assertEquals("1:30", formatSetLine(set(durationSec = 90.0), "lb"))

    @Test fun `a weighted set converts to kg for display`() =
        assertEquals("102.1 × 5 @ RPE 8", formatSetLine(set(weightLb = 225.0, reps = 5, rpe = 8.0), "kg"))

    // --- distance rendering: a distance-only set must never look identical to an unlogged one ---

    @Test fun `a distance-only set with neither reps nor weight renders its distance alone, not an em dash`() =
        assertEquals("500m", formatSetLine(set(distanceMeters = 500.0), "lb"))

    @Test fun `a distance-only set still carries its RPE`() =
        assertEquals("40m @ RPE 7", formatSetLine(set(distanceMeters = 40.0, rpe = 7.0), "lb"))

    @Test fun `distance paired with a duration is never dropped`() =
        assertEquals("1:30, 500m", formatSetLine(set(durationSec = 90.0, distanceMeters = 500.0), "lb"))

    @Test fun `distance alongside reps and weight is appended, not dropped`() =
        assertEquals("225 × 5, 40m", formatSetLine(set(weightLb = 225.0, reps = 5, distanceMeters = 40.0), "lb"))

    @Test fun `distance alongside reps alone is appended, not dropped`() =
        assertEquals("8 reps, 40m", formatSetLine(set(reps = 8, distanceMeters = 40.0), "lb"))

    @Test fun `distance alongside weight alone is appended, not dropped`() =
        assertEquals("225, 40m", formatSetLine(set(weightLb = 225.0, distanceMeters = 40.0), "lb"))

    @Test fun `a null distance never appears in the rendered line`() =
        assertEquals("225 × 5", formatSetLine(set(weightLb = 225.0, reps = 5, distanceMeters = null), "lb"))

    // --- lift key splitting for display ---

    @Test fun `a lift key with equipment shows the equipment alongside the name`() =
        assertEquals("Back Squat (Barbell)", liftDisplayName("Back Squat|Barbell"))

    @Test fun `a lift key with blank equipment shows the bare name`() =
        assertEquals("Pull-up", liftDisplayName("Pull-up|"))

    @Test fun `two same-named lifts on different equipment stay visually distinct`() {
        assertEquals("Row (Barbell)", liftDisplayName("Row|Barbell"))
        assertEquals("Row (Cable)", liftDisplayName("Row|Cable"))
    }

    // --- small dash-for-null helpers used by the weekly table ---

    @Test fun `formatOrDash renders an em dash for a null int, never zero`() {
        assertEquals("—", formatOrDash(null))
        assertEquals("0", formatOrDash(0))
        assertEquals("190", formatOrDash(190))
    }

    @Test fun `formatPercentOrDash renders an em dash for a null rate`() {
        assertEquals("—", formatPercentOrDash(null))
        assertEquals("50%", formatPercentOrDash(0.5))
        assertEquals("100%", formatPercentOrDash(1.0))
    }

    // --- per-limb sets ---

    // "185 x 5 L", trailing, as LIFT for Android writes it. A both-sided set says nothing: saying
    // "both" on every bench press set would be noise on every screen.
    @Test fun `a per-side set is marked, and a both-sided one is not`() {
        assertEquals("185 × 5 L", formatSetLine(set(weightLb = 185.0, reps = 5, side = SetSide.LEFT), "lb"))
        assertEquals("185 × 5 R", formatSetLine(set(weightLb = 185.0, reps = 5, side = SetSide.RIGHT), "lb"))
        assertEquals("185 × 5", formatSetLine(set(weightLb = 185.0, reps = 5), "lb"))
    }

    @Test fun `the side follows everything else the set logged`() {
        assertEquals("185 × 5 @ RPE 8 L", formatSetLine(set(weightLb = 185.0, reps = 5, rpe = 8.0, side = SetSide.LEFT), "lb"))
        assertEquals("8 reps R", formatSetLine(set(reps = 8, side = SetSide.RIGHT), "lb"))
        assertEquals("1:30, 500m L", formatSetLine(set(durationSec = 90.0, distanceMeters = 500.0, side = SetSide.LEFT), "lb"))
    }

    // One heading with two lines under it, not two headings a coach has to read as a pair.
    @Test fun `the side is not in the lift's heading`() {
        assertEquals("Split Squat (Dumbbell)", liftDisplayName("Split Squat|Dumbbell|left"))
        assertEquals("Split Squat (Dumbbell)", liftDisplayName("Split Squat|Dumbbell|"))
        assertEquals("Pull-up", liftDisplayName("Pull-up||right"))
    }

    private fun progression(vararg sessions: SideSession) = LiftProgression(
        key = "Split Squat|Dumbbell",
        both = emptyList(),
        sessions = sessions.toList(),
        imbalance = SideBalance.imbalance(sessions.toList())
    )

    @Test fun `a two-sided lift carries no imbalance lines at all`() =
        assertNull(imbalanceLines(LiftProgression("Bench Press|Barbell", listOf("2026-09-01" to 225.0), emptyList(), null)))

    // Coach web's `coach/sides.js` imbalanceLines, word for word: one decimal, a trailing ".0"
    // dropped exactly as the browser's own Math.round(percent * 1000) / 10 prints it.
    @Test fun `the headline names the stronger side and the figure to one decimal`() {
        val lines = imbalanceLines(progression(
            SideSession("2026-09-01", 80.0, 100.0),
            SideSession("2026-09-04", 80.0, 100.0),
            SideSession("2026-09-08", 95.0, 100.0),
            SideSession("2026-09-11", 95.0, 100.0)
        ))!!
        assertEquals("Right ahead by 10%", lines.headline)
        assertEquals("Mean estimated 1RM of the last 3 sessions each · gap closing", lines.detail)
    }

    @Test fun `a fractional gap keeps its one decimal`() {
        val lines = imbalanceLines(progression(
            SideSession("2026-09-01", 95.0, 100.0),
            SideSession("2026-09-04", 95.0, 100.0),
            SideSession("2026-09-08", 95.0, 100.0)
        ))!!
        // (100 - 95) / 100 == 5%, and a gap of 94.7 against 100 would read 5.3%.
        assertEquals("Right ahead by 5%", lines.headline)
        assertEquals("Mean estimated 1RM of the last 3 sessions each", lines.detail)
        assertEquals("Right ahead by 5.3%", imbalanceLines(progression(
            SideSession("2026-09-01", 94.7, 100.0),
            SideSession("2026-09-04", 94.7, 100.0),
            SideSession("2026-09-08", 94.7, 100.0)
        ))!!.headline)
    }

    @Test fun `two sides that match exactly are level, with no side named`() {
        val lines = imbalanceLines(progression(
            SideSession("2026-09-01", 100.0, 100.0),
            SideSession("2026-09-04", 100.0, 100.0),
            SideSession("2026-09-08", 100.0, 100.0)
        ))!!
        assertEquals("Sides level", lines.headline)
        // Exactly three sessions: no trend to state, so the clause is left off rather than guessed.
        assertEquals("Mean estimated 1RM of the last 3 sessions each", lines.detail)
    }

    @Test fun `a widening gap says so`() {
        val lines = imbalanceLines(progression(
            SideSession("2026-09-01", 100.0, 95.0),
            SideSession("2026-09-04", 100.0, 95.0),
            SideSession("2026-09-08", 120.0, 95.0),
            SideSession("2026-09-11", 120.0, 95.0)
        ))!!
        assertEquals("Mean estimated 1RM of the last 3 sessions each · gap widening", lines.detail)
    }

    @Test fun `a gap that has not moved says steady`() {
        val lines = imbalanceLines(progression(
            SideSession("2026-09-01", 100.0, 90.0),
            SideSession("2026-09-04", 100.0, 90.0),
            SideSession("2026-09-08", 100.0, 90.0),
            SideSession("2026-09-11", 100.0, 91.0)
        ))!!
        assertEquals("Mean estimated 1RM of the last 3 sessions each · gap steady", lines.detail)
    }

    // Below three sessions a side there is no figure, and saying what is missing beats an empty
    // space a coach would read as "no imbalance".
    @Test fun `too few sessions is an em dash and a count of what each side has`() {
        val lines = imbalanceLines(progression(
            SideSession("2026-09-01", 100.0, 90.0),
            SideSession("2026-09-08", 100.0, null)
        ))!!
        assertEquals("—", lines.headline)
        assertEquals("Needs 3 sessions a side · 2 left, 1 right so far", lines.detail)
    }

    // Tracked and shown, never targeted: nothing in this card suggests a threshold, a colour, or
    // anything to do about it. Coach web's own test asserts exactly this list.
    @Test fun `no line here tells a coach what to do about a gap`() {
        val lines = listOfNotNull(
            imbalanceLines(progression(SideSession("a", 100.0, 90.0), SideSession("b", 100.0, 90.0), SideSession("c", 100.0, 90.0))),
            imbalanceLines(progression(SideSession("a", 100.0, 90.0)))
        )
        val text = lines.joinToString(" ") { "${it.headline} ${it.detail}" }.lowercase()
        listOf("should", "fix", "warning", "target", "too ", "concern").forEach {
            assertEquals("\"$it\" has no business in this card", false, text.contains(it))
        }
    }
}
