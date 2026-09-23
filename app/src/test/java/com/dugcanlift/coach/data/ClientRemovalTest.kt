package com.dugcanlift.coach.data

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Remove client: the client's file goes, and with it the meals planned and sessions booked for
 * them -- nothing else. Another client's week, and the coach's own recipes and routines, stay.
 */
class ClientRemovalTest {

    private lateinit var root: File
    private lateinit var repo: ClientRepository
    private lateinit var cook: CookRepository
    private lateinit var train: TrainRepository
    private lateinit var picks: RoadPickRepository
    private lateinit var removal: ClientRemoval

    private fun day(key: String) = TrainingDay(key, null, null, null, null, null, null, null, null, null, emptyList(), emptyList())

    @Before fun setUp() {
        root = Files.createTempDirectory("coach-removal").toFile()
        repo = ClientRepository(root)
        cook = CookRepository(root)
        train = TrainRepository(root)
        picks = RoadPickRepository(root)
        removal = ClientRemoval(repo, cook, train, picks)

        repo.save(Client("jordan", "Jordan Reyes", "lb", "and", 0, null, listOf(day("2026-09-14"), day("2026-09-15"))))
        repo.save(Client("sam", "Sam Ortiz", "lb", "ios", 0, null, listOf(day("2026-09-15"))))

        cook.save(
            listOf(Recipe(id = "chili", name = "Chili")),
            listOf(
                PlannedMeal(id = "m1", recipeId = "chili", clientId = "jordan", dayKey = "2026-09-16"),
                PlannedMeal(id = "m2", recipeId = "chili", clientId = "jordan", dayKey = "2026-09-17"),
                PlannedMeal(id = "m3", recipeId = "chili", clientId = "sam", dayKey = "2026-09-16"),
                PlannedMeal(id = "m4", recipeId = "chili", clientId = null, dayKey = "2026-09-16")
            )
        )
        train.save(
            listOf(Routine(id = "push", name = "Push")),
            listOf(
                ScheduledSession(id = "s1", clientId = "jordan", dayKey = "2026-09-16", routineId = "push"),
                ScheduledSession(id = "s2", clientId = "sam", dayKey = "2026-09-16", routineId = "push")
            )
        )
    }

    @After fun tearDown() {
        root.deleteRecursively()
    }

    @Test fun `impact counts the client's days, meals and sessions`() {
        assertEquals(RemovalImpact("Jordan Reyes", loggedDays = 2, plannedMeals = 2, bookedSessions = 1), removal.impact("jordan"))
    }

    @Test fun `impact of a client not on this device is null`() {
        assertNull(removal.impact("nobody"))
    }

    @Test fun `remove deletes the client and only what was planned for them`() {
        val outcome = removal.remove("jordan")

        assertTrue(outcome.removed)
        assertFalse(outcome.problem)
        assertEquals("Removed Jordan Reyes.", outcome.message)
        assertNull(repo.get("jordan"))
        assertEquals(listOf("sam"), repo.all().map { it.id })

        val cookLibrary = cook.load()
        assertEquals(listOf("chili"), cookLibrary.recipes.map { it.id })
        assertEquals(listOf("m3", "m4"), cookLibrary.meals.map { it.id })

        val trainLibrary = train.load()
        assertEquals(listOf("push"), trainLibrary.routines.map { it.id })
        assertEquals(listOf("s2"), trainLibrary.sessions.map { it.id })
    }

    @Test fun `an unreadable library is left untouched and the message says what stayed`() {
        val cookFile = File(root, "cook-library.json")
        cookFile.writeText("{not json")

        val outcome = removal.remove("jordan")

        assertTrue(outcome.removed)
        assertTrue(outcome.problem)
        assertTrue(outcome.message, outcome.message.contains("planned meals couldn't be removed"))
        assertNull(repo.get("jordan"))
        assertEquals("{not json", cookFile.readText())
        assertEquals(listOf("s2"), train.load().sessions.map { it.id })
    }

    @Test fun `a client with nothing planned leaves the library files alone`() {
        cook.save(emptyList(), emptyList())
        val before = File(root, "cook-library.json").lastModified()
        repo.save(Client("solo", "Solo", "lb", "and", 0, null, emptyList()))

        assertTrue(removal.remove("solo").removed)
        assertEquals(before, File(root, "cook-library.json").lastModified())
    }

    @Test fun `the confirmation says what goes and what stays`() {
        assertEquals(
            "Their 2 logged days will be removed from this device, and the 2 planned meals and 1 booked session " +
                "you made for them. Your recipes and routines stay. A backup file you saved earlier still has them. " +
                "This can't be undone.",
            ClientRemoval.confirmationText(RemovalImpact("Jordan Reyes", 2, 2, 1))
        )
        assertEquals(
            "Their 1 logged day will be removed from this device. Your recipes and routines stay. " +
                "A backup file you saved earlier still has them. This can't be undone.",
            ClientRemoval.confirmationText(RemovalImpact("Sam", 1, 0, 0))
        )
    }
}
