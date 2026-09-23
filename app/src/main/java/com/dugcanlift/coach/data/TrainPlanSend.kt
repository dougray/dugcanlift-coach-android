package com.dugcanlift.coach.data

import com.dugcanlift.kit.DayKey

/**
 * The seven days a plan screen shows, and how to move between weeks.
 *
 * A port of Coach iOS's `PlanWeek` (and the same seven days Coach web's
 * `cookWeek()` lays out): day arithmetic through [DayKey], which goes through
 * `LocalDate`. Adding `7 * 86400` seconds repeats a day across a DST
 * fall-back, which would book two workouts onto one date and skip another.
 */
data class PlanWeek(val startDayKey: String) {

    val days: List<String>
        get() = (0 until 7).mapNotNull { runCatching { DayKey.adding(it, startDayKey) }.getOrNull() }

    fun advanced(weeks: Int): PlanWeek =
        runCatching { PlanWeek(DayKey.adding(weeks * 7, startDayKey)) }.getOrDefault(this)

    /** "This week" when it contains today, otherwise the span -- Coach iOS's label. */
    fun label(today: String = DayKey.today()): String {
        val all = days
        val first = all.firstOrNull() ?: return startDayKey
        if (all.contains(today)) return "This week"
        return "$first – ${all.last()}"
    }

    companion object {
        /** The week starting today, which is where both other Coach builds start. */
        fun current(today: String = DayKey.today()) = PlanWeek(today)
    }
}

/**
 * What a Send from Train actually sends: one client, one week, the templates
 * that week books and nothing else.
 *
 * A plain value with no Compose in it, for the reason `RosterLoader` is one --
 * this repo has no Compose test harness, and what a coach ships to a client is
 * not a thing to leave untested in a composable's lambda.
 *
 * ## What goes, and how it compares to the other two Coach builds
 *
 * - **One client and one week**, as Coach iOS's `TrainPlanView` scopes it: the
 *   bookings whose `dayKey` falls in the shown week, and only the routines
 *   those bookings use. Coach web's `encodePlan` sends every session a client
 *   has, ever, from a screen that shows one week; that is a deliberate
 *   difference, and the smaller send is the one a coach can see on screen
 *   before they send it.
 * - **Training only.** Coach web builds one link from either screen, carrying
 *   meals and training together; Coach iOS sends the training half from Train
 *   and the food half from Cook, and this app's Cook Send
 *   ([CookPlanEncoder]) already sends the food half alone. Following iOS keeps
 *   the two Android screens symmetric -- each sends what it shows -- rather
 *   than giving Cook's Send a silent second payload.
 * - **A booking whose routine is gone is dropped** (see [TrainPlanEncoder.encode])
 *   and counted in [removedBookings], so the screen can say so.
 */
data class TrainPlanSend(
    val clientName: String,
    val sessions: List<ScheduledSession>,
    val routines: List<Routine>,
    val removedBookings: Int,
    val link: String,
    /** Who this is addressed to; empty when no client is picked. */
    val clientId: String = "",
    /**
     * The payload inside [link], as encoded. Kept so the screen can file what it sent
     * ([SentPlan]) without re-encoding it and risking a record of a different plan.
     */
    val payloadJson: String = ""
) {

    /** Nothing bookable in this week means no button: a link offering nothing is not a send. */
    val isSendable: Boolean get() = link.isNotEmpty()

    /** What is in this link, in words -- Coach web's `planContents`, training half. */
    val contents: String
        get() = "${sessions.size} session${if (sessions.size == 1) "" else "s"}"

    /**
     * The line under the button: what is in the link and roughly how much email
     * it is. Coach web's `updateTrainPlanSize`, sentence for sentence.
     */
    val note: String
        get() {
            if (!isSendable) return "Nothing booked for this client this week yet."
            val kb = link.length / 1024.0
            return "$contents · about ${"%.1f".format(kb)} KB of email." +
                if (link.length > PlanEnvelope.RISKY_LINK_LENGTH)
                    " That is long enough that some mail apps will break it — send fewer days."
                else ""
        }

    /**
     * The text the share sheet carries. It names what it contains because
     * PLAN-FORMAT asks it to: an older LIFT that reads meals but not training
     * drops the training silently, and the covering message is what makes that
     * a visible mismatch rather than a puzzle.
     */
    val message: String get() = "Here's your training — $contents.\n\n$link"

    /**
     * The row to file when the coach actually sends this, or null when there is nothing to send.
     *
     * Recorded when the coach asks for the link, not when a client receives one: the chooser and a
     * mail app are both past where this app can see, and no platform sees into either. A plan a
     * coach opened the chooser for and then backed out of may be recorded, which is the accepted
     * cost -- the card says so in its own words and never claims the link arrived, and an
     * abandoned send is re-sent identically a moment later, which the hash reads as one plan
     * rather than two.
     */
    fun sentPlan(id: String, sentAtEpochSec: Long): SentPlan? {
        if (!isSendable || clientId.isEmpty() || payloadJson.isEmpty()) return null
        val payload = runCatching { org.json.JSONObject(payloadJson) }.getOrNull() ?: return null
        return SentPlan(id, clientId, sentAtEpochSec, SentPlans.hash(payload), payloadJson)
    }

    companion object {

        /**
         * @param sessions every session this device holds; filtered here.
         * @param routines the whole routine library.
         */
        fun build(
            clientId: String?,
            clientName: String?,
            week: PlanWeek,
            sessions: List<ScheduledSession>,
            routines: List<Routine>,
            coachName: String
        ): TrainPlanSend {
            val name = clientName.orEmpty()
            if (clientId.isNullOrEmpty()) return TrainPlanSend(name, emptyList(), emptyList(), 0, "")

            val days = week.days.toSet()
            val booked = sessions
                .filter { it.clientId == clientId && it.dayKey in days }
                .sortedBy { it.dayKey }
            val byId = routines.associateBy { it.id }
            val sendable = booked.filter { byId.containsKey(it.routineId) }
            // First booked, first inlined: `k`'s `x` indexes this list.
            val used = sendable.map { it.routineId }.distinct().mapNotNull { byId[it] }

            val payload = if (sendable.isEmpty()) null
            else TrainPlanEncoder.payload(used, sendable, clientId, coachName)
            val link = payload?.let { PlanEnvelope.LIFT_URL + "#" + PlanEnvelope.fragment(it) }.orEmpty()

            return TrainPlanSend(
                clientName = name,
                sessions = sendable,
                routines = used,
                removedBookings = booked.size - sendable.size,
                link = link,
                clientId = clientId,
                payloadJson = payload?.toString().orEmpty()
            )
        }

        /**
         * The coach's own name for `n`, as Connect stores it. Blank is "Your
         * coach" rather than an empty string on the wire -- the same guard
         * Coach iOS's `PlanLinkEncoder.coachName` applies, and the same default
         * this app's Cook Send already uses.
         */
        fun coachName(stored: String?): String = stored?.takeIf { it.isNotBlank() } ?: "Your coach"
    }
}
