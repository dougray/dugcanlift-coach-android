package com.dugcanlift.coach.data

import com.dugcanlift.coach.ui.parseExercises
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Saving the routine editor used to rebuild every exercise from its line of
 * text, and a line shows only the first set and no note: opening a routine and
 * pressing Save flattened a 60/60/70 ramp to three 60s and deleted every note,
 * along with any key a newer Coach wrote.
 */
class RoutineEditingTest {

    private val ramp = RoutineExercise(
        name = "Back Squat", equipment = "Barbell", note = "Belt on the last set.",
        sets = listOf(PrescribedSet(60.0, 5), PrescribedSet(60.0, 5), PrescribedSet(70.0, 3)),
        unknownKeys = JSONObject().put("tempo", "31X1")
    )
    private val routine = Routine(id = "r", name = "Lower A", exercises = listOf(
        ramp, RoutineExercise(name = "Walking Lunge", equipment = "Dumbbell", note = "Slow",
            sets = List(2) { PrescribedSet(20.0, 12) })
    ))

    private fun saveUnchanged(r: Routine) =
        RoutineEditing.apply(parseExercises(r.exercises.joinToString("\n", transform = RoutineEditing::renderLine)), r,
            RoutineEditing.initial(r)) { null }

    @Test fun `an untouched routine saves as it was`() {
        val saved = saveUnchanged(routine)
        assertEquals(listOf(60.0, 60.0, 70.0), saved[0].sets.map { it.targetWeightKg })
        assertEquals(listOf(5, 5, 3), saved[0].sets.map { it.targetReps })
        assertEquals("Belt on the last set.", saved[0].note)
        assertEquals("31X1", saved[0].unknownKeys!!.getString("tempo"))
        assertEquals("Slow", saved[1].note)
    }

    @Test fun `reordered lines still find their exercise`() {
        val lines = routine.exercises.reversed().joinToString("\n", transform = RoutineEditing::renderLine)
        val saved = RoutineEditing.apply(parseExercises(lines), routine, RoutineEditing.initial(routine)) { null }
        assertEquals(listOf("Walking Lunge", "Back Squat"), saved.map { it.name })
        assertEquals(listOf(60.0, 60.0, 70.0), saved[1].sets.map { it.targetWeightKg })
    }

    @Test fun `a changed line takes what it now says, and keeps its note`() {
        val saved = RoutineEditing.apply(parseExercises("Back Squat | Barbell | 4 x 5 @ 65"), routine, RoutineEditing.initial(routine)) { null }
        assertEquals(List(4) { 65.0 }, saved.single().sets.map { it.targetWeightKg })
        assertEquals("Belt on the last set.", saved.single().note)
    }

    @Test fun `a new line is only what it says`() {
        val saved = RoutineEditing.apply(parseExercises("Bench Press | Barbell | 3 x 8 @ 60"), routine, RoutineEditing.initial(routine)) { null }
        assertEquals(null, saved.single().note)
        assertEquals(3, saved.single().sets.size)
    }
}
