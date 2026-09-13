package com.dugcanlift.coach.data

import com.dugcanlift.kit.DayKey

/** One roster row: a client plus the days since they last logged and the label rendered under their name. */
data class RosterRow(val client: Client, val daysSinceLastLogged: Int?, val label: String)

/**
 * The roster screen's full pure view state: rows already sorted quietest-first, plus the
 * (also quietest-first) names of clients silent for [Roster.SILENCE_THRESHOLD_DAYS] days or more,
 * for the silence banner.
 */
data class RosterViewState(val rows: List<RosterRow>, val silentNames: List<String>)

/**
 * Turns a raw client list into what the roster screen shows. Mirrors the iOS app's
 * RosterView/Models silence logic (`daysSinceLastLoggedDay`, the >= 7 day banner, and the three
 * "Logged ..." phrasings), with one extension made explicit here: a client who has never logged
 * (`null`) is treated as infinitely silent, so they sort first AND count toward the banner --
 * exactly how iOS's own `?? Int.max` substitution behaves.
 */
object Roster {
    const val SILENCE_THRESHOLD_DAYS = 7

    /** "Nothing logged yet" / "Logged today" / "Logged yesterday" / "Logged N days ago". */
    fun label(daysSinceLastLogged: Int?): String = when {
        daysSinceLastLogged == null -> "Nothing logged yet"
        daysSinceLastLogged <= 0 -> "Logged today"
        daysSinceLastLogged == 1 -> "Logged yesterday"
        else -> "Logged $daysSinceLastLogged days ago"
    }

    fun buildViewState(clients: List<Client>, today: String = DayKey.today()): RosterViewState {
        val silenceRank = { days: Int? -> days ?: Int.MAX_VALUE }
        // runCatching is the belt to daysSinceLastLoggedDay's braces: this runs inside
        // RosterScreen's composition, and one client whose stored data cannot be reasoned about
        // must cost that client's label, never the whole roster. Connect -- the only screen with
        // "Restore from Backup" -- is reachable only through this list, so a throw here strands the
        // coach with no way to restore a good backup short of clearing app data.
        val sorted = clients
            .map { it to runCatching { it.daysSinceLastLoggedDay(today) }.getOrNull() }
            .sortedByDescending { (_, days) -> silenceRank(days) }

        val rows = sorted.map { (client, days) -> RosterRow(client, days, label(days)) }
        val silentNames = sorted
            .filter { (_, days) -> silenceRank(days) >= SILENCE_THRESHOLD_DAYS }
            .map { (client, _) -> client.name }

        return RosterViewState(rows, silentNames)
    }
}
