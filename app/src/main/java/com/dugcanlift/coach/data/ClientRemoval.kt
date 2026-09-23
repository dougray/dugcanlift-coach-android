package com.dugcanlift.coach.data

import java.io.File

/** What removing one client takes with it, counted before the coach confirms. */
data class RemovalImpact(
    val clientName: String,
    val loggedDays: Int,
    val plannedMeals: Int,
    val bookedSessions: Int
)

/** How a removal went. [problem] is set when the client went but something planned for them could not. */
data class RemovalOutcome(val removed: Boolean, val message: String, val problem: Boolean = false)

/**
 * "Remove client": the client's file, and the meals planned and sessions booked for them.
 *
 * Coach web's Remove this client and the privacy policy both promise that removing a client deletes
 * what they sent from this device. The meals and sessions go too, because each carries the client's
 * id and nothing else: with the client gone they appear in no week, can never be sent or edited,
 * and would still ride along in every backup -- data about a former client the coach can neither
 * see nor delete. Recipes and routines stay; they are the coach's own library.
 *
 * **The record of what was sent to them goes too, for the reason the meals do** ([SentPlan]): a
 * payload addressed to one person, readable on no screen once they are gone, and still in every
 * backup from now on. It is out of the confirmation sentence for the reason the picks are.
 *
 * **Road picks go too, for the reason the meals do**: a list made for one client, keyed by that
 * client's id, which with the client gone can be neither seen nor sent while still riding in every
 * backup. They are deliberately **not** in the confirmation sentence: that sentence is pinned word
 * for word by Coach iOS's and Coach web's tests as well as this repo's, and a clause added here
 * alone would break all three. Coach web's own road-picks branch left its copy alone for the same
 * reason.
 *
 * Out of the Composable for the reason `BackupService` is: this repo has no way to test logic that
 * lives only in a screen. Blocking I/O: call it from `Dispatchers.IO`.
 */
class ClientRemoval(
    private val repo: ClientRepository,
    private val cook: CookRepository,
    private val train: TrainRepository,
    private val picks: RoadPickRepository,
    private val sentPlans: SentPlanRepository
) {
    constructor(repo: ClientRepository, filesDir: File) :
        this(repo, CookRepository(filesDir), TrainRepository(filesDir), RoadPickRepository(filesDir),
            SentPlanRepository(filesDir))

    /** Null when the client is not on this device (already removed, or never readable). */
    fun impact(clientId: String): RemovalImpact? {
        val client = repo.get(clientId) ?: return null
        val cookLibrary = cook.load()
        val trainLibrary = train.load()
        return RemovalImpact(
            clientName = client.name,
            loggedDays = client.days.size,
            plannedMeals = cookLibrary.meals.count { it.clientId == clientId },
            bookedSessions = trainLibrary.sessions.count { it.clientId == clientId }
        )
    }

    fun remove(clientId: String): RemovalOutcome {
        val name = repo.get(clientId)?.name ?: "This client"
        // Under the import lock, so a link arriving mid-removal cannot write the client back
        // between the read above and the delete, or be deleted straight after it reported success.
        val deleted = ShareLinkImporter.withImportLock { repo.delete(clientId) }
        if (!deleted) {
            return RemovalOutcome(false, "Couldn't remove $name. Nothing was changed.", problem = true)
        }

        // An unreadable library is left alone rather than rewritten: saving over it is what would
        // destroy the recipes and routines it still holds. The client is still gone.
        val skipped = mutableListOf<String>()
        val cookLibrary = cook.load()
        if (cookLibrary.isUnreadable) {
            skipped += "planned meals"
        } else if (cookLibrary.meals.any { it.clientId == clientId }) {
            cook.save(cookLibrary.recipes, cookLibrary.meals.filterNot { it.clientId == clientId })
        }
        val trainLibrary = train.load()
        if (trainLibrary.isUnreadable) {
            skipped += "booked sessions"
        } else if (trainLibrary.sessions.any { it.clientId == clientId }) {
            train.save(trainLibrary.routines, trainLibrary.sessions.filterNot { it.clientId == clientId })
        }
        // Same rule: an unreadable picks file is left alone rather than rewritten from an empty
        // read, which is what would destroy every other client's ticks.
        val storedPicks = picks.load()
        if (storedPicks.isUnreadable) {
            skipped += "road picks"
        } else if (storedPicks.forClient(clientId).isNotEmpty()) {
            picks.removeClient(clientId)
        }
        // And the same rule again for what was sent to them.
        val storedSends = sentPlans.load()
        if (storedSends.isUnreadable) {
            skipped += "sent plans"
        } else if (storedSends.forClient(clientId).isNotEmpty()) {
            sentPlans.removeClient(clientId)
        }

        return if (skipped.isEmpty()) {
            RemovalOutcome(true, "Removed $name.")
        } else {
            RemovalOutcome(
                true,
                "Removed $name. Their ${andList(skipped)} couldn't be removed, because " +
                    "that part of this device's library can't be read.",
                problem = true
            )
        }
    }

    companion object {
        /** "a", "a and b", "a, b and c" -- the two-item form is what this message always had. */
        private fun andList(parts: List<String>): String =
            if (parts.size < 3) parts.joinToString(" and ")
            else parts.dropLast(1).joinToString(", ") + " and " + parts.last()

        /** The confirmation's body: what goes, in words, before anything does. */
        fun confirmationText(impact: RemovalImpact): String {
            val days = if (impact.loggedDays == 1) "1 logged day" else "${impact.loggedDays} logged days"
            val planned = listOfNotNull(
                impact.plannedMeals.takeIf { it > 0 }?.let { if (it == 1) "1 planned meal" else "$it planned meals" },
                impact.bookedSessions.takeIf { it > 0 }?.let { if (it == 1) "1 booked session" else "$it booked sessions" }
            )
            val also = if (planned.isEmpty()) "" else ", and the ${planned.joinToString(" and ")} you made for them"
            return "Their $days will be removed from this device$also. Your recipes and routines stay. " +
                "A backup file you saved earlier still has them. This can't be undone."
        }
    }
}
