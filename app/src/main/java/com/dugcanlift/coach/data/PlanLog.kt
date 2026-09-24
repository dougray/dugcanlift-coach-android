package com.dugcanlift.coach.data

import com.dugcanlift.kit.DayKey
import com.dugcanlift.kit.trimZeros
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToLong
import org.json.JSONArray
import org.json.JSONObject

/**
 * What you booked, and what they logged.
 *
 * Coach holds both halves -- it wrote the plan and it decoded the log -- and until now joined them
 * nowhere, so a coach could not see that Friday never happened. This is the join; [SentPlans] is
 * the record that makes it possible.
 *
 * **Counting is allowed; grading is not.** This says how many days were booked and how many were
 * logged, side by side, and stops. There is no score, no percentage, no colour on an absence, no
 * roster column, nothing carried across weeks and nothing comparing one client to another. The
 * words are `not logged`, never "missed" or "skipped": a client may have trained and not sent,
 * been ill, or been told to rest, and Coach cannot tell those apart. `outside the log they sent`
 * is a fourth state and exists so the third is never claimed wrongly. The word "adherence" never
 * reaches a screen. [lines] exists so both of those are testable as strings.
 *
 * **Training is compared; meals are not matched.** A booked meal is a recipe in a slot on a day.
 * What comes back is a day's food entries -- free-typed foods, barcode scans, a recipe logged as a
 * meal -- with no id joining them to anything, names drawn from the client's own food dictionary,
 * and itemisation a choice the client makes per send. Coach therefore says what it booked and what
 * the log holds at that meal, and never that the two are the same dish. Matching a booked recipe to
 * a logged entry by name would be right most of the time, and the times it was wrong it would tell
 * a coach their client ate something they did not -- the one error this card exists to avoid.
 * [MEAL_NOTE] says so on screen, once, permanently.
 *
 * **Nothing new travels.** SHARE-FORMAT and PLAN-FORMAT are unchanged; this works from what Coach
 * sent and the log the client already chose to send, so it reads every LIFT build in the field,
 * including ones that will never update, and one a client never opened. The cost, stated: a
 * session lifted the day after it was booked is a booked day with nothing logged plus a session of
 * its own. They sit next to each other on screen where a coach can read what happened, and Coach
 * claims no connection between them -- that join is the trainer's to make.
 *
 * **A port of Coach web's `coach/plan-log.js`, function for function**, the way [SideBalance] is a
 * port of `sides.js` and [PrescriptionSides] of `prescriptions.js`. It reuses
 * [PrescriptionSides.targets] for what a prescription asks for per side rather than repeating it.
 * A rule changes there first and is ported again; `coach/fixtures/plan-log-expected.json` is the
 * same file all three Coach builds check their port against.
 *
 * Pure and free of Compose so it can be tested directly, for the reason [ClientRemoval] is: this
 * decides whether a coach is told something true.
 */
object PlanLog {

    /* ---------------- dates ---------------- */

    private fun parseKey(key: String): LocalDate? = DayKey.parse(key)

    private fun shiftKey(key: String, days: Int): String =
        runCatching { DayKey.adding(days, key) }.getOrDefault(key)

    /**
     * "Oct", in the reader's own language. Written day-then-month by hand rather than through a
     * localised date format, because "12–18 Oct" has to read as a range and a US pattern would put
     * the month in the middle of it -- web's own note, and its own behaviour.
     */
    private fun monthName(key: String): String =
        parseKey(key)?.month?.getDisplayName(TextStyle.SHORT, Locale.getDefault()).orEmpty()

    private fun weekdayName(key: String): String =
        parseKey(key)?.dayOfWeek?.getDisplayName(TextStyle.SHORT, Locale.getDefault()).orEmpty()

    private fun dayOf(key: String): Int = parseKey(key)?.dayOfMonth ?: 0

    /**
     * "Mon 13 Oct". The month is on the head line above it too, and it is here as well on purpose
     * (Doug, 2026-09-23): a day row is read on its own, and a bare "Mon 13" in a card that can
     * hold several sends is a date a coach has to look up. Every day row carries it, in all four
     * states.
     */
    fun dayLabel(key: String): String = "${weekdayName(key)} ${dayMonth(key)}"

    /** "13 Oct" -- for the by-lift view, where rows cross weeks. */
    fun dayMonth(key: String): String = "${dayOf(key)} ${monthName(key)}"

    /** "12–18 Oct", "28 Sep–4 Oct", "12 Oct" for a single day. */
    fun rangeText(from: String, to: String): String {
        if (from == to) return dayMonth(from)
        val a = parseKey(from)
        val b = parseKey(to)
        if (a != null && b != null && a.year == b.year && a.month == b.month) {
            return "${dayOf(from)}–${dayMonth(to)}"
        }
        return "${dayMonth(from)}–${dayMonth(to)}"
    }

    private fun plural(n: Int, one: String, many: String): String = "$n ${if (n == 1) one else many}"

    /**
     * The same for a count that is not whole. A meal's `q` is a servings figure, and half a serving
     * is a thing a coach books: "1.5 servings", and "1 serving" for exactly one, as
     * `JSON.stringify` and Coach web's own `plural` both print it.
     */
    private fun plural(n: Double, one: String, many: String): String =
        "${n.trimZeros()} ${if (n == 1.0) one else many}"

    /* ---------------- what a plan and a log are, in one shape ---------------- */

    /**
     * One set, asked or logged. PLAN-FORMAT's set tuple and SHARE-FORMAT's are deliberately the
     * same six fields "so nothing has to be transposed to compare what was asked for against what
     * was done", and this is the shape that was for.
     *
     * Weights are **pounds** on both sides: the stored payload's and the wire's. Never a routine's
     * kilograms -- see [setText].
     */
    data class PlanSet(
        val weightLb: Double? = null,
        val reps: Int? = null,
        val rpe: Double? = null,
        val durationSec: Double? = null,
        val distanceM: Double? = null,
        val side: SetSide? = null
    )

    /** One lift, asked or logged, with its sets pooled. */
    data class PlanExercise(
        val key: String,
        val name: String,
        val equipment: String,
        val eachSide: Boolean,
        val sets: List<PlanSet>
    )

    /**
     * One day a payload books. [name] is the workout's, or the names joined when it books two.
     *
     * [workout] is whether this day booked training at all, which is not the same as having
     * exercises: `r`/`m` and `w`/`k` are independent (PLAN-FORMAT), so a day may book meals with no
     * training, training with no meals, or both. A day that booked no training gets no training
     * verdict -- "not logged" against a day nobody was asked to train would be Coach inventing a
     * booking to hold against them.
     */
    data class Booking(
        val date: String,
        val name: String,
        val workout: Boolean,
        val exercises: List<PlanExercise>,
        val meals: List<MealBooking>
    )

    /**
     * PLAN-FORMAT's `m.s`: 0 breakfast, 1 lunch, 2 dinner, 3 snack. The same four slots, in the
     * same order, that SHARE-FORMAT's `f` meal position indexes -- which is the only reason a
     * booked slot and a logged entry can be put on the same line at all. Coach web compares the
     * word its importer expanded the index into; here both sides are still the index.
     */
    val MEAL_SLOTS = listOf("Breakfast", "Lunch", "Dinner", "Snack")

    /**
     * One booked meal, as Coach's own record of it: the slot it was booked into, the dish, and how
     * much of it. All three are what the coach wrote, so all three can be said without reservation.
     *
     * Macros are deliberately not here -- see [MEAL_NOTE]. The recipe's own figures are in the
     * payload, and a planned calorie beside a logged one is a coach's target measured against,
     * which is the line this card does not cross. Nothing in this half carries a weight either, so
     * the pounds-on-the-wire care [setText] takes has no second place to go wrong: servings are a
     * count in no unit at all.
     *
     * [logged] is what the log holds at that slot, or null when nothing can be said per slot.
     */
    data class MealBooking(
        /** The wire's slot index, or -1 for one this build cannot name. */
        val slot: Int,
        val slotLabel: String?,
        val name: String,
        val servings: Double,
        val logged: String?
    ) {
        val title: String
            get() = listOfNotNull(slotLabel, name, plural(servings, "serving", "servings"))
                .joinToString(" · ")
    }

    fun exerciseKey(name: String?, equipment: String?): String =
        (name.orEmpty().trim() + "|" + equipment.orEmpty().trim()).lowercase(Locale.US)

    private fun nameKey(name: String?): String = name.orEmpty().trim().lowercase(Locale.US)

    /**
     * Bits 1-2 of SHARE-FORMAT's flags byte, **masked, never compared**: `3` is never written and
     * reads as both, and bit 0 (warmup) is not ours. `flags == 2` would be a correct left test
     * only until a left-side warmup arrives as `3`.
     */
    private fun sideFromFlags(raw: Any?): SetSide? {
        val flags = (raw as? Number)?.toInt() ?: return null
        return when ((flags shr 1) and 3) {
            1 -> SetSide.LEFT
            2 -> SetSide.RIGHT
            else -> null
        }
    }

    private fun tupleValue(t: JSONArray, i: Int): Double? =
        (t.opt(i) as? Number)?.toDouble()?.takeIf { it.isFinite() }

    /** A plan's set tuple back into [PlanSet] -- web's `decodeSet`. */
    private fun decodeSet(tuple: Any?): PlanSet {
        val t = tuple as? JSONArray ?: JSONArray()
        return PlanSet(
            weightLb = tupleValue(t, 0),
            reps = tupleValue(t, 1)?.toInt(),
            rpe = tupleValue(t, 2),
            durationSec = tupleValue(t, 3),
            distanceM = tupleValue(t, 4),
            side = sideFromFlags(t.opt(5))
        )
    }

    /** `b: 1` is each side. Anything else -- absent, 0, junk -- is a two-sided lift. */
    private fun decodeExercise(wire: JSONObject): PlanExercise {
        val name = wire.optStringOrNull("n") ?: "Exercise"
        val equipment = wire.optStringOrNull("q").orEmpty()
        val sets = wire.optJSONArray("s")?.let { a -> (0 until a.length()).map { decodeSet(a.opt(it)) } }.orEmpty()
        return PlanExercise(
            key = exerciseKey(name, equipment),
            name = name,
            equipment = equipment,
            eachSide = (wire.opt("b") as? Number)?.toInt() == 1,
            sets = sets
        )
    }

    /**
     * Every day a payload books.
     *
     * Training pools where a payload books two sessions on one date -- SHARE-FORMAT gives a day one
     * `w` array, so the log has already merged two sessions into one before Coach sees it, and the
     * asked side has to be read the same way. Meals do not pool: two dishes at one dinner are two
     * dishes, and a coach who booked both wants to see both.
     *
     * A library send -- `r` or `w` with nothing scheduled -- books no day at all, and neither does
     * a picks-only send.
     */
    fun bookingsIn(payload: JSONObject): List<Booking> {
        val workouts = payload.optJSONArray("w") ?: JSONArray()
        val booked = payload.optJSONArray("k") ?: JSONArray()
        val recipes = payload.optJSONArray("r") ?: JSONArray()
        val meals = payload.optJSONArray("m") ?: JSONArray()
        val names = LinkedHashMap<String, MutableList<String>>()
        val exercises = LinkedHashMap<String, MutableList<PlanExercise>>()
        val dayMeals = LinkedHashMap<String, MutableList<MealBooking>>()
        val dates = LinkedHashSet<String>()
        val trained = mutableSetOf<String>()

        (0 until booked.length()).forEach { i ->
            val entry = booked.opt(i) as? JSONObject ?: return@forEach
            val date = entry.optStringOrNull("d") ?: return@forEach
            val index = (entry.opt("x") as? Number)?.toInt() ?: return@forEach
            val wire = workouts.opt(index) as? JSONObject ?: return@forEach
            val name = wire.optStringOrNull("n") ?: "Session"
            dates += date
            trained += date
            names.getOrPut(date) { mutableListOf() }.add(name)
            val list = exercises.getOrPut(date) { mutableListOf() }
            wire.optJSONArray("e")?.let { a ->
                (0 until a.length()).forEach { j -> (a.opt(j) as? JSONObject)?.let { list.add(decodeExercise(it)) } }
            }
        }

        (0 until meals.length()).forEach { i ->
            val entry = meals.opt(i) as? JSONObject ?: return@forEach
            val date = entry.optStringOrNull("d") ?: return@forEach
            // A booking whose recipe is not inlined indexes nothing, exactly as a session whose `x`
            // misses `w` does. Skipped, not guessed at -- a stale index is the wrong dinner.
            val index = (entry.opt("x") as? Number)?.toInt() ?: return@forEach
            val recipe = recipes.opt(index) as? JSONObject ?: return@forEach
            val slot = (entry.opt("s") as? Number)?.toInt() ?: -1
            val label = MEAL_SLOTS.getOrNull(slot)
            val servings = (entry.opt("q") as? Number)?.toDouble()?.takeIf { it > 0 && it.isFinite() } ?: 1.0
            dates += date
            dayMeals.getOrPut(date) { mutableListOf() }.add(
                MealBooking(
                    slot = if (label == null) -1 else slot,
                    slotLabel = label,
                    name = recipe.optStringOrNull("n") ?: "Recipe",
                    servings = servings,
                    logged = null
                )
            )
        }

        return dates.sorted().map { date ->
            Booking(
                date = date,
                name = names[date].orEmpty().joinToString(" · "),
                workout = date in trained,
                exercises = pool(exercises[date].orEmpty()),
                // Breakfast, lunch, dinner, snack -- the order a day is eaten in, not the order the
                // coach happened to book them. A slot Coach cannot read sorts last rather than
                // being dropped.
                meals = dayMeals[date].orEmpty().sortedBy { if (it.slot < 0) 9 else it.slot }
            )
        }
    }

    /**
     * Exercises pooled by `name|equipment`, keeping first-seen order. The same key prescribed
     * twice in a day is one prescription of more sets. Each side is a property of the lift, not of
     * one booking of it: if either says each side, the sets pooled under it are each side.
     */
    private fun pool(list: List<PlanExercise>): List<PlanExercise> {
        val order = LinkedHashMap<String, PlanExercise>()
        list.forEach { ex ->
            val current = order[ex.key]
            order[ex.key] = if (current == null) ex
            else current.copy(eachSide = current.eachSide || ex.eachSide, sets = current.sets + ex.sets)
        }
        return order.values.toList()
    }

    /**
     * A logged day's lifts, pooled the same way, warmups dropped.
     *
     * Warmups are excluded on both sides -- and on this side they are already masked out of the
     * flags byte by the importer, because a left-side warmup arrives as `3` and `flags == 1` would
     * read it as a working set. A lift whose sets were all warmups is still a lift that was
     * touched, so it still pairs; it is [alsoLogged] that drops it for having no working set.
     */
    fun loggedIn(day: TrainingDay?): List<PlanExercise> {
        val sets = day?.sets.orEmpty()
        val order = LinkedHashMap<String, PlanExercise>()
        sets.forEach { s ->
            val key = exerciseKey(s.exerciseName, s.equipment)
            val current = order[key] ?: PlanExercise(key, s.exerciseName, s.equipment.orEmpty(), false, emptyList())
            order[key] = if (s.isWarmup) current else current.copy(
                sets = current.sets + PlanSet(s.weightLb, s.reps, s.rpe, s.durationSec, s.distanceMeters, s.side)
            )
        }
        return order.values.toList()
    }

    /* ---------------- how a set reads ---------------- */

    private const val LB_PER_KG_DISPLAY = 2.2046226218

    private fun fromLb(lb: Double, unit: String): Double = if (unit == "kg") lb / LB_PER_KG_DISPLAY else lb

    private fun num(value: Double): String = NumberFormat.getIntegerInstance().format(Math.round(value))

    private fun pad2(n: Long): String = n.toString().padStart(2, '0')

    /**
     * One set, asked or logged, in the same shape.
     *
     * **Both rows come from pounds**: the stored payload's and the wire's, converted once here.
     * Never from a routine, which stores kilograms ([PrescribedSet.targetWeightKg]) -- an asked
     * row in kilograms above a logged row in pounds would be two units in one card, silently and
     * 2.2x wrong, and a coach reads these two rows against each other.
     *
     * Blank stays blank. `[null, 5]` is "5 reps", never "0 × 5".
     */
    fun setText(set: PlanSet, unit: String): String {
        val bits = mutableListOf<String>()
        val weight = set.weightLb
        val reps = set.reps
        when {
            weight != null && reps != null -> bits += "${num(fromLb(weight, unit))} × $reps"
            reps != null -> bits += "$reps reps"
            weight != null -> bits += "${num(fromLb(weight, unit))} $unit"
        }
        set.distanceM?.let { bits += "${num(it)} m" }
        set.durationSec?.let { seconds ->
            val total = seconds.roundToLong()
            val minutes = total / 60
            bits += if (minutes > 0) "$minutes:${pad2(total % 60)}" else "${total}s"
        }
        set.rpe?.let { bits += "@${it.trimZeros()}" }
        return bits.joinToString(" · ").ifEmpty { "as written" }
    }

    /** One group of sets on screen: "L" and its sets, or an unlabelled group when nothing is sided. */
    data class SetGroup(val label: String, val text: String)

    /**
     * A row of sets: the groups, and the clause that applies to all of them.
     *
     * [suffix] is a field rather than glued onto the last group, because "each side" is a clause on
     * the ask and not a set of its own -- a view that draws the groups and forgets the clause
     * prints a plan that asks for half of what it asks for.
     */
    data class SetRow(val label: String, val groups: List<SetGroup>, val suffix: String) {
        val text: String get() = groupsText(groups) + suffix
    }

    private val SERIES = listOf(SetSide.LEFT, SetSide.RIGHT, null)

    /**
     * Sets as one group per side -- "L 40 × 8 · 40 × 8 · 40 × 5" beside "R 40 × 8 · 40 × 8" -- or a
     * single unlabelled group when nothing is sided. Sets are listed, never paired one to one with
     * the asked row: if they did three of four sets, Coach cannot say which one they dropped, so
     * it does not.
     */
    fun setGroups(sets: List<PlanSet>, unit: String): List<SetGroup> {
        if (sets.none { it.side != null }) {
            if (sets.isEmpty()) return emptyList()
            return listOf(SetGroup("", sets.joinToString(" · ") { setText(it, unit) }))
        }
        return SERIES.mapNotNull { side ->
            val mine = sets.filter { it.side == side }
            if (mine.isEmpty()) null
            else SetGroup(side?.short ?: "Both", mine.joinToString(" · ") { setText(it, unit) })
        }
    }

    private fun groupsText(groups: List<SetGroup>): String =
        groups.joinToString("   ") { (if (it.label.isEmpty()) "" else it.label + " ") + it.text }

    /* ---------------- one exercise, asked against logged ---------------- */

    private fun title(ex: PlanExercise): String =
        if (ex.equipment.isBlank()) ex.name else "${ex.name} (${ex.equipment})"

    private fun equipmentWord(equipment: String): String = equipment.ifBlank { "no equipment" }

    /** What one lift says, asked above logged. [state] is `logged`, `notLogged` or `alsoLogged`. */
    data class ExerciseLines(
        val key: String,
        /** The lift on its own, for a heading that is about the lift and not about one day of it. */
        val lift: String,
        val title: String,
        val state: String,
        val substitution: String?,
        val sideLine: String?,
        val countLine: String?,
        val asked: SetRow?,
        val logged: SetRow?
    )

    /** A lift the log has and the plan does not: its name and how many working sets it carried. */
    data class AlsoLogged(val key: String, val title: String, val text: String)

    /**
     * The side counts LIFT already shows in its own header: "L 3/3 · R 2/3", the logged count over
     * what the prescription asks for. Over is shown as over -- "L 4/3", never capped. An each-side
     * exercise's ask is twice its tuples, which [PrescriptionSides.targets] already works out. An
     * exercise with no side on either half gets no line at all.
     *
     * Printed, not judged. Coach does no arithmetic on the difference between the two numbers;
     * "three reps short on the left every time" is what a coach reads off the two rows, not a
     * number this computes.
     */
    fun sideLine(asked: PlanExercise, logged: PlanExercise?): String? {
        val t = PrescriptionSides.targets(asked.eachSide, asked.sets.map { it.side })
        val sets = logged?.sets.orEmpty()
        val left = sets.count { it.side == SetSide.LEFT }
        val right = sets.count { it.side == SetSide.RIGHT }
        val both = sets.count { it.side == null }
        if (t.left == 0 && t.right == 0 && left == 0 && right == 0) return null
        var text = "L $left/${t.left} · R $right/${t.right}"
        // Sets logged with no side are still real work. Saying so beats leaving them out.
        if (both > 0) text += " · $both both"
        return text
    }

    /**
     * @param absentWord what a lift with nothing logged against it says: "not logged" on a day the
     *   client sent, "outside the log they sent" on a day they did not. The second exists so the
     *   first is never claimed wrongly.
     */
    fun pairLines(
        asked: PlanExercise,
        logged: PlanExercise?,
        unit: String,
        substituted: Boolean,
        absentWord: String
    ): ExerciseLines {
        val side = sideLine(asked, logged)
        val askedSets = asked.sets
        val loggedSets = logged?.sets.orEmpty()
        val lift = title(asked) + if (asked.eachSide) " · each side" else ""
        return ExerciseLines(
            key = asked.key,
            lift = lift,
            title = if (logged != null) lift else "$lift · $absentWord",
            state = if (logged != null) "logged" else "notLogged",
            substitution = if (substituted && logged != null)
                "Asked ${equipmentWord(asked.equipment)} · logged ${equipmentWord(logged.equipment)}" else null,
            sideLine = side,
            // How many were asked for and how many came back, when they differ and there is no
            // side line already saying it per side.
            countLine = if (logged != null && side == null && askedSets.size != loggedSets.size)
                "Asked ${plural(askedSets.size, "set", "sets")} · logged ${loggedSets.size}" else null,
            // A lift with nothing logged against it is one line and no rows: the day row above
            // already says the session was not logged, and repeating the prescription under every
            // lift of a missed day turns a fact into a recital of what someone did not do.
            asked = if (logged != null && askedSets.isNotEmpty())
                SetRow("Asked", setGroups(askedSets, unit), if (asked.eachSide) " each side" else "") else null,
            logged = if (logged != null) SetRow("Logged", setGroups(loggedSets, unit), "") else null
        )
    }

    private fun alsoLogged(ex: PlanExercise): AlsoLogged = AlsoLogged(
        key = ex.key,
        title = title(ex),
        text = "${title(ex)} · ${plural(ex.sets.size, "set", "sets")}"
    )

    private data class Joined(val exercises: List<ExerciseLines>, val alsoLogged: List<AlsoLogged>)

    /**
     * The prescribed and the logged lifts of one day, joined.
     *
     * Two passes, in this order, so an exact match always wins:
     *   1. name and equipment -- a cable pulldown and a machine pulldown are not the same lift and
     *      a coach prescribing one of them meant it.
     *   2. name alone, over what is left on each side: the equipment substitution, paired and
     *      labelled.
     * Never by position: a client who skips the second exercise would shift every pairing after it.
     */
    private fun joinExercises(asked: List<PlanExercise>, logged: List<PlanExercise>, unit: String): Joined {
        val remaining = logged.toMutableList()
        fun take(predicate: (PlanExercise) -> Boolean): PlanExercise? {
            val i = remaining.indexOfFirst(predicate)
            return if (i < 0) null else remaining.removeAt(i)
        }

        val matched = asked.map { ex -> take { it.key == ex.key } }.toMutableList()
        asked.forEachIndexed { i, ex ->
            if (matched[i] != null) return@forEachIndexed
            matched[i] = take { nameKey(it.name) == nameKey(ex.name) }
        }

        return Joined(
            // A pair whose keys differ was found on the second pass: same name, other equipment.
            exercises = asked.mapIndexed { i, ex ->
                val match = matched[i]
                pairLines(ex, match, unit, match != null && match.key != ex.key, "not logged")
            },
            // Working sets are the claim everywhere else here, so a lift nobody asked for that came
            // back as warmups alone is not "0 sets" on screen.
            alsoLogged = remaining.filter { it.sets.isNotEmpty() }.map(::alsoLogged)
        )
    }

    /* ---------------- the card ---------------- */

    const val FOOTER = "This is what you shared. Whether it arrived, and whether they opened it, " +
        "only they know."

    /**
     * The one line that keeps the meal rows honest, shown once under a card that has any,
     * permanently.
     *
     * A booked meal is a dish in a slot. A logged day is a list of foods, named out of the client's
     * own food dictionary, with no id joining the two. Coach therefore prints what it booked and
     * what the log holds at that slot, side by side, and stops -- the same move the day rows make
     * with `not logged` above `not booked`. The join is the trainer's, and this says so.
     */
    const val MEAL_NOTE = "A meal row says what the log holds at that meal. Whether it was this " +
        "dish, only they know."

    /**
     * How many days a send booked, what became of them, and how many meals it booked. Days and
     * meals, and nothing else: there is no figure here for meals eaten, because there is not one.
     *
     * [training] is how many of [booked] booked training at all, and it is what names what [logged]
     * counts: "logged 2" under "Booked 5 days" would read as two days of five when three of them
     * booked no training.
     */
    data class Counts(
        val booked: Int,
        val training: Int,
        val logged: Int,
        val notLogged: Int,
        val outside: Int,
        val other: Int,
        val meals: Int
    )

    /**
     * What a day's log can say about food at all, which is three different things and not two.
     *
     * - [ITEMS] the client itemised: Coach can see each entry and the meal it was stamped with, so
     *   it can say what the log holds at a slot.
     * - [TOTALS] the client sent the day's totals and not what was in them. Itemisation is a choice
     *   made per send (SHARE-FORMAT's `f` is optional), so this is a client's privacy choice and
     *   not an absence -- calling a booked dinner `not logged` here would contradict it.
     * - [NONE] nothing at all, including a day opened and left empty, which SHARE-FORMAT writes as
     *   `ft: [0,0,0,0,0]`.
     */
    private enum class FoodState { ITEMS, TOTALS, NONE }

    private data class DayFood(val state: FoodState, val items: List<ClientFoodEntry>)

    private fun foodIn(day: TrainingDay?): DayFood {
        val items = day?.foodEntries.orEmpty()
        if (items.isNotEmpty()) return DayFood(FoodState.ITEMS, items)
        val totals = listOf(
            day?.foodCalories, day?.foodProteinG, day?.foodFatG, day?.foodCarbsG, day?.foodFiberG
        )
        val any = totals.any { (it ?: 0.0) > 0.0 }
        return DayFood(if (any) FoodState.TOTALS else FoodState.NONE, emptyList())
    }

    /**
     * What Coach can see of a day's food, said once above that day's meal rows.
     *
     * It is here so `Nothing logged at lunch` cannot be read as `they ate nothing`: a day with
     * seven foods on it, none of them stamped lunch, says both facts one above the other.
     * `not tied to a meal` is the same guard for an entry the client's app recorded with no slot.
     */
    private fun foodContext(food: DayFood): String = when (food.state) {
        FoodState.TOTALS -> "Food logged that day, not itemised"
        FoodState.NONE -> "No food logged that day"
        FoodState.ITEMS -> {
            val loose = food.items.count { MEAL_SLOTS.getOrNull(it.meal) == null }
            plural(food.items.size, "food", "foods") + " logged that day" +
                if (loose > 0) " · $loose not tied to a meal" else ""
        }
    }

    /**
     * What the log holds at one slot -- the entries the client stamped with it, named as they wrote
     * them and in the order they logged them.
     *
     * Names are printed, never compared. A coach reading `Beef Chilli` under a booked `Beef Chilli`
     * has made the join themselves, from the same two facts Coach has, and can see when it is not
     * there; Coach asserting the match would be right most nights and, the nights it was wrong,
     * would tell a coach their client ate something they did not.
     *
     * Null when nothing can be said per slot: a day Coach cannot see inside, or a booking whose
     * slot the payload did not give.
     */
    private fun slotLine(meal: MealBooking, food: DayFood): String? {
        if (food.state != FoodState.ITEMS || meal.slotLabel == null) return null
        val where = meal.slotLabel.lowercase(Locale.US)
        val mine = food.items.filter { it.meal == meal.slot }
        if (mine.isEmpty()) return "Nothing logged at $where"
        return "Logged at $where · " + mine.joinToString(" · ") { it.foodName }
    }

    /**
     * One day of a send. [state] is `logged`, `notLogged`, `outside`, `notBooked` or `meals` --
     * the last being a day that booked food and no training, which gets no training verdict at all.
     */
    data class DayRow(
        val key: String,
        val state: String,
        val name: String,
        val text: String,
        val exercises: List<ExerciseLines>,
        val alsoLogged: List<AlsoLogged>,
        /** The meals this day booked, each with what the log holds at that slot. */
        val meals: List<MealBooking>,
        /**
         * What the day's log can say about food at all, said once above the meal rows so
         * "Nothing logged at lunch" is read beside what the day's log does hold. Null when there
         * are no meal rows for it to be about.
         */
        val foodContext: String?,
        /**
         * What this day booked, whatever became of it. The day view does not draw these on a day
         * nobody logged -- the row above already says so. The by-lift view does need them: a lift
         * shown only on the weeks it was logged reads steadier than it was.
         */
        val booked: List<ExerciseLines>
    )

    /** One send: the days it booked, and the days logged beside them. */
    data class Group(
        val id: String,
        val sentAt: Long,
        val from: String,
        val to: String,
        val range: String,
        val counts: Counts,
        val head: String,
        val days: List<DayRow>
    )

    /** One lift across the sent weeks, its asked and logged rows stacked by date. */
    data class LiftEntry(val key: String, val whenText: String, val exercise: ExerciseLines)

    data class LiftRows(val key: String, val title: String, val entries: List<LiftEntry>)

    data class Result(
        val groups: List<Group>,
        val byLift: List<LiftRows>,
        /** [MEAL_NOTE], but only when there is a meal row for it to be about. */
        val mealFooter: String?,
        val footer: String
    )

    private fun hasTraining(day: TrainingDay?): Boolean = !day?.sets.isNullOrEmpty()

    /**
     * Whether a date falls inside the window the client actually sent. A day with no log is not the
     * same as a day the client did not send, and one of those is "not logged" while the other is
     * "we do not know".
     */
    private fun covered(key: String, coverage: Pair<String, String>?): Boolean {
        if (coverage == null || coverage.first.isEmpty() || coverage.second.isEmpty()) return false
        return key >= coverage.first && key <= coverage.second
    }

    /**
     * The head of one send.
     *
     * A send with no meals reads exactly as it always has -- "Booked 4 days, 12–17 Oct · logged 2"
     * -- so a training week's line, and the fixture that pins it, are unchanged.
     *
     * A send with meals says how many it booked, and then names what the `logged` figure counts.
     * The meal count carries no logged figure beside it, because there is not one -- see
     * [MEAL_NOTE].
     */
    private fun headLine(range: String, counts: Counts): String {
        var head = "Booked ${plural(counts.booked, "day", "days")}, $range"
        if (counts.meals > 0) head += " · ${plural(counts.meals, "meal booked", "meals booked")}"
        if (counts.outside == counts.booked) return "$head · no log covering them"
        if (counts.meals == 0) head += " · logged ${counts.logged}"
        else if (counts.training > 0) {
            head += " · ${plural(counts.training, "training day", "training days")}, ${counts.logged} logged"
        }
        if (counts.outside > 0) head += " · ${counts.outside} outside the log they sent"
        if (counts.other > 0) head += " · ${plural(counts.other, "other day logged", "other days logged")}"
        return head
    }

    /**
     * The sent plans against the log.
     *
     * Days join on client and date, and nothing else -- no window. A client who does Friday's work
     * on Saturday is the case everyone asks about, and the honest answer is that Coach cannot know
     * they did: the card puts the two facts on the same seven days and says nothing about cause.
     *
     * @param sentPlans every row this device holds; this client's are picked out here.
     * @param days the client's stored days, keyed by day key.
     * @param coverage the window the client has sent, as [Client.coveredFrom] to [Client.coveredTo].
     * @param unit the client's display unit; both rows convert from pounds through it.
     * @param weeks how far back to look -- eight, the Weeks table's own window, so the card and the
     *   table look at the same stretch.
     */
    fun compare(
        clientId: String,
        sentPlans: List<SentPlan>,
        days: Map<String, TrainingDay>,
        coverage: Pair<String, String>?,
        unit: String,
        today: String = DayKey.today(),
        weeks: Int = 8
    ): Result {
        val display = if (unit == "kg") "kg" else "lb"
        val from = shiftKey(today, -(7 * weeks - 1))

        val groups = mutableListOf<Group>()
        SentPlans.forClient(sentPlans, clientId).forEach { row ->
            val booked = bookingsIn(row.payload()).filter { it.date in from..today }
            if (booked.isEmpty()) return@forEach

            val first = booked.first().date
            val last = booked.last().date
            val bookedDates = booked.map { it.date }.toSet()

            var training = 0
            var logged = 0
            var notLogged = 0
            var outside = 0
            var other = 0
            var mealCount = 0
            val rows = mutableListOf<DayRow>()

            booked.forEach { booking ->
                val day = days[booking.date]
                val isCovered = covered(booking.date, coverage)
                if (booking.workout) training += 1
                mealCount += booking.meals.size
                val state = when {
                    !isCovered -> { outside += 1; "outside" }
                    // A day that booked no training gets no training verdict. `not logged` against
                    // a day nobody was asked to train would be Coach inventing a booking to hold
                    // against them.
                    !booking.workout -> "meals"
                    hasTraining(day) -> { logged += 1; "logged" }
                    else -> { notLogged += 1; "notLogged" }
                }
                val word = when (state) {
                    "logged" -> "logged"
                    "notLogged" -> "not logged"
                    "outside" -> "outside the log they sent"
                    else -> ""
                }
                val joined = if (state == "logged") joinExercises(booking.exercises, loggedIn(day), display)
                else Joined(emptyList(), emptyList())
                val food = if (isCovered) foodIn(day) else null
                val meals = booking.meals.map { meal ->
                    meal.copy(logged = food?.let { slotLine(meal, it) })
                }
                val mealsClause = if (meals.isEmpty()) ""
                else plural(meals.size, "meal booked", "meals booked")
                // The training word hugs the session it judges; the meal clause follows it. A day
                // that booked only meals has no session for it to hug, so what is left --
                // `outside the log they sent`, or nothing -- goes last instead.
                val head = if (booking.name.isNotEmpty())
                    listOf(dayLabel(booking.date), booking.name, word, mealsClause)
                else listOf(dayLabel(booking.date), mealsClause, word)
                rows += DayRow(
                    key = booking.date,
                    state = state,
                    name = booking.name,
                    text = head.filter { it.isNotEmpty() }.joinToString(" · "),
                    exercises = joined.exercises,
                    alsoLogged = joined.alsoLogged,
                    meals = meals,
                    foodContext = if (meals.isNotEmpty() && food != null) foodContext(food) else null,
                    booked = if (state == "logged") emptyList()
                    else booking.exercises.map { pairLines(it, null, display, false, word) }
                )
            }

            // A day inside this send's span that was trained and not booked. Shown beside the
            // bookings, saying nothing about cause: a session lifted the day after the one it was
            // booked for looks exactly like this, and so does a session the client added themselves.
            //
            // Only when this send booked training at all. A food plan booked no session for a
            // logged one to be a displaced version of, and listing a client's own training under it
            // as `not booked` would be Coach holding up work nobody set out to book.
            if (training > 0) days.keys.sorted().forEach { key ->
                if (key < first || key > last || key in bookedDates) return@forEach
                val day = days[key]
                if (!hasTraining(day)) return@forEach
                other += 1
                rows += DayRow(
                    key = key,
                    state = "notBooked",
                    name = day?.sessionName.orEmpty(),
                    text = listOf(dayLabel(key), day?.sessionName.orEmpty(), "not booked")
                        .filter { it.isNotEmpty() }.joinToString(" · "),
                    exercises = emptyList(),
                    alsoLogged = loggedIn(day).filter { it.sets.isNotEmpty() }.map(::alsoLogged),
                    meals = emptyList(),
                    foodContext = null,
                    booked = emptyList()
                )
            }
            rows.sortBy { it.key }

            val counts = Counts(booked.size, training, logged, notLogged, outside, other, mealCount)
            groups += Group(
                id = row.id,
                sentAt = row.sentAt,
                from = first,
                to = last,
                range = rangeText(first, last),
                counts = counts,
                head = headLine(rangeText(first, last), counts),
                days = rows
            )
        }

        // Only when there is a meal row for it to be about. A training-only card is byte for byte
        // what it was.
        val anyMeal = groups.any { it.counts.meals > 0 }
        return Result(groups, byLift(groups), if (anyMeal) MEAL_NOTE else null, FOOTER)
    }

    /**
     * The same lines grouped the other way: each prescribed lift across the sent weeks, its asked
     * and logged rows stacked by date. Same rules, same strings -- this is a regrouping of what
     * [compare] already decided, not a second opinion about it, and it still carries nothing across
     * weeks beyond putting the days under one heading.
     */
    private fun byLift(groups: List<Group>): List<LiftRows> {
        val byKey = LinkedHashMap<String, MutableList<LiftEntry>>()
        groups.forEach { group ->
            group.days.forEach { day ->
                (day.exercises + day.booked).forEach { ex ->
                    byKey.getOrPut(ex.key) { mutableListOf() }
                        .add(LiftEntry(day.key, dayMonth(day.key), ex))
                }
            }
        }
        return byKey.map { (key, entries) ->
            val sorted = entries.sortedBy { it.key }
            // The heading is the lift, without the per-day clause: whether one day of it was logged
            // belongs to that day and not to the lift.
            LiftRows(key, sorted.first().exercise.lift, sorted)
        }
    }

    /**
     * Every sentence this card can produce, flattened, in the order a coach reads them. The
     * line-discipline tests run over this rather than over a screen, so a string that judges a
     * client fails a test the day it is written rather than the day someone notices it on screen.
     */
    fun lines(result: Result): List<String> {
        val out = mutableListOf<String>()
        result.groups.forEach { group ->
            out += group.head
            group.days.forEach { day ->
                out += day.text
                day.exercises.forEach { ex ->
                    out += ex.title
                    ex.sideLine?.let { out += it }
                    ex.countLine?.let { out += it }
                    ex.asked?.let { out += "${it.label} ${it.text}" }
                    ex.logged?.let { out += "${it.label} ${it.text}" }
                    // Under the pair, as it sits on screen: the substitution line says what the two
                    // rows above it are, and reads as nonsense above them.
                    ex.substitution?.let { out += it }
                }
                if (day.alsoLogged.isNotEmpty()) {
                    out += "Also logged"
                    day.alsoLogged.forEach { out += it.text }
                }
                // Meals last, under the training they sit beside, in the order a day is eaten.
                // Every sentence they can produce runs through here, so the line-discipline tests
                // cover them exactly as they cover a lift.
                if (day.meals.isNotEmpty()) {
                    out += "Meals"
                    day.foodContext?.let { out += it }
                    day.meals.forEach { meal ->
                        out += meal.title
                        meal.logged?.let { out += it }
                    }
                }
            }
        }
        // The other way round. The same lines under a lift's heading rather than a day's, so the
        // second view is pinned by the same tests as the first rather than being the one place a
        // sentence could slip through.
        if (result.byLift.isNotEmpty()) {
            out += "By lift"
            result.byLift.forEach { lift ->
                out += lift.title
                lift.entries.forEach { entry ->
                    out += entry.whenText
                    val ex = entry.exercise
                    if (ex.state != "logged") out += ex.title
                    ex.sideLine?.let { out += it }
                    ex.countLine?.let { out += it }
                    ex.asked?.let { out += "${it.label} ${it.text}" }
                    ex.logged?.let { out += "${it.label} ${it.text}" }
                    ex.substitution?.let { out += it }
                }
            }
        }
        if (out.isNotEmpty()) result.mealFooter?.let { out += it }
        if (out.isNotEmpty()) out += result.footer
        return out
    }
}
