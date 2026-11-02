package com.dugcanlift.coach.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * [formatShortDay] with the pattern Android really gives each locale: en-US reads exactly as it did
 * with the old fixed "MMM d", and a German reader gets the day first, as Germans write it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OutdoorDisplaySkeletonTest {

    @Test fun `en-US is unchanged`() {
        assertEquals("Sep 13", formatShortDay("2026-09-13", Locale.US))
        assertEquals("Jan 1", formatShortDay("2027-01-01", Locale.US))
        assertEquals("2026-9-3", formatShortDay("2026-9-3", Locale.US))
    }

    @Test fun `another locale gets its own order`() {
        assertEquals("13. Sept.", formatShortDay("2026-09-13", Locale.GERMANY))
        assertEquals("13 sept.", formatShortDay("2026-09-13", Locale.FRANCE))
    }
}
