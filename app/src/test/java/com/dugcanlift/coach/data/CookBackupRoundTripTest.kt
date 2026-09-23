package com.dugcanlift.coach.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `recipes` and `meals` stopped being opaque cargo when Cook arrived. These pin
 * that the change took nothing with it.
 *
 * The guarantee `BackupCodec` makes is preservation by exclusion: every
 * top-level key it does not model is carried byte-for-byte, because a fixed
 * list of known keys is what silently destroyed a coach's data before (see
 * ENVELOPE_KEYS' own history). Modelling two of those keys is exactly the kind
 * of change that erodes the guarantee by accident.
 */
class CookBackupRoundTripTest {

    /** A Coach iOS v2 file carrying all four library arrays. */
    private val iosFile = """
        {
          "v": 2,
          "clients": [],
          "recipes": [
            { "id": "r1", "name": "Chili", "servings": 4,
              "ingredients": ["500 g beef mince"], "steps": ["Simmer"] }
          ],
          "meals": [
            { "id": "m1", "recipeID": "r1", "recipeName": "Chili",
              "dayKey": "2026-09-14", "meal": "dinner", "servings": 2, "clientID": "c1" }
          ],
          "routines": [ { "id": "t1", "name": "Push A", "exercises": [] } ],
          "sessions": [ { "id": "s1", "clientID": "c1", "dayKey": "2026-09-14", "routineID": "t1" } ]
        }
    """.trimIndent()

    /** The browser build's own export. Different keys, different inner shapes. */
    private val webFile = """
        {
          "v": 2,
          "clients": [],
          "settings": { "coachName": "Doug" },
          "recipes": [
            { "id": "r_7f3a", "name": "Chili", "servings": 4,
              "ingredients": [ { "rawText": "500 g beef mince" } ], "steps": [] }
          ],
          "plans": [ { "id": "p1", "clientId": "c1", "recipeId": "r_7f3a",
                       "date": "2026-09-14", "meal": "dinner", "servings": 2 } ],
          "workouts": [ { "id": "w1", "name": "Push A" } ],
          "sessions": []
        }
    """.trimIndent()

    // MARK: - The half that is now modelled

    @Test fun `an iOS file's recipes and meals decode into models`() {
        val result = BackupCodec.restore(iosFile)
        assertEquals(1, result.recipes.size)
        assertEquals("Chili", result.recipes.single().name)
        assertEquals(1, result.meals.size)
        assertEquals("c1", result.meals.single().clientId)
    }

    @Test fun `a web file's recipes decode too, ingredients and all`() {
        // The failure this guards: the web spells an ingredient
        // {"rawText": ...} where iOS writes a bare string. Reading only iOS's
        // spelling empties a web coach's whole ingredient list on first restore.
        val result = BackupCodec.restore(webFile)
        assertEquals(listOf("500 g beef mince"), result.recipes.single().rawIngredients)
    }

    // MARK: - The half that is still cargo

    @Test fun `routines and sessions are modelled now, not carried`() {
        // They were cargo until Train shipped. This is the assertion that
        // changed with it -- deliberately, and it is the only one here that did.
        val restored = BackupCodec.restore(iosFile)
        assertEquals("Push A", restored.routines.single().name)
        assertEquals(1, restored.sessions.size)
        assertTrue("and they have left the cargo bag",
                   restored.preservedLibrary?.has("routines") != true)
    }

    @Test fun `the modelled keys are no longer in the cargo`() {
        // Otherwise export would write each of them twice -- once from models,
        // once from the bag -- and the second would win.
        val preserved = BackupCodec.restore(iosFile).preservedLibrary
        assertTrue("recipes is modelled now", preserved?.has("recipes") != true)
        assertTrue("meals is modelled now", preserved?.has("meals") != true)
    }

    @Test fun `a web file's plans and workouts and settings stay cargo`() {
        // `plans` is the web's meal plan under a different name AND a different
        // shape. Half-understanding it here would be worse than carrying it.
        val preserved = BackupCodec.restore(webFile).preservedLibrary
        assertNotNull(preserved)
        assertEquals(1, preserved!!.getJSONArray("plans").length())
        assertEquals(1, preserved.getJSONArray("workouts").length())
        assertEquals("Doug", preserved.getJSONObject("settings").getString("coachName"))
    }

    // MARK: - Whole-file round trips

    @Test fun `an iOS file survives restore then export`() {
        val restored = BackupCodec.restore(iosFile)
        val out = JSONObject(
            BackupCodec.export(restored.clients, restored.preservedLibrary,
                               restored.recipes, restored.meals,
                               restored.routines, restored.sessions, emptyMap())
        )
        assertEquals("Chili", out.getJSONArray("recipes").getJSONObject(0).getString("name"))
        assertEquals("c1", out.getJSONArray("meals").getJSONObject(0).getString("clientID"))
        assertEquals("Push A", out.getJSONArray("routines").getJSONObject(0).getString("name"))
        assertEquals(1, out.getJSONArray("sessions").length())
    }

    @Test fun `a web file survives restore then export, losing only the spelling`() {
        val restored = BackupCodec.restore(webFile)
        val out = JSONObject(
            BackupCodec.export(restored.clients, restored.preservedLibrary,
                               restored.recipes, restored.meals,
                               restored.routines, restored.sessions, emptyMap())
        )
        // Recipes are rewritten in this app's own spelling...
        assertEquals("500 g beef mince",
                     out.getJSONArray("recipes").getJSONObject(0).getJSONArray("ingredients").getString(0))
        // ...and everything the app does not model is still there, unchanged.
        assertEquals(1, out.getJSONArray("plans").length())
        assertEquals("Doug", out.getJSONObject("settings").getString("coachName"))
    }

    @Test fun `a field a newer Coach iOS adds to a recipe survives the whole trip`() {
        val file = JSONObject(iosFile)
        file.getJSONArray("recipes").getJSONObject(0).put("sourceTranscript", "from a video")
        val restored = BackupCodec.restore(file.toString())
        val out = JSONObject(
            BackupCodec.export(restored.clients, restored.preservedLibrary,
                               restored.recipes, restored.meals,
                               restored.routines, restored.sessions, emptyMap())
        )
        assertEquals("from a video",
                     out.getJSONArray("recipes").getJSONObject(0).getString("sourceTranscript"))
    }

    @Test fun `a top-level key nobody has thought of survives`() {
        val file = JSONObject(iosFile).put("programs", org.json.JSONArray().put(JSONObject().put("id", "p9")))
        val restored = BackupCodec.restore(file.toString())
        val out = JSONObject(
            BackupCodec.export(restored.clients, restored.preservedLibrary,
                               restored.recipes, restored.meals,
                               restored.routines, restored.sessions, emptyMap())
        )
        assertEquals("p9", out.getJSONArray("programs").getJSONObject(0).getString("id"))
    }

    // MARK: - Absent is not empty

    @Test fun `a v1 file with no library at all still restores`() {
        val result = BackupCodec.restore("""{ "v": 1, "clients": [] }""")
        assertEquals(emptyList<Recipe>(), result.recipes)
        assertEquals(emptyList<PlannedMeal>(), result.meals)
    }

    @Test fun `an export with no recipes writes no recipes key`() {
        // Matches what this codec has always done for a library it had nothing
        // for, and is safe in both directions: iOS merges the library by id and
        // never deletes from it, so absent and empty mean the same thing there.
        val out = JSONObject(BackupCodec.export(emptyList(), null, emptyList(), emptyList(), emptyList(), emptyList(), emptyMap()))
        assertTrue("a v1 file must not sprout v2 keys on the way out", !out.has("recipes"))
        assertTrue(!out.has("meals"))
    }
}
