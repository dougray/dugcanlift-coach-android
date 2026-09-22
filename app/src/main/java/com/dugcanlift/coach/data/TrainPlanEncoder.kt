package com.dugcanlift.coach.data

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.round

/**
 * The training half of a plan link: a routine as PLAN-FORMAT's `w` entry.
 *
 * Coach Android does not send a client's training yet -- Train has no Send,
 * only Cook does ([CookPlanEncoder]) -- so nothing on screen calls this. It
 * exists so the wire rules for per-side prescriptions live beside the editor
 * that writes them and are checked against Coach web's own link
 * (`fixtures/web-plan-per-side.txt`) and read back through the kit decoder
 * LIFT Android uses, ready for the day Train gets its Send. A port of Coach
 * web's `workoutWire` / `exerciseWire` / `setTuple` (`coach/prescriptions.js`).
 */
object TrainPlanEncoder {

    /** Bits 1-2 of SHARE-FORMAT's flags byte. Bit 0 (warmup) is always 0 in a plan. */
    private const val SIDE_SHIFT = 1

    private fun sideBits(side: SetSide): Int = when (side) {
        SetSide.LEFT -> 1
        SetSide.RIGHT -> 2
    } shl SIDE_SHIFT

    /**
     * Kilograms back to the wire's pounds. Rounded to six places only to drop
     * the float noise a pound value picks up going to kilograms and back
     * (30 lb stored as kg writes 30, not 30.000000000000004); no weight anyone
     * prescribes is that precise.
     */
    internal fun kgToLb(kg: Double): Double = round(kg * LB_PER_KG * 1_000_000.0) / 1_000_000.0

    /**
     * `[weightLb, reps, rpe, durationSec, distanceMeters, flags]`.
     *
     * A both-sides set is the five-field tuple it has always been, trailing
     * nulls trimmed, with no sixth position at all. A set that names a side
     * keeps every position up to its flags: only trailing nulls are ever
     * trimmed, or a left-side conditioning piece `[null, null, null, 600, 1600, 2]`
     * would slide its distance into the weight slot.
     */
    fun setTuple(set: PrescribedSet): JSONArray {
        val values = mutableListOf<Any?>(
            set.targetWeightKg?.let(::kgToLb),
            set.targetReps,
            set.targetRpe,
            set.targetDurationSec,
            set.targetDistanceMeters
        )
        val side = set.side
        if (side != null) values += sideBits(side)
        else while (values.isNotEmpty() && values.last() == null) values.removeAt(values.lastIndex)
        return JSONArray().also { array -> values.forEach { array.put(it ?: JSONObject.NULL) } }
    }

    /**
     * One exercise: `n`, `q` when there is equipment, `b: 1` only when each
     * side (never `0`), `s`, and `c` when there is a note -- inserted in that
     * order, which Android's org.json keeps. An exercise with no side anywhere
     * is the object it would always have been.
     */
    fun exerciseWire(exercise: RoutineExercise): JSONObject {
        val o = JSONObject().put("n", exercise.name)
        if (exercise.equipment.isNotBlank()) o.put("q", exercise.equipment)
        if (exercise.eachSide) o.put("b", 1)
        o.put("s", JSONArray().also { array -> exercise.sets.forEach { array.put(setTuple(it)) } })
        exercise.note?.takeIf { it.isNotBlank() }?.let { o.put("c", it) }
        return o
    }

    fun workoutWire(routine: Routine): JSONObject = JSONObject()
        .put("n", routine.name)
        .put("e", JSONArray().also { array -> routine.exercises.forEach { array.put(exerciseWire(it)) } })
}
