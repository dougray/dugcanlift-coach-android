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
    @Test fun `the LIFT Android fixture imports with servings applied`() {
        val repo = ClientRepository(tmp.root)
        val r = ShareLinkImporter.import(fixture("share-link-android.txt"), repo)
        assertTrue(r is ImportResult.Imported)
        val entries = repo.get((r as ImportResult.Imported).clientId)!!.days.flatMap { it.foodEntries }
        assertTrue(entries.any { it.servings != 1.0 && it.calories > 0 })
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

    // --- share-link-doc-example.txt: hand-built from the SHARE-FORMAT.md worked example, a third independent
    // encoder. Client b7f3a1c8 "Jordan Reyes", startDay 2026-06-24, day k=12 (warmup + two ordinary sets, one
    // itemized food at 1.5 servings) and day k=40 (ft totals only, no exercises or itemized food).
    @Test fun `the doc-example fixture decodes the SHARE-FORMAT spec's worked example`() {
        val raw = fixture("share-link-doc-example.txt")

        // h:71 is a JSON integer in the fixture; ShareClient.heightIn is Double? -- confirm it decodes to 71.0
        // rather than throwing (which would turn the whole import into ImportResult.Malformed).
        val decoded = ShareLinkCodec.decode(raw) as ShareDecodeResult.Success
        assertEquals(71.0, decoded.payload.client.heightIn!!, 0.0)

        val repo = ClientRepository(tmp.root)
        val r = ShareLinkImporter.import(raw, repo) as ImportResult.Imported
        assertEquals("b7f3a1c8", r.clientId); assertEquals("Jordan Reyes", r.clientName); assertEquals(2, r.daysImported)

        val c = repo.get("b7f3a1c8")!!
        assertEquals(2400, c.goal!!.calories); assertEquals(190, c.goal!!.proteinG); assertEquals(70, c.goal!!.fatG); assertEquals(220, c.goal!!.carbsG); assertEquals(34, c.goal!!.fiberG)

        val days = c.days.associateBy { it.dayKey }
        assertEquals(setOf("2026-07-06", "2026-08-03"), days.keys) // r=2026-06-24 plus k=12 and plus k=40

        val pushDay = days.getValue("2026-07-06")
        assertEquals(3, pushDay.sets.size)
        assertTrue(pushDay.sets[0].isWarmup); assertEquals(135.0, pushDay.sets[0].weightLb!!, 0.0); assertEquals(5, pushDay.sets[0].reps)
        assertFalse(pushDay.sets[1].isWarmup); assertEquals(185.0, pushDay.sets[1].weightLb!!, 0.0); assertEquals(5, pushDay.sets[1].reps); assertEquals(8.0, pushDay.sets[1].rpe!!, 0.0)
        assertFalse(pushDay.sets[2].isWarmup); assertEquals(205.0, pushDay.sets[2].weightLb!!, 0.0); assertEquals(3, pushDay.sets[2].reps); assertEquals(9.0, pushDay.sets[2].rpe!!, 0.0)

        assertEquals(1, pushDay.foodEntries.size)
        val food = pushDay.foodEntries[0]
        assertEquals(1.5, food.servings, 0.0)
        assertEquals(480.0, food.calories, 0.0); assertEquals(60.0, food.proteinG, 0.0); assertEquals(12.0, food.fatG, 0.0); assertEquals(18.0, food.carbsG, 0.0); assertEquals(3.0, food.fiberG, 0.0)

        val totalsDay = days.getValue("2026-08-03")
        assertTrue(totalsDay.sets.isEmpty()); assertTrue(totalsDay.foodEntries.isEmpty())
        assertEquals(2410.0, totalsDay.foodCalories!!, 0.0); assertEquals(188.0, totalsDay.foodProteinG!!, 0.0)
        assertEquals(71.0, totalsDay.foodFatG!!, 0.0); assertEquals(230.0, totalsDay.foodCarbsG!!, 0.0); assertEquals(33.0, totalsDay.foodFiberG!!, 0.0)
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
