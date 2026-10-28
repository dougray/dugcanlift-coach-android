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

    /* ---------- an exercise with no equipment ---------- */

    // The line used to be `Pull Up | 3 x 8 @ 10 kg`, and the parser reads the
    // second part as the equipment: re-saving such a routine filed the scheme
    // as equipment and dropped every set.

    private fun noEquipment(vararg exercises: RoutineExercise) = Routine(id = "bw", name = "Bodyweight", exercises = exercises.toList())

    @Test fun `an exercise with no equipment keeps its sets`() {
        val pullUp = RoutineExercise(name = "Pull Up", sets = listOf(PrescribedSet(10.0, 8), PrescribedSet(10.0, 8), PrescribedSet(15.0, 5)))
        val r = noEquipment(pullUp)

        val line = RoutineEditing.renderLine(pullUp)
        val read = parseExercises(line).single()
        assertEquals(line, "", read.equipment)
        assertEquals(line, 3, read.sets.size)
        assertEquals(line, 8, read.sets.first().targetReps)
        assertEquals(10.0, read.sets.first().targetWeightKg!!, 0.001)

        val saved = saveUnchanged(r).single()
        assertEquals("", saved.equipment)
        assertEquals(listOf(10.0, 10.0, 15.0), saved.sets.map { it.targetWeightKg })
        assertEquals(listOf(8, 8, 5), saved.sets.map { it.targetReps })
    }

    @Test fun `an each-side exercise with no equipment keeps its sets and its flag`() {
        val bridge = RoutineExercise(name = "Single Leg Glute Bridge", eachSide = true, sets = List(3) { PrescribedSet(targetReps = 12) })
        val saved = saveUnchanged(noEquipment(bridge)).single()
        assertEquals("", saved.equipment)
        assertEquals(3, saved.sets.size)
        assertEquals(List(3) { 12 }, saved.sets.map { it.targetReps })
        assertEquals(true, saved.eachSide)
    }

    @Test fun `a sided set on an exercise with no equipment keeps its side`() {
        val lunge = RoutineExercise(name = "Reverse Lunge", sets = listOf(
            PrescribedSet(targetReps = 10), PrescribedSet(targetReps = 10), PrescribedSet(targetReps = 10, side = SetSide.LEFT)))
        val saved = saveUnchanged(noEquipment(lunge)).single()
        assertEquals(listOf(null, null, SetSide.LEFT), saved.sets.map { it.side })
        assertEquals(List(3) { 10 }, saved.sets.map { it.targetReps })
    }

    @Test fun `an exercise with no equipment and no sets is still just its name`() {
        // What the exercise library writes, and what a coach types.
        assertEquals("Plank", RoutineEditing.renderLine(RoutineExercise(name = "Plank")))
        assertEquals("Row | Cable", RoutineEditing.renderLine(RoutineExercise(name = "Row", equipment = "Cable")))
        assertEquals("Bench | Barbell | 3 x 8 @ 60 kg",
            RoutineEditing.renderLine(RoutineExercise(name = "Bench", equipment = "Barbell", sets = List(3) { PrescribedSet(60.0, 8) })))
    }
}
