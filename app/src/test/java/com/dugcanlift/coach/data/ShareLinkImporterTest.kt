package com.dugcanlift.coach.data
import com.dugcanlift.kit.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ShareLinkImporterTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun fixture(n: String) = javaClass.getResourceAsStream("/fixtures/$n")!!.bufferedReader().readText().trim()
    private fun frag(p: SharePayload) = ShareLinkCodec.encodeFragment(p)
    private fun payload(days: List<ShareDay>, goal: ShareGoal? = null) = SharePayload(ShareClient("a1b2c3d4", "Doug", platform = "ios"), goal, "2026-09-01", "2026-09-13", 1, days)
    private fun day(k: Int, sets: List<ShareSet> = emptyList(), food: List<ShareFood>? = null, ft: List<Double>? = null) =
        ShareDay(k, null, null, null, null, if (sets.isEmpty()) emptyList() else listOf(ShareExercise("Back Squat", "Barbell", sets)), ft, food)

    @Test fun `creates a new client from the wire and resolves the day key from r plus k`() {
        val repo = ClientRepository(tmp.root)
        val r = ShareLinkImporter.import(frag(payload(listOf(day(2, listOf(ShareSet(225.0, 5, null, null, null, false)))))), repo) as ImportResult.Imported
        assertEquals("a1b2c3d4", r.clientId); assertEquals("2026-09-03", repo.get("a1b2c3d4")!!.days.single().dayKey)
    }
    @Test fun `a second import reuses the client and replaces the whole day`() {
        val repo = ClientRepository(tmp.root)
        ShareLinkImporter.import(frag(payload(listOf(day(2, listOf(ShareSet(225.0, 5, null, null, null, false), ShareSet(225.0, 5, null, null, null, false)))))), repo)
        ShareLinkImporter.import(frag(payload(listOf(day(2, listOf(ShareSet(245.0, 3, null, null, null, false)))))), repo)
        val d = repo.get("a1b2c3d4")!!.days.single(); assertEquals(1, d.sets.size); assertEquals(245.0, d.sets[0].weightLb!!, 0.0)
        assertEquals(1, repo.all().size)
    }
    @Test fun `days outside the new payload are untouched`() {
        val repo = ClientRepository(tmp.root)
        ShareLinkImporter.import(frag(payload(listOf(day(1, listOf(ShareSet(100.0, 5, null, null, null, false))), day(2, listOf(ShareSet(200.0, 5, null, null, null, false)))))), repo)
        ShareLinkImporter.import(frag(payload(listOf(day(2, listOf(ShareSet(300.0, 5, null, null, null, false)))))), repo)
        assertEquals(listOf("2026-09-02", "2026-09-03"), repo.get("a1b2c3d4")!!.days.map { it.dayKey }.sorted())
    }
    @Test fun `itemized food macros are multiplied by servings`() {
        val repo = ClientRepository(tmp.root)
        ShareLinkImporter.import(frag(payload(listOf(day(0, food = listOf(ShareFood("Oats", 2.0, 190.0, 6.5, 3.3, 34.0, 5.0, 0)))))), repo)
        val f = repo.get("a1b2c3d4")!!.days[0].foodEntries[0]
        assertEquals(380.0, f.calories, 0.0); assertEquals(13.0, f.proteinG, 0.0); assertEquals(2.0, f.servings, 0.0)
    }
    // Literal values decoded from share-link-android.txt: r=2026-08-16, x=["Back Squat|Barbell","Cable
    // Row|Cable"], fd=["Greek Yogurt","Brown Rice","Chicken Breast"]; day k=24 sets [225,5],[225,5],[245,3]
    // and food [0,2,100,17,0,6,0,0]; day k=25 food only [1,2,216,5,2,45,3,2]; day k=26 sets [90,12] and
    // [null,null,8.5] and food [2,2,165,31,4,0,0,1]. Itemized macros are per-serving on the wire, so the
    // stored values below are the wire numbers times each entry's servings (2.0 throughout this fixture).
    @Test fun `the LIFT Android fixture decodes to literal values, not just servings not equal to 1`() {
        val repo = ClientRepository(tmp.root)
        val r = ShareLinkImporter.import(fixture("share-link-android.txt"), repo) as ImportResult.Imported
        assertEquals("a1b2c3d4", r.clientId); assertEquals(3, r.daysImported)

        val c = repo.get("a1b2c3d4")!!
        assertEquals(2400, c.goal!!.calories); assertEquals(180, c.goal!!.proteinG); assertEquals(70, c.goal!!.fatG); assertEquals(220, c.goal!!.carbsG); assertEquals(35, c.goal!!.fiberG)

        val days = c.days.associateBy { it.dayKey }
        assertEquals(setOf("2026-09-09", "2026-09-10", "2026-09-11"), days.keys) // r=2026-08-16 plus k=24,25,26

        val squatDay = days.getValue("2026-09-09")
        assertEquals("Squat Day", squatDay.sessionName); assertEquals("BODYBUILDING", squatDay.focus)
        assertEquals(3, squatDay.sets.size)
        assertEquals("Back Squat", squatDay.sets[0].exerciseName); assertEquals("Barbell", squatDay.sets[0].equipment)
        assertEquals(225.0, squatDay.sets[0].weightLb!!, 0.0); assertEquals(5, squatDay.sets[0].reps); assertFalse(squatDay.sets[0].isWarmup)
        assertEquals(225.0, squatDay.sets[1].weightLb!!, 0.0); assertEquals(5, squatDay.sets[1].reps)
        assertEquals(245.0, squatDay.sets[2].weightLb!!, 0.0); assertEquals(3, squatDay.sets[2].reps)
        assertEquals(1, squatDay.foodEntries.size)
        val yogurt = squatDay.foodEntries[0]
        assertEquals("Greek Yogurt", yogurt.foodName); assertEquals(2.0, yogurt.servings, 0.0)
        assertEquals(200.0, yogurt.calories, 0.0); assertEquals(34.0, yogurt.proteinG, 0.0); assertEquals(0.0, yogurt.fatG, 0.0)
        assertEquals(12.0, yogurt.carbsG, 0.0); assertEquals(0.0, yogurt.fiberG, 0.0); assertEquals(0, yogurt.meal)

        val riceDay = days.getValue("2026-09-10")
        assertTrue(riceDay.sets.isEmpty())
        assertEquals(1, riceDay.foodEntries.size)
        val rice = riceDay.foodEntries[0]
        assertEquals("Brown Rice", rice.foodName); assertEquals(2.0, rice.servings, 0.0)
        assertEquals(432.0, rice.calories, 0.0); assertEquals(10.0, rice.proteinG, 0.0); assertEquals(4.0, rice.fatG, 0.0)
        assertEquals(90.0, rice.carbsG, 0.0); assertEquals(6.0, rice.fiberG, 0.0); assertEquals(2, rice.meal)

        val accessoryDay = days.getValue("2026-09-11")
        assertEquals("Accessory Day", accessoryDay.sessionName); assertEquals("BODYBUILDING", accessoryDay.focus)
        assertEquals(2, accessoryDay.sets.size)
        assertEquals("Cable Row", accessoryDay.sets[0].exerciseName); assertEquals("Cable", accessoryDay.sets[0].equipment)
        assertEquals(90.0, accessoryDay.sets[0].weightLb!!, 0.0); assertEquals(12, accessoryDay.sets[0].reps)
        assertNull(accessoryDay.sets[1].weightLb); assertNull(accessoryDay.sets[1].reps); assertEquals(8.5, accessoryDay.sets[1].rpe!!, 0.0)
        assertEquals(1, accessoryDay.foodEntries.size)
        val chicken = accessoryDay.foodEntries[0]
        assertEquals("Chicken Breast", chicken.foodName); assertEquals(2.0, chicken.servings, 0.0)
        assertEquals(330.0, chicken.calories, 0.0); assertEquals(62.0, chicken.proteinG, 0.0); assertEquals(8.0, chicken.fatG, 0.0)
        assertEquals(0.0, chicken.carbsG, 0.0); assertEquals(0.0, chicken.fiberG, 0.0); assertEquals(1, chicken.meal)
    }
    @Test fun `a payload that omits platform retains the client's existing platform`() {
        val repo = ClientRepository(tmp.root)
        ShareLinkImporter.import(frag(payload(listOf(day(0, listOf(ShareSet(135.0, 5, null, null, null, false)))))), repo)
        assertEquals("ios", repo.get("a1b2c3d4")!!.platform)

        val noPlatform = SharePayload(ShareClient("a1b2c3d4", "Doug", platform = null), null, "2026-09-01", "2026-09-13", 1, emptyList())
        ShareLinkImporter.import(frag(noPlatform), repo)
        assertEquals("ios", repo.get("a1b2c3d4")!!.platform)
    }
    @Test fun `the goal replaces and warmups are kept as flagged sets`() {
        val repo = ClientRepository(tmp.root)
        ShareLinkImporter.import(frag(payload(listOf(day(0, listOf(ShareSet(135.0, 5, null, null, null, true)))), ShareGoal(2400, 190, 70, 220, 34))), repo)
        ShareLinkImporter.import(frag(payload(emptyList(), ShareGoal(2200, 180, 60, 200, 30))), repo)
        val c = repo.get("a1b2c3d4")!!; assertEquals(2200, c.goal!!.calories); assertTrue(c.days[0].sets[0].isWarmup)
    }
    @Test fun `unsupported and malformed fragments do not touch the roster`() {
        val repo = ClientRepository(tmp.root)
        assertEquals(ImportResult.UnsupportedVersion, ShareLinkImporter.import("2zAAAA", repo))
        assertEquals(ImportResult.Malformed, ShareLinkImporter.import("1zNOT", repo)); assertTrue(repo.all().isEmpty())
    }

    // --- share-link-doc-example.txt: the SHARE-FORMAT.md worked example itself, corrected. The doc's
    // example day carries `w`, `ft` AND `f` together on ONE day (k=12) -- a day with both daily totals
    // and itemized food, which is the whole point of the fixture: the importer must store the two
    // independently, with neither summing into the other. Client b7f3a1c8 "Jordan Reyes", startDay
    // 2026-06-24, so k=12 lands on 2026-07-06.
    @Test fun `the doc-example fixture decodes the SHARE-FORMAT spec's worked example, totals and itemized food together`() {
        val raw = fixture("share-link-doc-example.txt")

        // h:71 is a JSON integer in the fixture; ShareClient.heightIn is Double? -- confirm it decodes to 71.0
        // rather than throwing (which would turn the whole import into ImportResult.Malformed).
        val decoded = ShareLinkCodec.decode(raw) as ShareDecodeResult.Success
        assertEquals(71.0, decoded.payload.client.heightIn!!, 0.0)

        val repo = ClientRepository(tmp.root)
        val r = ShareLinkImporter.import(raw, repo) as ImportResult.Imported
        assertEquals("b7f3a1c8", r.clientId); assertEquals("Jordan Reyes", r.clientName); assertEquals(1, r.daysImported)

        val c = repo.get("b7f3a1c8")!!
        assertEquals(2400, c.goal!!.calories); assertEquals(190, c.goal!!.proteinG); assertEquals(70, c.goal!!.fatG); assertEquals(220, c.goal!!.carbsG); assertEquals(34, c.goal!!.fiberG)

        val days = c.days.associateBy { it.dayKey }
        assertEquals(setOf("2026-07-06"), days.keys) // r=2026-06-24 plus k=12

        val pushDay = days.getValue("2026-07-06")
        assertEquals(3, pushDay.sets.size)
        assertTrue(pushDay.sets[0].isWarmup); assertEquals(135.0, pushDay.sets[0].weightLb!!, 0.0); assertEquals(5, pushDay.sets[0].reps)
        assertFalse(pushDay.sets[1].isWarmup); assertEquals(185.0, pushDay.sets[1].weightLb!!, 0.0); assertEquals(5, pushDay.sets[1].reps); assertEquals(8.0, pushDay.sets[1].rpe!!, 0.0)
        assertFalse(pushDay.sets[2].isWarmup); assertEquals(205.0, pushDay.sets[2].weightLb!!, 0.0); assertEquals(3, pushDay.sets[2].reps); assertEquals(9.0, pushDay.sets[2].rpe!!, 0.0)

        // ft totals -- present alongside the itemized food below, independently stored.
        assertEquals(2410.0, pushDay.foodCalories!!, 0.0); assertEquals(188.0, pushDay.foodProteinG!!, 0.0)
        assertEquals(71.0, pushDay.foodFatG!!, 0.0); assertEquals(230.0, pushDay.foodCarbsG!!, 0.0); assertEquals(33.0, pushDay.foodFiberG!!, 0.0)

        // itemized food -- the doc's one entry, per-serving wire macros [320,40,8,12,2] at 1.5 servings.
        assertEquals(1, pushDay.foodEntries.size)
        val food = pushDay.foodEntries[0]
        assertEquals(1.5, food.servings, 0.0)
        assertEquals(480.0, food.calories, 0.0); assertEquals(60.0, food.proteinG, 0.0); assertEquals(12.0, food.fatG, 0.0); assertEquals(18.0, food.carbsG, 0.0); assertEquals(3.0, food.fiberG, 0.0)
    }

    // A totals-only day (ft, no exercises, no itemized food) -- hand-built, NOT the spec's worked example
    // (which now carries w+ft+f together on one day; see the fixture test above).
    @Test fun `a hand-built totals-only day stores ft with no sets and no itemized food`() {
        val repo = ClientRepository(tmp.root)
        ShareLinkImporter.import(frag(payload(listOf(day(0, ft = listOf(2410.0, 188.0, 71.0, 230.0, 33.0))))), repo)
        val d = repo.get("a1b2c3d4")!!.days.single()
        assertTrue(d.sets.isEmpty()); assertTrue(d.foodEntries.isEmpty())
        assertEquals(2410.0, d.foodCalories!!, 0.0); assertEquals(188.0, d.foodProteinG!!, 0.0)
        assertEquals(71.0, d.foodFatG!!, 0.0); assertEquals(230.0, d.foodCarbsG!!, 0.0); assertEquals(33.0, d.foodFiberG!!, 0.0)
    }

    // No share-link-ios.txt fixture exists (needs Doug's phone). LIFT iOS always sends servings=1 for food;
    // build that payload by hand and confirm multiplying by 1.0 does not double the wire macros.
    @Test fun `LIFT iOS sends servings 1, so stored macros equal the wire macros with no doubling`() {
        val repo = ClientRepository(tmp.root)
        val iosPayload = SharePayload(
            ShareClient("c9d8e7f6", "Iris Lin", platform = "ios"),
            null, "2026-09-01", "2026-09-01", 1,
            listOf(ShareDay(0, null, null, null, null, emptyList(), null, listOf(ShareFood("Almonds", 1.0, 164.0, 6.0, 14.0, 6.0, 3.5, 3))))
        )
        ShareLinkImporter.import(frag(iosPayload), repo)
        val f = repo.get("c9d8e7f6")!!.days.single().foodEntries.single()
        assertEquals(1.0, f.servings, 0.0)
        assertEquals(164.0, f.calories, 0.0); assertEquals(6.0, f.proteinG, 0.0); assertEquals(14.0, f.fatG, 0.0); assertEquals(6.0, f.carbsG, 0.0); assertEquals(3.5, f.fiberG, 0.0)
    }
}
