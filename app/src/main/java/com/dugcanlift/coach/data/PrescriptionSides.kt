package com.dugcanlift.coach.data

import com.dugcanlift.kit.trimZeros
import java.text.NumberFormat
import java.util.Locale

/**
 * Prescribed sets and their sides -- PLAN-FORMAT "Sides", as Coach's editor
 * shows them. A port of Coach web's `coach/prescriptions.js` (with the name
 * guess from `sides.js`), function for function; the rule changes there first.
 *
 * Two separate, small things, and neither turns a single-limb set into two rows:
 *
 * - **each side**, an exercise flag ([RoutineExercise.eachSide]): every
 *   prescribed set is done on both sides. "3 x 8, each side" stays three rows.
 * - **a named side**, per set ([PrescribedSet.side]): done on that side once,
 *   for the asymmetric cases -- an extra set on the left, rehab side only.
 *
 * Tracked and prescribed, never judged: nothing here comments on a coach
 * writing an extra left set.
 *
 * Pure and free of Android so it can be tested directly.
 */
object PrescriptionSides {

    /**
     * Names that usually mean one limb at a time -- LIFT's own list
     * (LIFT Android's `PerSideLogging`, LIFT web's and Coach web's `sides.js`),
     * term for term. The editor pre-ticks "Each side" with it exactly as LIFT
     * pre-ticks per-side logging, so a coach and a client see the same
     * exercises start ticked. Do not add a term here alone.
     */
    private val UNILATERAL_TERMS = listOf(
        "single arm", "one arm", "1 arm", "single handed",
        "single leg", "one leg", "1 leg", "single limb",
        "one legged", "single legged", "one armed", "single armed",
        "bulgarian", "split squat", "split squats",
        "pistol", "pistols", "lunge", "lunges",
        "step up", "step ups", "stepup", "stepups",
        "unilateral"
    )

    /**
     * Whether a name reads as a lift with a side to it. Matched as whole words
     * against the name with everything that is not a letter or a digit turned
     * into a space, so "Single-Arm" and "Single Arm" are one term and "lunge"
     * does not tick a cold plunge. A guess, used only to decide where "Each
     * side" starts; the coach's own choice is what sticks.
     */
    fun looksUnilateral(name: String): Boolean {
        val cleaned = name.lowercase(Locale.US).map { if (it.isLetterOrDigit()) it else ' ' }.joinToString("")
        val padded = " " + cleaned.split(' ').filter { it.isNotEmpty() }.joinToString(" ") + " "
        return UNILATERAL_TERMS.any { padded.contains(" $it ") }
    }

    /** How a lift is keyed for the coach's remembered each-side choice: `name|equipment`, as LIFT keys per-side logging. */
    fun eachSideKey(name: String, equipment: String): String =
        "${name.trim()}|${equipment.trim()}".lowercase(Locale.US)

    /** Where "Each side" starts: as the coach last left this lift, or by the name when they never have. */
    fun eachSideDefault(remembered: Boolean?, name: String): Boolean = remembered ?: looksUnilateral(name)

    /** Sets asked for on each side, and two-sided ones. */
    data class Targets(val left: Int, val right: Int, val both: Int)

    /**
     * An each-side exercise's unmarked set counts once on each side; a set that
     * names a side counts once on that side, each side or not. So "3 x 8 each
     * side plus one left" is left 4, right 3 -- seven sets.
     */
    fun targets(exercise: RoutineExercise): Targets {
        var left = 0
        var right = 0
        var both = 0
        exercise.sets.forEach {
            when {
                it.side == SetSide.LEFT -> left++
                it.side == SetSide.RIGHT -> right++
                exercise.eachSide -> { left++; right++ }
                else -> both++
            }
        }
        return Targets(left, right, both)
    }

    /**
     * Whether the per-set Both / L / R control shows: once the exercise is each
     * side, once a set names a side, or once the coach asked for it with "Set a
     * side" -- so a bench press editor looks exactly as it always did.
     */
    fun showsSides(exercise: RoutineExercise, asked: Boolean): Boolean =
        exercise.eachSide || exercise.sets.any { it.side != null } || asked

    /* ---------- how it reads ---------- */

    private fun whole(n: Double): String = NumberFormat.getIntegerInstance().format(Math.round(n))

    /** Kilograms to one decimal, trailing ".0" dropped: 22.5 stays 22.5, 13.6077711 kg (30 lb) reads 13.6. */
    private fun kilos(kg: Double): String = (Math.round(kg * 10) / 10.0).trimZeros()

    /**
     * A set's numbers: "60 × 8 · @8", "5 reps", "1,600 m · 10:00". Weights are
     * kilograms, as this app stores and edits them -- Coach web's text is its
     * pounds, and to one decimal here rather than rounded whole, because 22.5 kg
     * is an ordinary dumbbell.
     */
    fun prescriptionText(set: PrescribedSet): String {
        val bits = mutableListOf<String>()
        val kg = set.targetWeightKg
        val reps = set.targetReps
        when {
            kg != null && reps != null -> bits += "${kilos(kg)} × $reps"
            reps != null -> bits += "$reps reps"
            kg != null -> bits += "${kilos(kg)} kg"
        }
        set.targetDistanceMeters?.let { bits += "${whole(it)} m" }
        set.targetDurationSec?.let { sec ->
            val minutes = sec / 60
            bits += if (minutes > 0) "$minutes:${(sec % 60).toString().padStart(2, '0')}" else "${sec}s"
        }
        set.targetRpe?.let { bits += "@${it.trimZeros()}" }
        return bits.joinToString(" · ").ifEmpty { "as written" }
    }

    /** The same, with the side it names: "30 × 8 L". Nothing added for both. */
    fun setText(set: PrescribedSet): String =
        prescriptionText(set) + (set.side?.let { " ${it.short}" } ?: "")

    /** Sets compare on what they prescribe, not on keys a newer writer added. */
    private fun same(a: PrescribedSet, b: PrescribedSet) = a.copy(unknownKeys = null) == b.copy(unknownKeys = null)

    /** "3 × 60 × 8" when every set matches, otherwise each set spelled out. */
    private fun listText(sets: List<PrescribedSet>, text: (PrescribedSet) -> String): String =
        if (sets.size > 1 && sets.all { same(it, sets[0]) }) "${sets.size} × ${text(sets[0])}"
        else sets.joinToString(", ", transform = text)

    /**
     * One line for an exercise: "3 × 60 × 8", "3 × 30 × 8 each side",
     * "3 × 30 × 8 each side + 1 L", "60 × 8, 60 × 8, 40 × 10 R". An exercise
     * with no side anywhere reads as a plain list, as it always would.
     */
    fun summary(exercise: RoutineExercise): String {
        val sets = exercise.sets
        if (sets.isEmpty()) return "no sets yet"
        val plain = sets.filter { it.side == null }
        if (!exercise.eachSide || plain.isEmpty()) return listText(sets, ::setText)

        val plainText = listText(plain, ::prescriptionText)
        val sameText = if (plain.all { same(it, plain[0]) }) prescriptionText(plain[0]) else null

        // The named extras, grouped by what they say, in the order they appear.
        data class Group(val side: SetSide, val text: String, var count: Int)
        val groups = mutableListOf<Group>()
        sets.forEach { s ->
            val side = s.side ?: return@forEach
            val text = prescriptionText(s)
            groups.firstOrNull { it.side == side && it.text == text }?.let { it.count++ }
                ?: groups.add(Group(side, text, 1))
        }
        val extras = groups.joinToString("") { g ->
            // "+ 1 L" when the extra is the same set; its numbers when it is not.
            if (g.text == sameText) " + ${g.count} ${g.side.short}"
            else " + " + (if (g.count > 1) "${g.count} × " else "") + "${g.text} ${g.side.short}"
        }
        return "$plainText each side$extras"
    }
}
