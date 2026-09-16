package com.dugcanlift.coach.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class InviteTextTest {
    @Test fun `no name does not say your coach twice`() {
        val text = inviteText("", "coach@example.com")
        assertFalse(text, text.contains("your coach, your coach"))
        assertEquals(true, text.startsWith("Hi! I'm your coach on LIFT. To share"))
    }

    @Test fun `a name reads as that name`() {
        assertEquals(true, inviteText(" Alex ", "a@b.co").startsWith("Hi! I'm Alex, your coach on LIFT."))
    }

    @Test fun `no email says to enter one`() {
        assertEquals(true, inviteText("Alex", "").endsWith("[enter your email above]"))
    }
}
