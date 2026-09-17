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
 * Out of the Composable for the reason `BackupService` is: this repo has no way to test logic that
 * lives only in a screen. Blocking I/O: call it from `Dispatchers.IO`.
 */
class ClientRemoval(
    private val repo: ClientRepository,
    private val cook: CookRepository,
    private val train: TrainRepository
) {
    constructor(repo: ClientRepository, filesDir: File) :
        this(repo, CookRepository(filesDir), TrainRepository(filesDir))

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

        return if (skipped.isEmpty()) {
            RemovalOutcome(true, "Removed $name.")
        } else {
            RemovalOutcome(
                true,
                "Removed $name. Their ${skipped.joinToString(" and ")} couldn't be removed, because " +
                    "that part of this device's library can't be read.",
                problem = true
            )
        }
    }

    companion object {
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
