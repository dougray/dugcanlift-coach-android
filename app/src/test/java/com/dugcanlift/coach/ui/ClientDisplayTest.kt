package com.dugcanlift.coach.ui

import com.dugcanlift.coach.data.ExerciseSet
import org.junit.Assert.assertEquals
import org.junit.Test

class ClientDisplayTest {
    private fun set(
        weightLb: Double? = null,
        reps: Int? = null,
        rpe: Double? = null,
        durationSec: Double? = null,
        warm: Boolean = false
    ) = ExerciseSet("Back Squat", "Barbell", weightLb, reps, rpe, durationSec, null, warm)

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
}
