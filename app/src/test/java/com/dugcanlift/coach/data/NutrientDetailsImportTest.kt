package com.dugcanlift.coach.data

import com.dugcanlift.kit.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Saturated fat, sugar and sodium off a share link (SHARE-FORMAT "Saturated fat, sugar and sodium").
 * Every payload is built by the kit's own encoder, which is what LIFT Android ships -- so `fx` and
 * `fe` here are the real wire, not this file's reading of it.
 */
class NutrientDetailsImportTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun frag(days: List<ShareDay>) = ShareLinkCodec.encodeFragment(
        SharePayload(ShareClient("a1b2c3d4", "Doug", platform = "android"), null, "2026-09-01", "2026-09-13", 1, days)
    )

    private val oats = ShareFood("Oats", 2.0, 190.0, 6.5, 3.3, 34.0, 5.0, 0, NutrientDetails(saturatedFatG = 0.6, sugarG = 1.0, sodiumMg = 2.0))
    private val apple = ShareFood("Apple", 1.0, 95.0, 0.5, 0.3, 25.0, 4.4, 3, NutrientDetails(sugarG = 19.0))
    private val coffee = ShareFood("Coffee", 1.0, 2.0, 0.3, 0.0, 0.0, 0.0, 0)

    private fun itemisedDay(k: Int = 0): ShareDay {
        val foods = listOf(oats, apple, coffee)
        return ShareDay(k, null, null, null, null, emptyList(), null, foods, nutrientTotals = ShareNutrients.dayTotals(foods))
    }

    @Test fun `a day's fx is stored with its coverage`() {
        val repo = ClientRepository(tmp.root)
        ShareLinkImporter.import(frag(listOf(itemisedDay())), repo)
        val t = repo.get("a1b2c3d4")!!.days.single().nutrientTotals!!
        assertEquals(1.2, t.saturatedFatG!!, 1e-9)   // 0.6 x 2 servings
        assertEquals(21.0, t.sugarG!!, 1e-9)          // 1 x 2 + 19
        assertEquals(4.0, t.sodiumMg!!, 1e-9)
        assertEquals(3, t.foods)
        assertEquals(1, t.withSaturatedFat)
        assertEquals(2, t.withSugar)
        assertEquals(1, t.withSodium)
    }

    @Test fun `itemised details are stored as eaten, like the macros beside them`() {
        val repo = ClientRepository(tmp.root)
        ShareLinkImporter.import(frag(listOf(itemisedDay())), repo)
        val foods = repo.get("a1b2c3d4")!!.days.single().foodEntries
        assertEquals(1.2, foods[0].saturatedFatG!!, 1e-9)
        assertEquals(2.0, foods[0].sugarG!!, 1e-9)
        assertEquals(4.0, foods[0].sodiumMg!!, 1e-9)
        assertEquals(380.0, foods[0].calories, 1e-9)
        // A food that recorded only sugar keeps the other two unknown, not zero.
        assertNull(foods[1].saturatedFatG)
        assertEquals(19.0, foods[1].sugarG!!, 1e-9)
        assertNull(foods[1].sodiumMg)
        assertNull(foods[2].saturatedFatG); assertNull(foods[2].sugarG); assertNull(foods[2].sodiumMg)
    }

    @Test fun `a totals-only day carries fx without any itemised food`() {
        val repo = ClientRepository(tmp.root)
        val day = ShareDay(1, null, null, null, null, emptyList(), listOf(2100.0, 150.0, 70.0, 220.0, 30.0), null,
            nutrientTotals = ShareNutrientTotals(null, 48.0, 2310.0, 6, 0, 5, 6))
        ShareLinkImporter.import(frag(listOf(day)), repo)
        val t = repo.get("a1b2c3d4")!!.days.single().nutrientTotals!!
        assertNull(t.saturatedFatG)
        assertEquals(48.0, t.sugarG!!, 0.0)
        assertEquals(2310.0, t.sodiumMg!!, 0.0)
        assertEquals(6, t.foods); assertEquals(0, t.withSaturatedFat); assertEquals(5, t.withSugar); assertEquals(6, t.withSodium)
    }

    @Test fun `a link without fx or fe stores no details, and nothing reads as zero`() {
        val repo = ClientRepository(tmp.root)
        val foods = listOf(coffee)
        ShareLinkImporter.import(frag(listOf(ShareDay(0, null, null, null, null, emptyList(), null, foods))), repo)
        val day = repo.get("a1b2c3d4")!!.days.single()
        assertNull(day.nutrientTotals)
        assertNull(day.foodEntries.single().sodiumMg)
        assertFalse(day.toJson().has("nutrientTotals"))
        assertFalse(day.foodEntries.single().toJson().has("sodiumMg"))
    }

    @Test fun `days are replaced whole, so a resend without fx clears the old totals`() {
        val repo = ClientRepository(tmp.root)
        ShareLinkImporter.import(frag(listOf(itemisedDay())), repo)
        ShareLinkImporter.import(frag(listOf(ShareDay(0, null, null, null, null, emptyList(), null, listOf(coffee)))), repo)
        assertNull(repo.get("a1b2c3d4")!!.days.single().nutrientTotals)
    }

    @Test fun `details survive the client file round trip`() {
        val repo = ClientRepository(tmp.root)
        ShareLinkImporter.import(frag(listOf(itemisedDay())), repo)
        val client = repo.get("a1b2c3d4")!!
        assertEquals(client, Client.fromJson(JSONObject(client.toJson().toString())))
    }

    @Test fun `a client file written before these fields loads with them unknown`() {
        val old = JSONObject("""{"id":"a","name":"Doug","displayUnit":"lb","platform":null,"lastImportedAtEpochMs":0,"goal":null,
            "days":[{"dayKey":"2026-09-10","sessionName":null,"focus":null,"bodyweightLb":null,"steps":null,
            "foodCalories":null,"foodProteinG":null,"foodFatG":null,"foodCarbsG":null,"foodFiberG":null,"sets":[],
            "foodEntries":[{"foodName":"Oats","servings":1,"calories":190,"proteinG":6.5,"fatG":3.3,"carbsG":34,"fiberG":5,"meal":0}]}]}""")
        val day = Client.fromJson(old).days.single()
        assertNull(day.nutrientTotals)
        assertNull(day.foodEntries.single().sugarG)
    }

    @Test fun `a stored totals object with nothing known is no totals`() {
        assertNull(DayNutrientTotals.fromJson(JSONObject("""{"saturatedFatG":null,"sugarG":null,"sodiumMg":null,"foods":3,"withSaturatedFat":0,"withSugar":0,"withSodium":0}""")))
    }
}
