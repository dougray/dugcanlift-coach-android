package com.dugcanlift.coach.data
import org.junit.Assert.*
import org.junit.Test

class LinkFragmentTest {
    @Test fun `a full share URL reduces to its fragment`() =
        assertEquals("1zABCDEF", fragmentFrom("https://www.dugcanlift.com/coach/#1zABCDEF"))

    @Test fun `a bare fragment passes through unchanged`() =
        assertEquals("1zABCDEF", fragmentFrom("1zABCDEF"))

    @Test fun `trailing whitespace after the fragment is trimmed`() =
        assertEquals("1zABCDEF", fragmentFrom("https://www.dugcanlift.com/coach/#1zABCDEF   \n"))

    @Test fun `only the text after the last hash is kept when there are multiple`() =
        assertEquals("last", fragmentFrom("https://example.com/#first#second#last"))

    @Test fun `surrounding whitespace around a bare fragment is trimmed`() =
        assertEquals("1zABCDEF", fragmentFrom("  1zABCDEF  "))

    @Test fun `an empty string yields an empty fragment`() =
        assertEquals("", fragmentFrom("   "))
}
