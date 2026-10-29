package com.dugcanlift.coach.data

import com.dugcanlift.kit.DayKey
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * A client's week on paper: the days that have something booked, each
 * workout's exercises and what they ask for, read the way this app's own
 * workout cards read them ([PrescriptionSides.summary]). For the client who
 * wants it printed or kept as a PDF rather than imported into LIFT.
 *
 * A port of Coach iOS's `PlanPrintout`, line for line, and the same lines
 * Coach web prints: the client's name, "Week of …" with "· from <coach>" when
 * the coach has given a name, then each booked day, its workouts, and one line
 * per exercise with its note under it.
 *
 * **Printing is not a send.** Nothing is recorded ([SentPlan] stays as it
 * was), because a printout is not a plan the client's app received.
 *
 * The lines are a value with no Android in them, so `PlanPrintoutTest` pins
 * what is on the page; [com.dugcanlift.coach.ui.PlanPrintoutDocument] only
 * lays them out, through [paginate], which is pure for the same reason.
 */
object PlanPrintout {

    data class Exercise(val name: String, val prescription: String, val note: String?)

    data class Workout(val name: String, val exercises: List<Exercise>)

    data class Day(val dayKey: String, val workouts: List<Workout>)

    /** How a line is set, the iPhone page's sizes: 20 bold, 10 gray, 14 and 12 semibold, 11, 10 gray, 11. */
    enum class Style { TITLE, SUBTITLE, DAY, WORKOUT, EXERCISE, NOTE, BODY }

    /** One block on the page, with the space above it in points. */
    data class Line(val text: String, val style: Style, val before: Float = 0f)

    /** Coach iOS's name for a booking whose template is gone, as the week grid says it. */
    const val REMOVED_WORKOUT = "Removed workout"

    /**
     * Days in order, only those with a booking, for one client. A booking whose
     * template has been deleted reads [REMOVED_WORKOUT] and lists nothing.
     *
     * @param sessions every session this device holds; filtered to [clientId] here.
     */
    fun days(
        week: PlanWeek,
        clientId: String,
        sessions: List<ScheduledSession>,
        routines: List<Routine>
    ): List<Day> {
        val byId = routines.associateBy { it.id }
        val mine = sessions.filter { it.clientId == clientId }
        return week.days.mapNotNull { day ->
            val booked = mine.filter { it.dayKey == day }
            if (booked.isEmpty()) return@mapNotNull null
            Day(day, booked.map { session ->
                val routine = byId[session.routineId] ?: return@map Workout(REMOVED_WORKOUT, emptyList())
                Workout(
                    // The week grid's own name for a routine saved without one.
                    routine.name.ifBlank { "Untitled" },
                    routine.exercises.map { exercise ->
                        Exercise(
                            name = exerciseName(exercise),
                            prescription = PrescriptionSides.summary(exercise),
                            note = exercise.note?.takeIf { it.isNotBlank() }
                        )
                    }
                )
            })
        }
    }

    /**
     * "Back Squat (Barbell)": the name, and the equipment with each word's first
     * letter upper-cased -- Coach iOS's `RoutineExercise.displayName`.
     */
    fun exerciseName(exercise: RoutineExercise): String {
        val equipment = exercise.equipment.trim()
        if (equipment.isEmpty()) return exercise.name
        val titled = equipment.split(" ").joinToString(" ") { word ->
            word.replaceFirstChar { it.titlecase(Locale.ROOT) }
        }
        return "${exercise.name} ($titled)"
    }

    /**
     * Everything the page says, in order. [dayTitle] is passed in so the words
     * can be pinned under one locale while the app formats in the reader's.
     */
    fun lines(
        clientName: String,
        weekStart: String,
        coachName: String?,
        days: List<Day>,
        dayTitle: (String) -> String
    ): List<Line> {
        val out = mutableListOf<Line>()
        out += Line(clientName.ifBlank { "Plan" }, Style.TITLE)
        val coach = coachName?.trim()?.takeIf { it.isNotEmpty() }
        out += Line("Week of ${dayTitle(weekStart)}" + (coach?.let { " · from $it" } ?: ""), Style.SUBTITLE)
        if (days.isEmpty()) out += Line("Nothing is booked this week.", Style.BODY, before = 12f)
        for (day in days) {
            out += Line(dayTitle(day.dayKey), Style.DAY, before = 16f)
            for (workout in day.workouts) {
                out += Line(workout.name, Style.WORKOUT, before = 6f)
                for (exercise in workout.exercises) {
                    out += Line("${exercise.name}: ${exercise.prescription}", Style.EXERCISE, before = 2f)
                    exercise.note?.let { out += Line(it, Style.NOTE) }
                }
            }
        }
        return out
    }

    /**
     * "Mon, Oct 26" in the reader's locale: abbreviated weekday, day, abbreviated
     * month, from the locale's own best pattern for that skeleton. Coach iOS
     * formats `.weekday(.abbreviated).day().month(.abbreviated)` the same way.
     *
     * @param pattern the locale's pattern for "EEEdMMM" -- on a device,
     *   `android.text.format.DateFormat.getBestDateTimePattern(locale, "EEEdMMM")`.
     */
    fun dayTitle(dayKey: String, locale: Locale, pattern: String): String =
        DayKey.parse(dayKey)?.let { DateTimeFormatter.ofPattern(pattern, locale).format(it) } ?: dayKey

    /** "Jordan Reyes – week of 2026-10-26.pdf", with what a file name cannot hold taken out. */
    fun fileName(clientName: String, weekStart: String): String {
        val safe = clientName.filterNot { it in "/\\:?*\"<>|" }
        return "${safe.ifEmpty { "Plan" }} – week of $weekStart.pdf"
    }

    // MARK: - The page

    /** US Letter, in points, which A4 printers also take without cropping a margin this wide. */
    const val PAGE_WIDTH = 612
    const val PAGE_HEIGHT = 792
    const val MARGIN = 54f

    /**
     * Which blocks go on which page: a block that does not fit below the last
     * one starts a new page, and the space above a block is dropped at the top
     * of a page. A block taller than a whole page still gets a page of its own
     * rather than an empty one before it.
     *
     * @param heights each block's measured text height, without its space above.
     * @param before each block's space above it.
     * @return the indices of the blocks on each page, in order; one empty page
     *   when there are no blocks.
     */
    fun paginate(
        heights: List<Float>,
        before: List<Float>,
        pageHeight: Float = PAGE_HEIGHT.toFloat(),
        margin: Float = MARGIN
    ): List<List<Int>> {
        val pages = mutableListOf(mutableListOf<Int>())
        var y = margin
        heights.forEachIndexed { index, height ->
            val space = if (pages.last().isEmpty()) 0f else before.getOrElse(index) { 0f }
            if (pages.last().isNotEmpty() && y + space + height > pageHeight - margin) {
                pages += mutableListOf<Int>()
                y = margin
                pages.last() += index
                y += height
            } else {
                pages.last() += index
                y += space + height
            }
        }
        return pages
    }
}
