package com.dugcanlift.coach.data

import com.dugcanlift.kit.trimZeros
import org.json.JSONArray
import org.json.JSONObject

/**
 * How the routine editor's text box is put back into a routine, and what it
 * cannot hold.
 *
 * The box is one line per exercise, `name | equipment | sets x reps @ kg`, which
 * shows only an exercise's first set and none of its note -- and cannot say
 * "each side" or which set is on which side, so the editor keeps those beside
 * it ([ExerciseSides]). An exercise is keyed by its lift and its occurrence in
 * the routine -- `bulgarian split squat|dumbbell#0` -- so it is found again when
 * lines are reordered, and a lift written twice is two.
 *
 * Plain Kotlin so the rules can be tested; the screen only draws them.
 */
object RoutineEditing {

    /** One exercise's sides: the each-side flag, each set's side (null both), and whether "Set a side" was tapped. */
    data class ExerciseSides(val eachSide: Boolean, val sides: List<SetSide?> = emptyList(), val asked: Boolean = false)

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
            val lift = PrescriptionSides.eachSideKey(e.name, e.equipment)
            val n = seen.getOrDefault(lift, 0)
            seen[lift] = n + 1
            "$lift#$n"
        }
    }

    /** The sides a routine opens with: exactly what it stored, so no guess touches a saved exercise. */
    fun initial(routine: Routine): Map<String, ExerciseSides> =
        keys(routine.exercises).zip(routine.exercises).associate { (key, e) ->
            key to ExerciseSides(e.eachSide, e.sets.map { it.side })
        }

    /**
     * An exercise's sides: what the editor holds for it, or, for one that
     * appeared while editing, "Each side" as the coach last left that lift --
     * or ticked by its name when they never have.
     */
    fun sidesFor(state: Map<String, ExerciseSides>, key: String, exercise: RoutineExercise,
                 remembered: (String) -> Boolean?): ExerciseSides =
        state[key] ?: ExerciseSides(
            eachSide = PrescriptionSides.eachSideDefault(
                remembered(PrescriptionSides.eachSideKey(exercise.name, exercise.equipment)), exercise.name)
        )

    /**
     * The routine to save from the box's [parsed] lines.
     *
     * An exercise whose line is unchanged keeps its own sets, note and any keys a
     * newer writer added: the line shows only the first set, so re-reading it
     * would flatten a 60/60/70 ramp to three 60s, and nothing in the box can hold
     * a note. A changed line takes the sets it now says. Either way each set
     * takes the side the editor holds for it, and the exercise its each-side flag.
     * A changed line still keeps its note.
     */
    fun apply(parsed: List<RoutineExercise>, original: Routine, state: Map<String, ExerciseSides>,
              remembered: (String) -> Boolean?): List<RoutineExercise> {
        val before = keys(original.exercises).zip(original.exercises).toMap()
        return keys(parsed).zip(parsed).map { (key, line) ->
            val old = before[key]
            val kept = old?.takeIf { renderLine(it) == renderLine(line) }
            val sets = kept?.sets ?: line.sets
            val sides = sidesFor(state, key, line, remembered)
            line.copy(
                note = old?.note ?: line.note,
                unknownKeys = old?.unknownKeys ?: line.unknownKeys,
                eachSide = sides.eachSide,
                sets = sets.mapIndexed { i, set -> set.copy(side = sides.sides.getOrNull(i)) }
            )
        }
    }

    /** [state] with one set's side changed, the list padded with both up to it. */
    fun withSide(sides: ExerciseSides, setIndex: Int, side: SetSide?): ExerciseSides {
        val list = sides.sides.toMutableList()
        while (list.size <= setIndex) list += null
        list[setIndex] = side
        return sides.copy(sides = list)
    }

    /* ---------- surviving a recreation: rememberSaveable holds a String ---------- */

    fun encode(state: Map<String, ExerciseSides>): String = JSONObject().also { o ->
        state.forEach { (key, s) ->
            o.put(key, JSONObject()
                .put("eachSide", s.eachSide)
                .put("asked", s.asked)
                .put("sides", JSONArray(s.sides.map { it?.wire ?: JSONObject.NULL })))
        }
    }.toString()

    fun decode(text: String): Map<String, ExerciseSides> = try {
        val o = JSONObject(text)
        o.keys().asSequence().associateWith { key ->
            val s = o.getJSONObject(key)
            val sides = s.optJSONArray("sides")
            ExerciseSides(
                eachSide = s.optBoolean("eachSide"),
                sides = if (sides == null) emptyList() else
                    (0 until sides.length()).map { if (sides.isNull(it)) null else SetSide.fromWire(sides.optString(it)) },
                asked = s.optBoolean("asked")
            )
        }
    } catch (e: Exception) {
        emptyMap()
    }
}
