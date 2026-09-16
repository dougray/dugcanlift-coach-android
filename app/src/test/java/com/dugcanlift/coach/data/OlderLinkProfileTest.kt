package com.dugcanlift.coach.data
import com.dugcanlift.kit.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Name, unit, platform and goal follow the newest send, as on Coach web and Coach iOS: a client who
 * changed their goal last week must not have it undone by an older link pasted late. Days still land
 * from any link.
 */
class OlderLinkProfileTest {
    @get:Rule val tmp = TemporaryFolder()
    private val id = "b7f3a1c8"

    private fun send(repo: ClientRepository, z: Long, name: String, unit: String = "lb", calories: Int, days: List<ShareDay> = emptyList()) =
        ShareLinkImporter.import(ShareLinkCodec.encodeFragment(SharePayload(
            ShareClient(id, name, unit = unit, platform = "ios"), ShareGoal(calories, 180, 70, 250, 30),
            "2026-09-01", "2026-09-10", z, days)), repo)

    private fun day(name: String) = ShareDay(0, name, null, null, 8_000L, emptyList(), null, null)

    @Test fun `an older link pasted late keeps the newer goal and profile`() {
        val repo = ClientRepository(tmp.root)
        send(repo, 2_000, "Jordan R.", "kg", 2_600)
        send(repo, 1_000, "Jordan Reyes", "lb", 2_200)
        val client = repo.get(id)!!
        assertEquals(2_600, client.goal!!.calories)
        assertEquals("Jordan R.", client.name)
        assertEquals("kg", client.displayUnit)
    }

    @Test fun `an older link still delivers its days`() {
        val repo = ClientRepository(tmp.root)
        send(repo, 2_000, "Jordan", calories = 2_600)
        send(repo, 1_000, "Jordan", calories = 2_200, days = listOf(day("Pull Day")))
        assertEquals(listOf(8_000L), repo.get(id)!!.days.map { it.steps })
    }

    @Test fun `a newer or equal link updates goal and profile`() {
        val repo = ClientRepository(tmp.root)
        send(repo, 1_000, "Jordan Reyes", calories = 2_200)
        send(repo, 1_000, "Jordan R.", "kg", 2_400)
        assertEquals(2_400, repo.get(id)!!.goal!!.calories)
        send(repo, 3_000, "Jordan", calories = 2_500)
        val client = repo.get(id)!!
        assertEquals(2_500, client.goal!!.calories)
        assertEquals("Jordan", client.name)
        assertEquals("lb", client.displayUnit)
    }

    @Test fun `a client stored before links were stamped takes the next link`() {
        val repo = ClientRepository(tmp.root)
        send(repo, 5_000, "Old Name", calories = 2_000)
        repo.save(repo.get(id)!!.copy(exportedAtEpochSec = null))
        send(repo, 1, "New Name", calories = 2_300)
        val client = repo.get(id)!!
        assertEquals("New Name", client.name)
        assertEquals(2_300, client.goal!!.calories)
    }
}
