package com.dugcanlift.coach.data

import com.dugcanlift.kit.CompactEncoding
import com.dugcanlift.kit.PlanDecodeResult
import com.dugcanlift.kit.PlanLinkCodec
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

/**
 * Road picks (PLAN-FORMAT "Road picks"): the stored list, the wire, the
 * bundled file, the backup and what a removal takes.
 *
 * A port of Coach web's `coach/road-picks.test.mjs` and the road-picks half of
 * its `client-removal.test.mjs`, case for case, plus what only Android has:
 * the repository, `BackupCodec` and `ClientRemoval`.
 *
 * `fixtures/web-plan-road-picks.txt` is a link **Coach web's own encoder
 * wrote** (md5 5c2f783c291f9c757e86a75a014510e6, the same bytes as
 * `dugcanlift-coach/coach/fixtures/` and `dugcanlift-site/lift/fixtures/`).
 * Never regenerate it from Kotlin: its whole value is that a different
 * implementation wrote it. Every link this file encodes is read back with the
 * kit's `PlanLinkCodec` -- the decoder LIFT Android ships -- rather than with a
 * second copy of the rules.
 */
class RoadPicksTest {

    @get:Rule val tmp = TemporaryFolder()

    /** The bundled file itself, the same bytes LIFT has. */
    private val data: RoadFoodData by lazy {
        parseRoadFood(File("src/main/assets/$ROAD_FOOD_ASSET").readText())
    }

    private fun fixture(name: String) =
        javaClass.classLoader!!.getResource("fixtures/$name")!!.readText().trim()

    private fun rawJson(fragment: String): JSONObject {
        check(fragment.startsWith("1z"))
        return JSONObject(
            String(
                CompactEncoding.inflateRaw(CompactEncoding.base64UrlDecode(fragment.substring(2))),
                Charsets.UTF_8
            )
        )
    }

    private fun success(fragment: String, lifter: String = "c1") =
        (PlanLinkCodec.decode(fragment, lifter) as PlanDecodeResult.Success).payload

    private val chili = Recipe(
        id = "r1", name = "Chili", servings = 4.0,
        rawIngredients = listOf("500 g beef mince")
    )

    /* ---------------- the stored list ---------------- */

    @Test fun `picks are trimmed strings, unique, in the order they were ticked`() {
        assertEquals(
            listOf("b", "a", "c"),
            RoadPicks.normalise(listOf(" b ", "a", "b", "", "a", null, "c"))
        )
        assertEquals(emptyList<String>(), RoadPicks.normalise(null))
    }

    @Test fun `toggle adds at the end and removes by id`() {
        assertEquals(listOf("a", "b", "c"), RoadPicks.toggle(listOf("a", "b"), "c", true))
        assertEquals(listOf("b"), RoadPicks.toggle(listOf("a", "b"), "a", false))
        assertEquals(
            "ticking one already on is a no-op re-add",
            listOf("b", "a"), RoadPicks.toggle(listOf("a", "b"), "a", true)
        )
        assertEquals(emptyList<String>(), RoadPicks.toggle(emptyList(), "a", false))
    }

    @Test fun `toggleAll picks or clears one place without touching another`() {
        val items = listOf(RoadFoodItem("x1", "X1"), RoadFoodItem("x2", "X2"))
        assertEquals(listOf("a", "x1", "x2"), RoadPicks.toggleAll(listOf("a"), items, true))
        assertEquals(listOf("a"), RoadPicks.toggleAll(listOf("a", "x1", "x2"), items, false))
        assertEquals(
            "no duplicates",
            listOf("a", "x1", "x2"), RoadPicks.toggleAll(listOf("a", "x1"), items, true)
        )
    }

    /* ---------------- the wire ---------------- */

    @Test fun `no picks is no key at all, never an empty list`() {
        assertNull(RoadPicks.wire(emptyList()))
        assertNull(RoadPicks.wire(null))
        assertNull(RoadPicks.wire(listOf("", "  ")))

        // A plan with no picks is byte for byte the plan main wrote.
        val meals = listOf(PlannedMeal(recipeId = "r1", clientId = "c1", dayKey = "2026-09-14"))
        val before = CookPlanEncoder.encode(meals, mapOf("r1" to chili), "c1", "Doug")
        val after = CookPlanEncoder.encode(meals, mapOf("r1" to chili), "c1", "Doug", emptyList())
        assertEquals(before, after)
        assertFalse(rawJson(after).has("rf"))
        assertFalse(
            "and no empty array either",
            CookPlanEncoder.encode(meals, mapOf("r1" to chili), "c1", "Doug", listOf(" "))
                .let { rawJson(it).has("rf") }
        )
    }

    @Test fun `picks travel as a flat list of ids, in order`() {
        val ids = listOf("wendys-large-chili", "chickfila-grilled-filet")
        val fragment = CookPlanEncoder.encode(emptyList(), emptyMap(), "c1", "Doug", ids)
        assertEquals(JSONArray(ids).toString(), rawJson(fragment).getJSONArray("rf").toString())
        assertEquals("purely additive: no version bump", 1, rawJson(fragment).getInt("v"))
        assertEquals("plan", rawJson(fragment).getString("t"))
    }

    @Test fun `a picks-only plan is a legitimate send the client's app reads`() {
        val fragment = CookPlanEncoder.encode(
            emptyList(), emptyMap(), "c1", "Doug", listOf("wendys-large-chili")
        )
        val payload = success(fragment)
        assertEquals("Doug", payload.coachName)
        assertEquals(emptyList<Any>(), payload.recipes)
        assertEquals(emptyList<Any>(), payload.meals)
        assertEquals(
            listOf("wendys-large-chili"),
            RoadPicks.fromWire(JSONObject(payload.rawJson).optJSONArray("rf"))
        )
    }

    @Test fun `a link carrying picks is still addressed to one client`() {
        val fragment = CookPlanEncoder.encode(emptyList(), emptyMap(), "c1", "Doug", listOf("a"))
        assertTrue(PlanLinkCodec.decode(fragment, "someone-else") is PlanDecodeResult.NotAddressedToYou)
    }

    @Test fun `rf reads back exactly, and junk reads as none`() {
        val ids = listOf("wendys-large-chili", "snack-jack-links-original-beef-jerky")
        assertEquals(ids, RoadPicks.fromWire(JSONArray(ids)))
        assertEquals(emptyList<String>(), RoadPicks.fromWire(null))
        assertEquals(
            "read leniently, never refused",
            listOf("a", "b"),
            RoadPicks.fromWire(JSONArray(listOf("a", "a", 3, " b")))
        )
    }

    @Test fun `nothing is filtered against this app's own copy of the file on the way out`() {
        // The coach's bundle and the client's are two builds updated at
        // different times, so only the receiver can say what it has. An id this
        // copy does not know still travels; LIFT skips it silently.
        val ids = listOf("wendys-large-chili", "gone-from-the-menu-2019")
        assertEquals(ids, RoadPicks.wire(ids))
        assertEquals(listOf("gone-from-the-menu-2019"), RoadPicks.missing(ids, data))
        val fragment = CookPlanEncoder.encode(emptyList(), emptyMap(), "c1", "Doug", ids)
        assertEquals(JSONArray(ids).toString(), rawJson(fragment).getJSONArray("rf").toString())
    }

    /* ---------------- against the bundled file ---------------- */

    @Test fun `every id in the bundled file is unique - the ids are the whole contract`() {
        val all = RoadPicks.catalogue(data).map { it.id }
        assertEquals(all.size, all.toSet().size)
        assertTrue("chains and snacks both counted", all.size > 50)
    }

    @Test fun `the catalogue covers chain items and gas-station snacks alike`() {
        val index = RoadPicks.index(data)
        assertEquals("Wendy's", index["wendys-large-chili"]!!.placeName)
        assertEquals("snacks", index["snack-jack-links-original-beef-jerky"]!!.placeId)
        assertEquals("Gas station", index["snack-jack-links-original-beef-jerky"]!!.placeName)
    }

    @Test fun `counts and the summary line count only what this copy has`() {
        val wendys = data.chains.first { it.id == "wendys" }
        val ids = listOf("wendys-large-chili", "wendys-4-pc-tenders", "gone-from-the-menu-2019")
        assertEquals(2, RoadPicks.countIn(ids, wendys.items))
        assertEquals("2 items at 1 place", RoadPicks.summary(ids, data))
        assertEquals(
            "2 items at 2 places",
            RoadPicks.summary(listOf("wendys-large-chili", "snack-rxbar-blueberry"), data)
        )
        assertEquals("1 item at 1 place", RoadPicks.summary(listOf("wendys-large-chili"), data))
        assertEquals("", RoadPicks.summary(emptyList(), data))
        assertEquals(
            "an id nothing here knows is not counted on screen, though it still travels",
            "", RoadPicks.summary(listOf("gone-from-the-menu-2019"), data)
        )
    }

    @Test fun `blank stays blank in the bundled file - an unlisted number is not zero`() {
        val all = RoadPicks.catalogue(data).map { it.item }
        assertTrue("the file does list calories", all.any { it.kcal != null })
        assertTrue("and names", all.all { it.name.isNotBlank() })
    }

    /* ---------------- the fixture ---------------- */

    @Test fun `the road-picks fixture carries rf as a flat list of ids`() {
        val payload = rawJson(fixture("web-plan-road-picks.txt").substringAfter('#'))
        assertEquals("purely additive: no version bump", 1, payload.getInt("v"))
        assertEquals("plan", payload.getString("t"))
        val rf = RoadPicks.fromWire(payload.getJSONArray("rf"))
        assertEquals("already normalised", 6, rf.size)

        val index = RoadPicks.index(data)
        val places = rf.mapNotNull { index[it]?.placeId }.distinct()
        assertEquals("two chains and the gas station", 3, places.size)
        assertTrue("a gas-station snack", "snacks" in places)
        assertEquals(
            "exactly one id the data does not have, so a decoder's skip rule is exercised",
            listOf("wendys-item-withdrawn-2019"), RoadPicks.missing(rf, data)
        )
    }

    /* ---------------- the repository ---------------- */

    @Test fun `picks are stored per client and cleared by storing none`() {
        val repo = RoadPickRepository(tmp.root)
        assertEquals(emptyList<String>(), repo.load().forClient("jordan"))

        repo.setForClient("jordan", listOf("a", "b"))
        repo.setForClient("sam", listOf("c"))
        assertEquals(listOf("a", "b"), repo.load().forClient("jordan"))
        assertEquals(listOf("c"), repo.load().forClient("sam"))

        repo.setForClient("jordan", emptyList())
        assertEquals(emptyList<String>(), repo.load().forClient("jordan"))
        assertFalse("the key goes, not an empty array", "jordan" in repo.load().byClient)
        assertEquals("and nobody else is touched", listOf("c"), repo.load().forClient("sam"))
    }

    @Test fun `an unreadable picks file is not no picks, and is never written over`() {
        File(tmp.root, "road-picks.json").writeText("{ not json")
        val repo = RoadPickRepository(tmp.root)
        assertTrue(repo.load().isUnreadable)
        try {
            repo.setForClient("jordan", listOf("a"))
            throw AssertionError("a write over an unreadable file must not be silent")
        } catch (expected: Throwable) {
            assertFalse(expected is AssertionError)
        }
        assertEquals("{ not json", File(tmp.root, "road-picks.json").readText())
    }

    /* ---------------- the backup ---------------- */

    @Test fun `the backup carries roadPicks as an object keyed by client, omitted when none`() {
        val out = JSONObject(
            BackupCodec.export(
                emptyList(), null, emptyList(), emptyList(), emptyList(), emptyList(),
                mapOf("jordan" to listOf("wendys-large-chili", "snack-rxbar-blueberry")), emptyList()
            )
        )
        assertEquals(
            JSONArray(listOf("wendys-large-chili", "snack-rxbar-blueberry")).toString(),
            out.getJSONObject("roadPicks").getJSONArray("jordan").toString()
        )
        val none = JSONObject(
            BackupCodec.export(emptyList(), null, emptyList(), emptyList(), emptyList(), emptyList(), emptyMap(), emptyList())
        )
        assertFalse("omitted, never an empty object", none.has("roadPicks"))
    }

    @Test fun `roadPicks round-trips by value, and a file without it restores unchanged`() {
        val picks = mapOf("jordan" to listOf("a", "b"), "sam" to listOf("c"))
        val json = BackupCodec.export(
            emptyList(), null, emptyList(), emptyList(), emptyList(), emptyList(), picks, emptyList()
        )
        assertEquals(picks, BackupCodec.restore(json).roadPicks)

        val older = """{"v":2,"clients":[]}"""
        assertEquals(emptyMap<String, List<String>>(), BackupCodec.restore(older).roadPicks)
    }

    @Test fun `a modelled roadPicks is not also carried as cargo`() {
        // Preservation is by exclusion, so a key that becomes modelled has to
        // join ENVELOPE_KEYS or it is written twice -- once from the model and
        // once out of the cargo a file written by an older build still holds.
        val fromOlderBuild = """{"v":2,"clients":[],"roadPicks":{"jordan":["a"]}}"""
        val restored = BackupCodec.restore(fromOlderBuild)
        assertNull(restored.preservedLibrary?.optJSONObject("roadPicks"))
        val out = JSONObject(
            BackupCodec.export(
                restored.clients, restored.preservedLibrary, restored.recipes, restored.meals,
                restored.routines, restored.sessions, restored.roadPicks, restored.sentPlans
            )
        )
        assertEquals(
            JSONArray(listOf("a")).toString(),
            out.getJSONObject("roadPicks").getJSONArray("jordan").toString()
        )
    }

    @Test fun `restoring picks is per client - an older backup never deletes newer work`() {
        val repo = RoadPickRepository(tmp.root)
        repo.setForClient("jordan", listOf("kept-1", "kept-2"))

        val added = repo.merge(mapOf("jordan" to listOf("older-1"), "sam" to listOf("new-1", "new-2")))
        assertEquals("only the client this device had none for", 2, added)
        assertEquals(
            "a client this device already has picks for keeps them",
            listOf("kept-1", "kept-2"), repo.load().forClient("jordan")
        )
        assertEquals(listOf("new-1", "new-2"), repo.load().forClient("sam"))

        // A v1 file, or any Coach old enough not to have picks, changes nothing.
        assertEquals(0, repo.merge(emptyMap()))
        assertEquals(listOf("kept-1", "kept-2"), repo.load().forClient("jordan"))
    }

    /* ---------------- removing a client ---------------- */

    @Test fun `a removal takes the client's road picks and leaves everyone else's`() {
        val root = tmp.root
        val clients = ClientRepository(root)
        clients.save(Client("jordan", "Jordan Reyes", "lb", "and", 0, null, emptyList()))
        clients.save(Client("sam", "Sam Ortiz", "lb", "ios", 0, null, emptyList()))
        val picks = RoadPickRepository(root)
        picks.setForClient("jordan", listOf("wendys-large-chili"))
        picks.setForClient("sam", listOf("subway-oven-roasted-turkey-6-inch"))

        val removal = ClientRemoval(clients, CookRepository(root), TrainRepository(root), picks,
            SentPlanRepository(root))
        val outcome = removal.remove("jordan")
        assertTrue(outcome.removed)
        assertFalse(outcome.problem)
        assertEquals(emptyList<String>(), picks.load().forClient("jordan"))
        assertEquals(
            listOf("subway-oven-roasted-turkey-6-inch"), picks.load().forClient("sam")
        )
    }

    @Test fun `the confirmation sentence is untouched by road picks`() {
        // Coach iOS's and Coach web's tests pin this word for word; a clause
        // added here alone would break all three. Coach web's own road-picks
        // branch left its copy alone for the same reason.
        val impact = RemovalImpact("Jordan Reyes", loggedDays = 2, plannedMeals = 2, bookedSessions = 1)
        assertEquals(
            "Their 2 logged days will be removed from this device, and the 2 planned meals and " +
                "1 booked session you made for them. Your recipes and routines stay. A backup " +
                "file you saved earlier still has them. This can't be undone.",
            ClientRemoval.confirmationText(impact)
        )
        assertFalse(ClientRemoval.confirmationText(impact).contains("pick"))
        assertFalse(ClientRemoval.confirmationText(impact).contains("road"))
    }

    // MARK: - The copy is the kit's bytes

    /**
     * road-food.json is curated once in `dugcanlift-kit/data/` and copied byte
     * for byte into six app repos. On 2026-09-23 this one was missed when
     * Burger King, Whataburger and Chipotle went round the others: a coach
     * could not pick at a place their client could see, and nothing failed,
     * because every other check here reads the data's shape and an old copy
     * has a perfectly good shape.
     *
     * Item ids are the contract a coach's picks travel on, and LIFT skips an
     * id it does not know in silence by design, so a stale copy here is a real
     * failure rather than an untidiness.
     *
     * The kit writes the checksum (`node data/validate-road-food.mjs
     * --write-checksum`); copy road-food.json AND road-food.sha256 over
     * together, and never re-write the hash by hand to make this pass -- the
     * other five repos pin the same one, so that only moves the failure.
     */
    @Test
    fun `the bundled road food file is the kit's bytes`() {
        val bytes = File("src/main/assets/$ROAD_FOOD_ASSET").readBytes()
        val pinned = File("src/main/assets/road-food.sha256").readText().trim()
        assertTrue(
            "road-food.sha256 should be one bare sha256 and nothing else",
            Regex("^[0-9a-f]{64}$").matches(pinned)
        )
        val actual = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        assertEquals(
            "src/main/assets/$ROAD_FOOD_ASSET does not match road-food.sha256. " +
                "Copy dugcanlift-kit/data/road-food.json and data/road-food.sha256 over together.",
            pinned,
            actual
        )
    }

}
