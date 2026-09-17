package com.dugcanlift.coach.ui

import com.dugcanlift.coach.data.ShoppingLine
import com.dugcanlift.kit.IngredientParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ShoppingLineTextTest {
    @Test fun `a counted item shows its number and no sentinel`() {
        val text = shoppingLineText(ShoppingLine("peppers", mapOf(IngredientParser.COUNT_UNIT to 3.0)))
        assertEquals("peppers — 3", text)
        assertFalse(text.contains(IngredientParser.COUNT_UNIT.first()))
    }

    @Test fun `real units, mixed units and unquantified lines read as before`() {
        assertEquals("lean beef mince — 450 g", shoppingLineText(ShoppingLine("lean beef mince", mapOf("g" to 450.0))))
        assertEquals("eggs — 2 + 100 g", shoppingLineText(ShoppingLine("eggs", linkedMapOf(IngredientParser.COUNT_UNIT to 2.0, "g" to 100.0))))
        assertEquals("salt — as needed", shoppingLineText(ShoppingLine("salt", emptyMap(), unquantified = 1)))
        assertEquals("oil — 1.5 tbsp + as needed", shoppingLineText(ShoppingLine("oil", mapOf("tbsp" to 1.5), unquantified = 1)))
    }
}
