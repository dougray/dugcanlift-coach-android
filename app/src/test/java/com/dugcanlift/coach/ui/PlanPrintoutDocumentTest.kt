package com.dugcanlift.coach.ui

import com.dugcanlift.coach.data.PlanPrintout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * The locale pattern the app really asks Android for. The words are pinned in
 * `PlanPrintoutTest` under that pattern; this checks they are what a US reader
 * gets. (Robolectric has no native `PdfDocument`, so the page itself is not
 * written here -- pagination is pure and pinned in `PlanPrintoutTest`.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlanPrintoutDocumentTest {

    @Test fun theDayTitleSkeletonReadsAsTheTestsPinIt() {
        val pattern = android.text.format.DateFormat.getBestDateTimePattern(Locale.US, "EEEdMMM")
        assertEquals("Mon, Oct 26", PlanPrintout.dayTitle("2026-10-26", Locale.US, pattern))
    }
}
