package com.dugcanlift.coach.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * What Train's "Print or save as PDF" puts on paper. A port of Coach iOS's
 * `PlanPrintoutTests`, and the same lines Coach web prints.
 */
class PlanPrintoutTest {

    private val week = PlanWeek("2026-10-26")

    /** en-US's pattern for "EEEdMMM", pinned so the words can be asserted. */
    private val title: (String) -> String = { PlanPrintout.dayTitle(it, Locale.US, "EEE, MMM d") }

    private fun lower() = Routine(
        id = "lower-a",
        name = "Lower A",
        exercises = listOf(
            RoutineExercise(
                name = "Back Squat",
                equipment = "barbell",
                note = "Belt on the last set.",
                sets = List(3) { PrescribedSet(targetWeightKg = 100.0, targetReps = 5) }
            ),
            RoutineExercise(name = "Plank", sets = listOf(PrescribedSet(targetDurationSec = 45)))
        )
    )

    @Test fun onlyBookedDaysInOrderAndARemovedTemplateSaysSo() {
        val sessions = listOf(
            ScheduledSession(clientId = "c1", dayKey = "2026-10-29", routineId = "gone"),
            ScheduledSession(clientId = "c1", dayKey = "2026-10-26", routineId = "lower-a"),
            // Another client's booking, and one outside the week: neither prints.
            ScheduledSession(clientId = "c2", dayKey = "2026-10-27", routineId = "lower-a"),
            ScheduledSession(clientId = "c1", dayKey = "2026-11-02", routineId = "lower-a")
        )
        val days = PlanPrintout.days(week, "c1", sessions, listOf(lower()))
        assertEquals(listOf("2026-10-26", "2026-10-29"), days.map { it.dayKey })
        assertEquals(
            PlanPrintout.Workout(
                "Lower A",
                listOf(
                    PlanPrintout.Exercise("Back Squat (Barbell)", "3 × 100 × 5", "Belt on the last set."),
                    PlanPrintout.Exercise("Plank", "45s", null)
                )
            ),
            days[0].workouts.single()
        )
        assertEquals(listOf(PlanPrintout.Workout("Removed workout", emptyList())), days[1].workouts)
    }

    @Test fun twoBookingsOnADayKeepTheirStoredOrder() {
        val upper = Routine(id = "upper", name = "Upper A")
        val sessions = listOf(
            ScheduledSession(clientId = "c1", dayKey = "2026-10-26", routineId = "upper"),
            ScheduledSession(clientId = "c1", dayKey = "2026-10-26", routineId = "lower-a")
        )
        val days = PlanPrintout.days(week, "c1", sessions, listOf(lower(), upper))
        assertEquals(listOf("Upper A", "Lower A"), days.single().workouts.map { it.name })
    }

    @Test fun theLinesAreTheIPhonesLinesInItsOrder() {
        val days = PlanPrintout.days(
            week, "c1",
            listOf(
                ScheduledSession(clientId = "c1", dayKey = "2026-10-26", routineId = "lower-a"),
                ScheduledSession(clientId = "c1", dayKey = "2026-10-29", routineId = "lower-a")
            ),
            listOf(lower())
        )
        val lines = PlanPrintout.lines("Jordan Reyes", "2026-10-26", "Doug", days, title)
        assertEquals(
            listOf(
                "Jordan Reyes",
                "Week of Mon, Oct 26 · from Doug",
                "Mon, Oct 26",
                "Lower A",
                "Back Squat (Barbell): 3 × 100 × 5",
                "Belt on the last set.",
                "Plank: 45s",
                "Thu, Oct 29",
                "Lower A",
                "Back Squat (Barbell): 3 × 100 × 5",
                "Belt on the last set.",
                "Plank: 45s"
            ),
            lines.map { it.text }
        )
        assertEquals(
            listOf(
                PlanPrintout.Style.TITLE, PlanPrintout.Style.SUBTITLE,
                PlanPrintout.Style.DAY, PlanPrintout.Style.WORKOUT,
                PlanPrintout.Style.EXERCISE, PlanPrintout.Style.NOTE, PlanPrintout.Style.EXERCISE,
                PlanPrintout.Style.DAY, PlanPrintout.Style.WORKOUT,
                PlanPrintout.Style.EXERCISE, PlanPrintout.Style.NOTE, PlanPrintout.Style.EXERCISE
            ),
            lines.map { it.style }
        )
    }

    @Test fun noCoachNameMeansNoFromClause() {
        assertEquals("Week of Mon, Oct 26", PlanPrintout.lines("A", "2026-10-26", null, emptyList(), title)[1].text)
        assertEquals("Week of Mon, Oct 26", PlanPrintout.lines("A", "2026-10-26", "  ", emptyList(), title)[1].text)
    }

    @Test fun anEmptyWeekSaysSo() {
        assertEquals(
            listOf("Plan", "Week of Mon, Oct 26", "Nothing is booked this week."),
            PlanPrintout.lines("", "2026-10-26", null, emptyList(), title).map { it.text }
        )
    }

    @Test fun eachSideAndSetKindsReadAsTheWorkoutCardsDo() {
        val lunge = RoutineExercise(
            name = "Walking Lunge", equipment = "dumbbell", eachSide = true,
            sets = List(3) { PrescribedSet(targetWeightKg = 20.0, targetReps = 8) } +
                PrescribedSet(targetWeightKg = 20.0, targetReps = 8, side = SetSide.LEFT)
        )
        val exercise = PlanPrintout.days(
            week, "c1",
            listOf(ScheduledSession(clientId = "c1", dayKey = "2026-10-26", routineId = "r")),
            listOf(Routine(id = "r", name = "Legs", exercises = listOf(lunge)))
        ).single().workouts.single().exercises.single()
        assertEquals(PrescriptionSides.summary(lunge), exercise.prescription)
        assertEquals("3 × 20 × 8 each side + 1 L", exercise.prescription)
        assertEquals("Walking Lunge (Dumbbell)", exercise.name)
    }

    @Test fun equipmentIsTitledWordByWord() {
        assertEquals("Row (Smith Machine)",
                     PlanPrintout.exerciseName(RoutineExercise(name = "Row", equipment = "smith machine")))
        assertEquals("Row", PlanPrintout.exerciseName(RoutineExercise(name = "Row", equipment = " ")))
    }

    @Test fun theFileNameIsSafe() {
        assertEquals("JoReyes – week of 2026-10-26.pdf", PlanPrintout.fileName("Jo/Rey:es", "2026-10-26"))
        assertEquals("Plan – week of 2026-10-26.pdf", PlanPrintout.fileName("<>", "2026-10-26"))
    }

    @Test fun aDayKeyThatIsNotADateIsPrintedAsItIs() {
        assertEquals("someday", PlanPrintout.dayTitle("someday", Locale.US, "EEE, MMM d"))
    }

    /** A long programme goes on to a second page rather than off the bottom. */
    @Test fun aLongWeekRunsOntoMorePages() {
        val exercises = (0 until 80).map { PlanPrintout.Exercise("Exercise $it", "3 × 10", null) }
        val lines = PlanPrintout.lines(
            "A", "2026-10-26", null,
            listOf(PlanPrintout.Day("2026-10-26", listOf(PlanPrintout.Workout("Big", exercises)))),
            title
        )
        // 11 pt text measures about 13 pt a line.
        val pages = PlanPrintout.paginate(lines.map { 13f }, lines.map { it.before })
        assertTrue("80 exercises should not fit one page", pages.size > 1)
        assertEquals("every block is placed once, in order", lines.indices.toList(), pages.flatten())
        pages.forEach { page ->
            val used = page.mapIndexed { i, index -> 13f + if (i == 0) 0f else lines[index].before }.sum()
            assertTrue("a page holds no more than its printable height", used <= 792f - 2 * 54f)
        }
    }

    @Test fun aShortWeekIsOnePageAndNothingIsOneEmptyPage() {
        assertEquals(listOf(listOf(0, 1, 2)), PlanPrintout.paginate(listOf(24f, 12f, 13f), listOf(0f, 0f, 16f)))
        assertEquals(listOf(emptyList<Int>()), PlanPrintout.paginate(emptyList(), emptyList()))
    }

    @Test fun aBlockTallerThanAPageGetsItsOwnPageWithNoBlankOneBefore() {
        assertEquals(listOf(listOf(0), listOf(1)), PlanPrintout.paginate(listOf(20f, 900f), listOf(0f, 6f)))
        assertEquals(listOf(listOf(0), listOf(1)), PlanPrintout.paginate(listOf(900f, 20f), listOf(0f, 6f)))
    }
}
