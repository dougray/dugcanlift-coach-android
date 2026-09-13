package com.dugcanlift.coach

import org.junit.Assert.*
import org.junit.Test

/**
 * Round 6 [I-8]: `c.i` comes off an untrusted link and used to be interpolated raw into the
 * `client/$id` route. An id containing `%` produced a route that did not round-trip back out of
 * `backStackEntry.arguments`, so that client could never be opened again.
 */
class RoutesTest {
    private fun segment(id: String) = Routes.client(id).removePrefix("client/")

    @Test fun `an id that breaks a raw route still round-trips through the client route`() {
        for (id in listOf("a1b2c3d4", "50%", "a/b", "a#b", "a b", "a?b=1", "../../escape", "ünïcode", "")) {
            val seg = segment(id)
            assertTrue(
                "the route segment must need no URI escaping at all: <$seg>",
                seg.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '_' || it == '-' }
            )
            assertEquals(id, Routes.decodeClientId(seg))
        }
    }

    @Test fun `a segment this app did not produce decodes to null rather than throwing`() {
        assertNull(Routes.decodeClientId("not a segment!!"))
    }
}
