package com.latch.android.ui

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals

/**
 * SRS 1.181. **The property worth pinning is that the bound actually binds.**
 *
 * A ceiling set above what the window could ever reach is not a weaker fix, it is *no fix* —
 * `weight` computes the same infinite share and the action row is clipped exactly as before,
 * while the code reads as though the problem were solved. That is the shape this project has
 * already recorded three times on this one sheet, so it is asserted rather than trusted.
 */
class SheetBoundsTest {

    @Test
    fun `the bound is below the tallest window the device can give`() {
        // Measured on the device the defect was diagnosed on: a 891dp screen, whose largest
        // observed sheet window was 826dp of content.
        assertTrue(
            sheetMaxHeightDp(891) < 826,
            "a bound at or above the achievable window height does nothing at all",
        )
    }

    @Test
    fun `the bound still leaves room for the content it exists to protect`() {
        // The failing sheet was chrome 88 + content 520 + actions 218. The bound has to leave
        // the actions their four lines *and* keep a usable column, or it trades one clipped
        // control for a field list nobody can read.
        val max = sheetMaxHeightDp(891)
        assertTrue(max - 88 - 218 > 400, "content would be squeezed to $max - 306")
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
