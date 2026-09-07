package com.latch.android.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * SRS 1.182. **The one property worth pinning is that the bound actually binds.**
 *
 * A ceiling set above what the window could ever reach is not a weaker guard, it is *no guard* —
 * `weight` computes the same infinite share and anything laid out below the scroll is clipped
 * exactly as before, while the code reads as though the sheet were bounded. That is the shape
 * this project has now recorded four times on this one screen, so it is asserted rather than
 * assumed.
 */
class SheetBoundsTest {

    @Test
    fun `the bound is below the tallest window the device can give`() {
        // Measured on the device this was diagnosed on: a 891dp screen, whose largest observed
        // sheet window was 826dp of content.
        assertTrue(
            sheetMaxHeightDp(891) < 826,
            "a bound at or above the achievable window height does nothing at all",
        )
    }

    @Test
    fun `the bound leaves more room than the sheet actually asks for`() {
        // The sheet is chrome + a content column capped at SHEET_CONTENT_MAX + an action row.
        // The guard must sit *above* that, or it would start squeezing a sheet that already
        // fits and turn a working layout into a scrolling one.
        val chromeAndActions = 88 + 60
        assertTrue(
            sheetMaxHeightDp(891) > 520 + chromeAndActions,
            "the guard must be inert for a sheet that fits, not a second cap on it",
        )
    }

    @Test
    fun `a short screen keeps a floor rather than collapsing`() {
        assertEquals(320, sheetMaxHeightDp(360))
        assertEquals(320, sheetMaxHeightDp(200))
    }

    @Test
    fun `a tall screen scales with it rather than being pinned to a constant`() {
        assertTrue(sheetMaxHeightDp(1000) > sheetMaxHeightDp(891))
    }
}
