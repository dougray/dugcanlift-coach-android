package com.dugcanlift.coach.ui

import com.dugcanlift.coach.data.LB_PER_KG
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The routine editor takes one exercise per line rather than presenting a
 * multi-screen builder. That line has rules, and this is where they live.
 */
class TrainScreenParsingTest {

    @Test fun `a full line becomes an exercise with its prescribed sets`() {
        val ex = parseExercises("Bench | Barbell | 3 x 8 @ 60").single()
        assertEquals("Bench", ex.name)
        assertEquals("Barbell", ex.equipment)
        assertEquals(3, ex.sets.size)
        assertEquals(8, ex.sets.first().targetReps)
        assertEquals(60.0, ex.sets.first().targetWeightKg!!, 0.001)
    }

    @Test fun `the weight is kilograms, as stored`() {
        // Not pounds. The whole Train data layer is kilograms because Coach iOS
        // stores kilograms; a number typed here must mean the same thing.
        val ex = parseExercises("Squat | Barbell | 1 x 5 @ 100").single()
        assertEquals(100.0, ex.sets.first().targetWeightKg!!, 0.001)
        assertEquals(220.46, ex.sets.first().targetWeightKg!! * LB_PER_KG, 0.01)
    }

    @Test fun `a name on its own is an exercise with no prescription`() {
        // Legitimate: "do some pull-ups" is a thing a coach writes down.
        val ex = parseExercises("Pull Up").single()
        assertEquals("Pull Up", ex.name)
        assertEquals("", ex.equipment)
        assertTrue(ex.sets.isEmpty())
    }

    @Test fun `equipment without a scheme still parses`() {
        val ex = parseExercises("Row | Cable").single()
        assertEquals("Row", ex.name)
        assertEquals("Cable", ex.equipment)
        assertTrue(ex.sets.isEmpty())
    }

    @Test fun `sets without a load are bodyweight, not zero kilograms`() {
        val ex = parseExercises("Push Up | Bodyweight | 3 x 12").single()
        assertEquals(3, ex.sets.size)
        assertEquals(12, ex.sets.first().targetReps)
        assertNull("no weight written means no weight, not 0 kg", ex.sets.first().targetWeightKg)
    }

    @Test fun `blank lines are skipped rather than becoming nameless exercises`() {
        val out = parseExercises("Bench | Barbell | 3 x 8\n\n   \nSquat | Barbell | 5 x 5")
        assertEquals(2, out.size)
        assertEquals(listOf("Bench", "Squat"), out.map { it.name })
    }

    @Test fun `a line with no name is dropped`() {
        assertTrue(parseExercises(" | Barbell | 3 x 8").isEmpty())
    }

    @Test fun `an unreadable scheme leaves the exercise, not an exception`() {
        val ex = parseExercises("Bench | Barbell | as many as you can").single()
        assertEquals("Bench", ex.name)
        assertTrue("nothing numeric to read, so no sets", ex.sets.isEmpty())
    }

    @Test fun `a capital X works as well as a lowercase one`() {
        assertEquals(4, parseExercises("Bench | Barbell | 4 X 6").single().sets.size)
    }

    @Test fun `whitespace around every part is trimmed`() {
        val ex = parseExercises("   Bench   |   Barbell   |   3 x 8 @ 60   ").single()
        assertEquals("Bench", ex.name)
        assertEquals("Barbell", ex.equipment)
        assertEquals(60.0, ex.sets.first().targetWeightKg!!, 0.001)
    }
}
