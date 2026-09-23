package com.dugcanlift.coach.data
import com.dugcanlift.kit.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * SHARE-FORMAT "Outdoor" on the receiving side. `outdoor-share-link.txt` and
 * `outdoor-share-expected.json` were written by LIFT web's encoder and copied here unchanged --
 * never regenerate them from this code or the kit, or they prove agreement with ourselves instead
 * of interop. The link is r=2026-09-10, z=1789500000, days k=0, 2, 3.
 */
class ShareOutdoorImportTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun fixture(n: String) = javaClass.getResourceAsStream("/fixtures/$n")!!.bufferedReader().readText().trim()
    private val expected by lazy { JSONObject(fixture("outdoor-share-expected.json")) }
    private val clientId = "outdoor-fixture"

    private fun importFixture(repo: ClientRepository) =
        ShareLinkImporter.import(fragmentFrom(fixture("outdoor-share-link.txt")), repo) as ImportResult.Imported

    /** A later (or earlier) send from the same client, carrying neither `ob` nor `lr`. */
    private fun sendWithoutOutdoor(z: Long, days: List<ShareDay> = emptyList()) = ShareLinkCodec.encodeFragment(
        SharePayload(ShareClient(clientId, "Outdoor Fixture", platform = "web"), null, "2026-09-14", "2026-09-16", z, days))

    @Test fun `the LIFT web fixture imports three activities on the right days`() {
        val repo = ClientRepository(tmp.root)
        assertEquals(3, importFixture(repo).daysImported)
        val days = repo.get(clientId)!!.days.associateBy { it.dayKey }
        assertEquals(setOf("2026-09-10", "2026-09-12", "2026-09-13"), days.keys)

        // expected.json's `o` is every activity in start order, as if one day; here they are split by day.
        val o = expected.getJSONArray("o")
        val stored = listOf("2026-09-10", "2026-09-12", "2026-09-13").map { days.getValue(it).outdoor.single() }
        stored.forEachIndexed { i, a ->
            val t = o.getJSONArray(i)
            assertEquals(OutdoorActivity(t.getInt(0), t.getLong(1), t.getLong(2), t.getLong(3)), a)
        }
        assertEquals(OutdoorActivity(0, 1720, 2795, 37), stored[2])
    }

    @Test fun `bests equal what LIFT web says they are`() {
        val repo = ClientRepository(tmp.root)
        importFixture(repo)
        val bests = repo.get(clientId)!!.outdoorBests!!
        assertEquals(listOf(OutdoorBest(0, 2, 10001, 3000, 300), OutdoorBest(1, 1, 300, 240, null)), bests)

        val ob = expected.getJSONArray("ob")
        assertEquals(ob.length(), bests.size)
        bests.forEachIndexed { i, b ->
            val t = ob.getJSONArray(i)
            assertEquals(t.getInt(0), b.type); assertEquals(t.getInt(1), b.count)
            assertEquals(t.optLongOrNullAt(2), b.farthestMeters); assertEquals(t.optLongOrNullAt(3), b.longestSec)
            assertEquals(t.optLongOrNullAt(4), b.fastestSecPerKm)
        }
    }

    @Test fun `the last route decodes to 150 points and keeps the whole activity's numbers`() {
        val repo = ClientRepository(tmp.root)
        importFixture(repo)
        val route = repo.get(clientId)!!.lastRoute!!
        val lr = expected.getJSONArray("lr")
        assertEquals(LastRoute(0, 1789259200, 1720, 2795, 37, lr.getString(5)), route)
        assertEquals(2795, route.distanceMeters)
        assertEquals(expected.getInt("trimmedPointCount"), OutdoorShare.decodePolyline(route.polyline).size)
        assertEquals(150, OutdoorShare.decodePolyline(route.polyline).size)
    }

    @Test fun `a newer link without lr or ob clears them`() {
        val repo = ClientRepository(tmp.root)
        importFixture(repo)
        ShareLinkImporter.import(sendWithoutOutdoor(z = 1789600000), repo)
        val c = repo.get(clientId)!!
        assertNull(c.lastRoute); assertNull(c.outdoorBests)
        assertEquals(1789600000L, c.exportedAtEpochSec)
        // Days are still replaced by date and nothing else: the fixture's three days are untouched.
        assertEquals(3, c.days.count { it.outdoor.isNotEmpty() })
    }

    @Test fun `an older link opened late does not clear them`() {
        val repo = ClientRepository(tmp.root)
        importFixture(repo)
        val walk = ShareDay(0, null, null, null, null, emptyList(), null, null, listOf(ShareOutdoor(1, 900, 1200, 4)))
        ShareLinkImporter.import(sendWithoutOutdoor(z = 1789400000, days = listOf(walk)), repo)
        val c = repo.get(clientId)!!
        assertNotNull(c.lastRoute); assertEquals(2, c.outdoorBests!!.size)
        assertEquals(1789500000L, c.exportedAtEpochSec)
        // Its days still land -- only the all-time parts follow the newest send.
        assertEquals(listOf(OutdoorActivity(1, 900, 1200, 4)), c.days.single { it.dayKey == "2026-09-14" }.outdoor)
    }

    @Test fun `a client stored before exportedAt existed takes any send`() {
        val repo = ClientRepository(tmp.root)
        repo.save(Client(clientId, "Outdoor Fixture", "lb", "web", 0, null, emptyList(),
            outdoorBests = listOf(OutdoorBest(2, 1, 5000, 7200, null)), exportedAtEpochSec = null))
        ShareLinkImporter.import(sendWithoutOutdoor(z = 1), repo)
        assertNull(repo.get(clientId)!!.outdoorBests)
    }

    @Test fun `an unknown type, a non-positive best and an undrawable route are dropped, as Coach web reads them`() {
        val repo = ClientRepository(tmp.root)
        val payload = SharePayload(ShareClient(clientId, "X"), null, "2026-09-14", "2026-09-14", 5,
            listOf(ShareDay(0, null, null, null, null, emptyList(), null, null, listOf(ShareOutdoor(7, 60, 100, 0), ShareOutdoor(2, 60, 100, 0)))),
            outdoorBests = listOf(ShareOutdoorBest(9, 1, 1, 1, 1), ShareOutdoorBest(2, 1, 0, 60, null)),
            lastRoute = ShareLastRoute(0, 1, 60, 100, 0, OutdoorShare.encodePolyline(listOf(40.0 to -74.0))))
        ShareLinkImporter.import(ShareLinkCodec.encodeFragment(payload), repo)
        val c = repo.get(clientId)!!
        assertEquals(listOf(OutdoorActivity(2, 60, 100, 0)), c.days.single().outdoor)
        assertEquals(listOf(OutdoorBest(2, 1, null, 60, null)), c.outdoorBests)
        assertNull(c.lastRoute)
    }

    @Test fun `outdoor survives the client file and a backup round trip`() {
        val repo = ClientRepository(tmp.root)
        importFixture(repo)
        val imported = repo.get(clientId)!!

        val reloaded = ClientRepository(tmp.root).get(clientId)!!
        assertEquals(imported, reloaded)

        val restored = BackupCodec.restore(BackupCodec.export(listOf(imported), null, emptyList(), emptyList(), emptyList(), emptyList(), emptyMap(), emptyList())).clients.single()
        // lastImportedAt crosses a Foundation-seconds double and back, so compare everything but it.
        assertEquals(imported, restored.copy(lastImportedAtEpochMs = imported.lastImportedAtEpochMs))
    }

    @Test fun `a client file and a backup written before outdoor still load, with nothing outdoor`() {
        val old = """{"id":"a","name":"Doug","displayUnit":"lb","platform":null,"lastImportedAtEpochMs":0,"goal":null,
            "days":[{"dayKey":"2026-09-10","sessionName":null,"focus":null,"bodyweightLb":null,"steps":null,"foodCalories":null,
            "foodProteinG":null,"foodFatG":null,"foodCarbsG":null,"foodFiberG":null,"sets":[],"foodEntries":[]}]}"""
        val c = Client.fromJson(JSONObject(old))
        assertTrue(c.days.single().outdoor.isEmpty()); assertNull(c.outdoorBests); assertNull(c.lastRoute); assertNull(c.exportedAtEpochSec)

        val backup = BackupCodec.restore(javaClass.getResourceAsStream("/fixtures/coach-ios-backup-v2.json")!!.bufferedReader().readText())
        backup.clients.forEach { assertNull(it.outdoorBests); assertNull(it.lastRoute); assertTrue(it.days.all { d -> d.outdoor.isEmpty() }) }
    }

    private fun JSONArray.optLongOrNullAt(i: Int): Long? = if (isNull(i)) null else getLong(i)
}
