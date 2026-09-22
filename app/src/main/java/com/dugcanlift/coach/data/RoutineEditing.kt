package com.dugcanlift.coach.data

import com.dugcanlift.kit.trimZeros

/**
 * How the routine editor's text box is put back into a routine.
 *
 * The box is one line per exercise, `name | equipment | sets x reps @ kg`, which
 * shows only an exercise's first set and none of its note. An exercise is keyed
 * by its lift and its occurrence in the routine -- `back squat|barbell#0` -- so
 * it is found again when lines are reordered, and a lift written twice is two.
 *
 * Plain Kotlin so the rules can be tested; the screen only draws them.
 */
object RoutineEditing {

    /** The line the box shows for an exercise. The first set stands for all of them. */
    fun renderLine(exercise: RoutineExercise): String {
        val first = exercise.sets.firstOrNull()
        val scheme = listOfNotNull(
            exercise.sets.size.takeIf { it > 0 }?.toString(),
            first?.targetReps?.toString()
        ).joinToString(" x ")
        val load = first?.targetWeightKg?.trimZeros()?.let { " @ $it" }.orEmpty()
        return listOf(exercise.name, exercise.equipment).filter { it.isNotBlank() }
            .joinToString(" | ") + (if (scheme.isNotBlank()) " | $scheme$load" else "")
    }

    /** Each exercise's key: its lift, `name|equipment`, and which occurrence of that lift it is. */
    fun keys(exercises: List<RoutineExercise>): List<String> {
        val seen = mutableMapOf<String, Int>()
        return exercises.map { e ->
            val lift = "${e.name.trim()}|${e.equipment.trim()}".lowercase(java.util.Locale.US)
            val n = seen.getOrDefault(lift, 0)
            seen[lift] = n + 1
            "$lift#$n"
        }
    }

    /**
     * The routine to save from the box's [parsed] lines.
     *
     * An exercise whose line is unchanged keeps its own sets, note and any keys a
     * newer writer added: the line shows only the first set, so re-reading it
     * would flatten a 60/60/70 ramp to three 60s, and nothing in the box can hold
     * a note. A changed line takes the sets it now says, and still keeps the note.
     */
    fun apply(parsed: List<RoutineExercise>, original: Routine): List<RoutineExercise> {
        val before = keys(original.exercises).zip(original.exercises).toMap()
        return keys(parsed).zip(parsed).map { (key, line) ->
            val old = before[key] ?: return@map line
            val kept = old.takeIf { renderLine(it) == renderLine(line) }
            line.copy(
                note = old.note ?: line.note,
                unknownKeys = old.unknownKeys ?: line.unknownKeys,
                sets = kept?.sets ?: line.sets
            )
        }
    }
}
