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
 * "each side", which set is on which side, which exercises are a superset or
 * which sets are drops, so the editor keeps those beside it ([ExerciseSides]). An exercise is keyed by its lift and its occurrence in
 * the routine -- `bulgarian split squat|dumbbell#0` -- so it is found again when
 * lines are reordered, and a lift written twice is two.
 *
 * Plain Kotlin so the rules can be tested; the screen only draws them.
 */
object RoutineEditing {

    /**
     * What the box cannot hold for one exercise: its sides -- the each-side flag, each set's side
     * (null both), and whether "Set a side" was tapped -- and its set types (PLAN-FORMAT "Set
     * types"): the group it is stored in, each set's kind (null straight), and whether
     * "Drop / rest-pause" was tapped. Named for the sides, which came first.
     */
    data class ExerciseSides(
        val eachSide: Boolean,
        val sides: List<SetSide?> = emptyList(),
        val asked: Boolean = false,
        val group: Int? = null,
        val kinds: List<SetKind?> = emptyList(),
        val askedKinds: Boolean = false
    )

    /** The line the box shows for an exercise. The first set stands for all of them. */
    fun renderLine(exercise: RoutineExercise): String {
        val first = exercise.sets.firstOrNull()
        val scheme = listOfNotNull(
            exercise.sets.size.takeIf { it > 0 }?.toString(),
            first?.targetReps?.toString()
        ).joinToString(" x ")
        // The unit is written out: an unlabelled number with a safety
        // consequence read as pounds to a coach who thinks in pounds. Two
        // decimals, so a load typed in pounds does not reopen as 61.23496 kg;
        // an untouched line still keeps its exact sets (see apply).
        val load = first?.targetWeightKg?.let { " @ ${(Math.round(it * 100) / 100.0).trimZeros()} kg" }.orEmpty()
        if (scheme.isBlank()) return listOf(exercise.name, exercise.equipment).filter { it.isNotBlank() }.joinToString(" | ")
        // A scheme always goes third, behind an empty equipment slot if need be
        // (`Pull Up | | 3 x 8`): the parser reads the second part as equipment,
        // so `Pull Up | 3 x 8` filed the scheme there and a re-save dropped every set.
        return "${exercise.name} | ${exercise.equipment}".trimEnd() + " | $scheme$load"
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

    /** The sides and types a routine opens with: exactly what it stored, so no guess touches a saved exercise. */
    fun initial(routine: Routine): Map<String, ExerciseSides> =
        keys(routine.exercises).zip(routine.exercises).associate { (key, e) ->
            key to ExerciseSides(e.eachSide, e.sets.map { it.side }, group = e.group, kinds = e.sets.map { it.kind })
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
     * takes the side and kind the editor holds for it (the first set never a
     * kind), and the exercise its each-side flag and group. A changed line still
     * keeps its note. Groups come out as the wire would number them, so a line
     * moved away from its superset, or a superset partner deleted from the box,
     * leaves no group of one behind.
     */
    fun apply(parsed: List<RoutineExercise>, original: Routine, state: Map<String, ExerciseSides>,
              remembered: (String) -> Boolean?): List<RoutineExercise> {
        val before = keys(original.exercises).zip(original.exercises).toMap()
        return PrescriptionTypes.regroup(keys(parsed).zip(parsed).map { (key, line) ->
            val old = before[key]
            val kept = old?.takeIf { renderLine(it) == renderLine(line) }
            val sets = kept?.sets ?: line.sets
            val sides = sidesFor(state, key, line, remembered)
            line.copy(
                note = old?.note ?: line.note,
                unknownKeys = old?.unknownKeys ?: line.unknownKeys,
                eachSide = sides.eachSide,
                group = sides.group,
                sets = sets.mapIndexed { i, set ->
                    set.copy(side = sides.sides.getOrNull(i), kind = if (i == 0) null else sides.kinds.getOrNull(i))
                }
            )
        })
    }

    /** [state] with one set's side changed, the list padded with both up to it. */
    fun withSide(sides: ExerciseSides, setIndex: Int, side: SetSide?): ExerciseSides {
        val list = sides.sides.toMutableList()
        while (list.size <= setIndex) list += null
        list[setIndex] = side
        return sides.copy(sides = list)
    }

    /** [sides] with one set's kind changed, the list padded with straight up to it. */
    fun withKind(sides: ExerciseSides, setIndex: Int, kind: SetKind?): ExerciseSides {
        val list = sides.kinds.toMutableList()
        while (list.size <= setIndex) list += null
        list[setIndex] = kind
        return sides.copy(kinds = list)
    }

    /**
     * [state] with exercise [index] of the box's [parsed] lines joined to the next one, or split
     * from it -- [PrescriptionTypes.settingGroupedWithNext], over the groups the lines are in now,
     * written back to every exercise whose number it changed.
     */
    fun withGroupedWithNext(state: Map<String, ExerciseSides>, parsed: List<RoutineExercise>, index: Int,
                            on: Boolean, remembered: (String) -> Boolean?): Map<String, ExerciseSides> {
        val keyed = keys(parsed).zip(parsed)
        val current = PrescriptionTypes.runs(keyed.map { (key, _) -> state[key]?.group })
        val next = PrescriptionTypes.settingGroupedWithNext(current, index, on)
        var out = state
        keyed.forEachIndexed { i, (key, line) ->
            val existing = state[key]
            if (existing?.group == next[i] || (existing == null && next[i] == null)) return@forEachIndexed
            out = out + (key to sidesFor(state, key, line, remembered).copy(group = next[i]))
        }
        return out
    }

    /* ---------- surviving a recreation: rememberSaveable holds a String ---------- */

    fun encode(state: Map<String, ExerciseSides>): String = JSONObject().also { o ->
        state.forEach { (key, s) ->
            o.put(key, JSONObject()
                .put("eachSide", s.eachSide)
                .put("asked", s.asked)
                .put("sides", JSONArray(s.sides.map { it?.wire ?: JSONObject.NULL }))
                .put("group", s.group ?: JSONObject.NULL)
                .put("kinds", JSONArray(s.kinds.map { it?.wire ?: JSONObject.NULL }))
                .put("askedKinds", s.askedKinds))
        }
    }.toString()

    fun decode(text: String): Map<String, ExerciseSides> = try {
        val o = JSONObject(text)
        o.keys().asSequence().associateWith { key ->
            val s = o.getJSONObject(key)
            val sides = s.optJSONArray("sides")
            val kinds = s.optJSONArray("kinds")
            ExerciseSides(
                eachSide = s.optBoolean("eachSide"),
                sides = if (sides == null) emptyList() else
                    (0 until sides.length()).map { if (sides.isNull(it)) null else SetSide.fromWire(sides.optString(it)) },
                asked = s.optBoolean("asked"),
                group = PrescriptionTypes.groupOf(s.opt("group")),
                kinds = if (kinds == null) emptyList() else
                    (0 until kinds.length()).map { if (kinds.isNull(it)) null else SetKind.fromWire(kinds.optString(it)) },
                askedKinds = s.optBoolean("askedKinds")
            )
        }
    } catch (e: Exception) {
        emptyMap()
    }
}
