package com.dugcanlift.coach.data

/**
 * How a set follows the one before it (PLAN-FORMAT "Set types"). Null is straight, everywhere:
 * absent is how straight is spelt, in storage, in a backup and on the wire.
 *
 * @property wire BACKUP-FORMAT's spelling, `"drop"` / `"restPause"`, written only for these two.
 * @property label the word a set's text ends with: "50 × 10 drop" (Coach web's `kindLabel`).
 * @property displayName the editor's control: "Drop", "Rest-pause".
 * @property bits flags bits 3-4: 1 drop, 2 rest-pause. 0 is straight; 3 is never written.
 */
enum class SetKind(val wire: String, val label: String, val displayName: String, val bits: Int) {
    /** Done straight after the set before it, with no rest; normally lighter. */
    DROP("drop", "drop", "Drop", 1),
    /** Done after a short pause, about 15 seconds; normally the same weight. */
    REST_PAUSE("restPause", "rest-pause", "Rest-pause", 2);

    companion object {
        /**
         * Lenient, as a side is: case and surrounding space are forgiven, and anything that is not
         * drop or rest-pause -- absent, `"straight"`, junk from a file another app wrote -- is
         * straight, the only safe reading of a set nobody described. Coach web's `kindOf`.
         */
        fun fromWire(value: String?): SetKind? = when (value?.trim()?.lowercase()) {
            "drop" -> DROP
            "restpause" -> REST_PAUSE
            else -> null
        }
    }
}

/**
 * Set types -- supersets and circuits, drop sets and rest-pause -- as rules on plain values. A port
 * of Coach web's `coach/prescriptions.js` (`runs`, `regroup`, `groupedWithNext`,
 * `setGroupedWithNext`, `letters`, `groupLabels`, `kindFromFlags`), rule for rule; the rules change
 * there first. [PrescriptionSides] is the precedent.
 *
 * - **a group**, an exercise number ([RoutineExercise.group]): exercises done round by round --
 *   one set of each, then the rest. Two make a superset, three or more a circuit. *Adjacency*
 *   makes the group, not the number, so a group of one is no group and is never written or kept.
 * - **a kind**, per set ([PrescribedSet.kind]): drop or rest-pause. An exercise's first set has no
 *   set before it, so it is always straight.
 *
 * Counted and shown, never graded: nothing here says whether a superset was done as one.
 *
 * Pure and free of Android so it can be tested directly.
 */
object PrescriptionTypes {

    /** Bits 3-4 of a plan tuple's flags. */
    const val KIND_SHIFT = 3

    /** A group number as stored: a positive integer, or null. `0` or a negative is not grouped. */
    fun groupOf(value: Int?): Int? = value?.takeIf { it > 0 }

    /**
     * A `group` or `g` as org.json hands it over. Only a JSON number that is a positive integer is
     * a group: a string, a fraction, a negative, a boolean, null or anything else is not grouped
     * (Coach web's `groupOf`, which takes `Number.isInteger`, so `2.0` is 2 there and here).
     */
    fun groupOf(json: Any?): Int? {
        val number = json as? Number ?: return null
        val d = number.toDouble()
        if (d.isNaN() || d.isInfinite() || d != Math.floor(d) || d <= 0 || d > Int.MAX_VALUE) return null
        return d.toInt()
    }

    /**
     * The kind bits 3-4 of a flags field say, or null for straight. **Masked, never compared**: a
     * left-side drop set arrives as 10, and a reader testing `flags == 8` would call it straight.
     * Anything that is not a whole, non-negative number is straight; `3` in the bits is never
     * written and reads as straight, and bit 0 and the side bits are not this reader's.
     *
     * Coach sends plans and never decodes one of its own, so this is what the tests read the
     * fixture and the encoder's output with -- and what anything that ever reads a stored payload's
     * kinds must use.
     */
    fun kindFromFlags(raw: Any?): SetKind? {
        val number = raw as? Number ?: return null
        val d = number.toDouble()
        if (d.isNaN() || d.isInfinite() || d != Math.floor(d) || d < 0 || d > Int.MAX_VALUE) return null
        return when ((d.toInt() shr KIND_SHIFT) and 3) {
            SetKind.DROP.bits -> SetKind.DROP
            SetKind.REST_PAUSE.bits -> SetKind.REST_PAUSE
            else -> null
        }
    }

    /**
     * The group each position belongs to, as the wire numbers them, from the raw group numbers in
     * order: runs of two or more adjacent equal numbers become 1, 2, 3... in order of appearance,
     * and everything else -- a lone number, a number its neighbours do not share, no number at
     * all -- is null.
     *
     * So `[7, 7, 3, 3, 3]` is `[1, 1, 2, 2, 2]`, and `[1, null, 1]` is three ungrouped exercises:
     * the number only has to differ from its neighbours.
     */
    fun runs(numbers: List<Int?>): List<Int?> {
        val out = MutableList<Int?>(numbers.size) { null }
        var next = 1
        var i = 0
        while (i < numbers.size) {
            val g = groupOf(numbers[i])
            var j = i + 1
            if (g != null) while (j < numbers.size && groupOf(numbers[j]) == g) j++
            if (g != null && j - i >= 2) {
                for (k in i until j) out[k] = next
                next++
            }
            i = j
        }
        return out
    }

    /** The group each exercise is sent in, as the wire numbers them. */
    fun wireGroups(exercises: List<RoutineExercise>): List<Int?> = runs(exercises.map { it.group })

    /**
     * Exercises with their groups as the wire would number them: runs renumbered 1, 2, 3..., and a
     * group of one removed -- so storage never holds a group that would not be sent. Run on save,
     * on load and on restore.
     */
    fun regroup(exercises: List<RoutineExercise>): List<RoutineExercise> =
        exercises.zip(wireGroups(exercises)) { e, g -> if (e.group == g) e else e.copy(group = g) }

    /** Whether position [i] and the one after it are in the same group -- "Superset with next". */
    fun groupedWithNext(numbers: List<Int?>, i: Int): Boolean {
        if (i < 0 || i + 1 >= numbers.size) return false
        val a = groupOf(numbers[i]) ?: return false
        return groupOf(numbers[i + 1]) == a
    }

    /**
     * Join position [i] to the next, or split them. Joining takes the whole group the next exercise
     * is in, so "Superset with next" on the exercise before a superset makes a circuit of three,
     * and joining two groups makes one. Splitting keeps the exercises before the split in their
     * group and gives the rest one of their own, so splitting a circuit of three after its first
     * leaves a superset. Either way the result is as the wire would number it: a group left with
     * one member is gone. The last position has no next, and asking changes nothing.
     */
    fun settingGroupedWithNext(numbers: List<Int?>, i: Int, on: Boolean): List<Int?> {
        if (i < 0 || i + 1 >= numbers.size) return numbers
        val list = runs(numbers).toMutableList()
        val a = list[i]
        val b = list[i + 1]
        val fresh = (list.filterNotNull().maxOrNull() ?: 0) + 1
        if (on) {
            if (a != null && a == b) return list
            val target = a ?: b ?: fresh
            list[i] = target
            list[i + 1] = target
            var j = i + 2
            while (b != null && j < list.size && list[j] == b) { list[j] = target; j++ }
        } else {
            if (a == null || a != b) return list
            var j = i + 1
            while (j < list.size && list[j] == a) { list[j] = fresh; j++ }
        }
        return runs(list)
    }

    /** Spreadsheet letters: 0 is A, 25 Z, 26 AA. A day with 27 groups is not likely, but a label must never be blank. */
    fun letters(n: Int): String {
        val out = StringBuilder()
        var x = n + 1
        while (x > 0) {
            out.insert(0, ('A' + (x - 1) % 26))
            x = (x - 1) / 26
        }
        return out.toString()
    }

    /**
     * What a grouped exercise is labelled: [group] "Superset A" / "Circuit B", [member] "A1",
     * [first] whether it is the group's first exercise (where a list names the group once).
     */
    data class GroupLabel(val group: String, val member: String, val first: Boolean, val size: Int) {
        /** "Superset A · A1", the editor's line over a grouped exercise. */
        val text: String get() = "$group · $member"
    }

    /**
     * Each position's label, or null when it is not grouped. Two exercises are a superset and
     * three or more a circuit; the letters run A, B, C... within the workout and the members are
     * numbered from 1. LIFT's labels, so a coach and their client read the same words.
     */
    fun labels(numbers: List<Int?>): List<GroupLabel?> {
        val groups = runs(numbers)
        val sizes = groups.filterNotNull().groupingBy { it }.eachCount()
        val seen = mutableMapOf<Int, Int>()
        return groups.map { g ->
            if (g == null) return@map null
            val letter = letters(g - 1)
            val n = (seen[g] ?: 0) + 1
            seen[g] = n
            val size = sizes[g] ?: 0
            GroupLabel(group = (if (size >= 3) "Circuit " else "Superset ") + letter,
                member = letter + n, first = n == 1, size = size)
        }
    }

    /** [labels] over a workout's exercises (Coach web's `groupLabels`). */
    fun groupLabels(exercises: List<RoutineExercise>): List<GroupLabel?> = labels(exercises.map { it.group })

    /** An exercise's sets with the first one's kind removed: there is no set before it to follow. */
    fun withoutFirstKind(sets: List<PrescribedSet>): List<PrescribedSet> =
        if (sets.firstOrNull()?.kind == null) sets else listOf(sets[0].copy(kind = null)) + sets.drop(1)

    /**
     * Whether the per-set Straight / Drop / Rest-pause control shows: once a set after the first
     * has a kind, or once the coach asked for it with "Drop / rest-pause" -- so an editor for
     * straight sets looks exactly as it always did.
     */
    fun showsKinds(exercise: RoutineExercise, asked: Boolean): Boolean =
        asked || exercise.sets.drop(1).any { it.kind != null }
}
