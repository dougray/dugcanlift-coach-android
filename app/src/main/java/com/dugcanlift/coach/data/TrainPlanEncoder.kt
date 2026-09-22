package com.dugcanlift.coach.data

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.round

/**
 * The training half of a plan link: a routine as PLAN-FORMAT's `w` entry, the
 * sessions booked from it as `k`, and the fragment that carries them.
 *
 * A port of Coach web's `workoutWire` / `exerciseWire` / `setTuple`
 * (`coach/prescriptions.js`) and the `w`/`k` half of its `encodePlan`. Checked
 * against Coach web's own links (`fixtures/web-plan-per-side.txt`,
 * `fixtures/web-plan-link.txt`) and read back through the kit's
 * `PlanLinkCodec`, the decoder LIFT Android ships, rather than against a second
 * copy of these rules. What is *in* a send -- which client, which week -- is
 * [TrainPlanSend]'s decision, not this object's.
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

    /**
     * A client's booked training as a plan fragment: `w` the templates, `k` the
     * days they are booked on.
     *
     * @param routines the templates to inline, in the order `k` indexes them.
     *   Only the ones this send actually books belong here -- an unbooked
     *   routine would be a library send (PLAN-FORMAT "A payload may also carry
     *   `r` or `w` with no `m` or `k`"), which this is not.
     * @param sessions the bookings. A session whose routine is not in
     *   [routines] is **dropped**, never pointed at whichever template happens
     *   to sit at that index: `x` indexes into `w`, and a stale index is the
     *   wrong workout on someone's Tuesday. That is also what a session left
     *   behind by a deleted routine does here -- the screen says so in words
     *   ("Removed workout") rather than sending a day that carries nothing.
     * @return the fragment, without a leading `#`.
     */
    fun encode(
        routines: List<Routine>,
        sessions: List<ScheduledSession>,
        lifterId: String,
        coachName: String
    ): String {
        val indexById = routines.withIndex().associate { (i, r) -> r.id to i }

        val w = JSONArray()
        routines.forEach { w.put(workoutWire(it)) }

        val k = JSONArray()
        sessions.forEach { session ->
            val index = indexById[session.routineId] ?: return@forEach
            k.put(JSONObject().put("d", session.dayKey).put("x", index))
        }

        val payload = PlanEnvelope.payload(lifterId, coachName)
        // Empty means absent, not `[]` -- PLAN-FORMAT: "a coach who plans only
        // training sends a payload with no `r` or `m` at all," and the mirror
        // of it here. Every decoder treats all four keys as optional.
        if (w.length() > 0) payload.put("w", w)
        if (k.length() > 0) payload.put("k", k)

        return PlanEnvelope.fragment(payload)
    }
}
