package com.dugcanlift.coach.ui

import org.junit.Assert.assertTrue
import org.junit.Test

/** Restore from Backup says what it will replace before the file picker opens. */
class RestoreWarningTest {
    @Test fun `the warning counts what goes`() {
        val text = restoreWarning(12, 30, 8)
        assertTrue(text, text.startsWith("Restoring replaces your 12 clients, 30 recipes and 8 routines"))
        assertTrue(text, text.contains("can't be undone"))
    }

    @Test fun `one of a thing is singular`() {
        val text = restoreWarning(1, 1, 1)
        assertTrue(text, text.contains("your 1 client, 1 recipe and 1 routine,"))
    }
}
