package com.latch.ocr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * FR-1228's arithmetic.
 *
 * **The rotation mapping is the part worth testing hardest**, because getting it wrong does not
 * throw or return nothing — it re-reads a different part of the photograph at high resolution and
 * hands back confident nonsense, which is indistinguishable from bad OCR and would be blamed on
 * ML Kit. Three coordinate frames meet in `sourceRect` and only arithmetic keeps them apart.
 */
class RereadRegionTest {

    // ---- the union ----------------------------------------------------------------------------

    @Test
    fun `no blocks is no region`() {
        assertNull(textUnion(emptyList()))
    }

    @Test
    fun `the union spans every block`() {
        val blocks = listOf(
            TextBlock("a", left = 100, top = 50, right = 300, bottom = 90),
            TextBlock("b", left = 80, top = 120, right = 250, bottom = 160),
        )
        assertEquals(PixelRect(80, 50, 300, 160), textUnion(blocks))
    }

    // ---- the margin ---------------------------------------------------------------------------

    @Test
    fun `the region is padded beyond what was read`() {
        // A character the first pass missed sits just outside the union — the `+` of a number, the
        // last letter of a domain. Cropping exactly to what was found is the one crop guaranteed
        // to preserve the first pass's mistakes.
        val padded = paddedRegion(PixelRect(100, 100, 300, 200), imageWidth = 1000, imageHeight = 1000)
        assertTrue(padded.left < 100 && padded.top < 100)
        assertTrue(padded.right > 300 && padded.bottom > 200)
    }

    @Test
    fun `the margin never leaves the image`() {
        val padded = paddedRegion(PixelRect(0, 0, 100, 100), imageWidth = 100, imageHeight = 100)
        assertEquals(PixelRect(0, 0, 100, 100), padded)
    }

    // ---- whether to bother --------------------------------------------------------------------

    @Test
    fun `a card already filling the frame is not re-read`() {
        // The guard that keeps the cost proportional: a good photograph pays nothing.
        assertFalse(rereadWorthwhile(currentWidth = 2000, availableWidth = 2040))
    }

    @Test
    fun `a card at a third of the frame is re-read`() {
        // 673 px today against 1346 available — the case the requirement exists for.
        assertTrue(rereadWorthwhile(currentWidth = 673, availableWidth = 1346))
    }

    @Test
    fun `an empty reading is never re-read`() {
        assertFalse(rereadWorthwhile(currentWidth = 0, availableWidth = 4000))
    }

    // ---- the three frames -----------------------------------------------------------------------

    @Test
    fun `with no rotation the mapping is only the sample size`() {
        assertEquals(
            PixelRect(200, 100, 600, 300),
            sourceRect(PixelRect(100, 50, 300, 150), sample = 2, rotationDegrees = 0,
                sourceWidth = 4000, sourceHeight = 3000),
        )
    }

    /**
     * The case the developer actually hit: a card photographed with the phone turned, so EXIF says
     * rotate 90 and ML Kit's boxes come back in a frame whose axes are swapped relative to the file.
     */
    @Test
    fun `a quarter turn swaps the axes rather than the numbers`() {
        // Source 4000x3000, sample 2 -> bitmap 2000x1500 -> upright 1500x2000.
        // The top-left of the upright frame is the bottom-left of the bitmap.
        // Derived rather than eyeballed, because the first attempt at this expectation was wrong
        // and the code was right. Rotating the bitmap 90 clockwise sends (x, y) to (H - y, x), so
        // undoing it gives bitmap x = upright y and bitmap y = H - upright x. With H = 1500 and an
        // upright x of 0..100 that is y = 1400..1500, and the sample of 2 doubles it to 2800..3000.
        val mapped = sourceRect(
            PixelRect(0, 0, 100, 200), sample = 2, rotationDegrees = 90,
            sourceWidth = 4000, sourceHeight = 3000,
        )
        assertEquals(PixelRect(left = 0, top = 2800, right = 400, bottom = 3000), mapped)
    }

    @Test
    fun `every rotation maps a full-frame region back to the whole source`() {
        // **The property that catches a sign error in any branch.** Whatever the rotation, asking
        // for the entire upright frame must ask for the entire file — so a mapping that is off by
        // a reflection or a swap shows up here rather than on somebody's card.
        listOf(0, 90, 180, 270).forEach { rotation ->
            val uprightW = if (rotation % 180 == 0) 2000 else 1500
            val uprightH = if (rotation % 180 == 0) 1500 else 2000
            assertEquals(
                PixelRect(0, 0, 4000, 3000),
                sourceRect(PixelRect(0, 0, uprightW, uprightH), sample = 2, rotationDegrees = rotation,
                    sourceWidth = 4000, sourceHeight = 3000),
                "rotation $rotation mapped the whole frame to the wrong rectangle",
            )
        }
    }

    @Test
    fun `every rotation keeps the region inside the source`() {
        listOf(0, 90, 180, 270).forEach { rotation ->
            val r = sourceRect(PixelRect(10, 20, 900, 1200), sample = 2, rotationDegrees = rotation,
                sourceWidth = 4000, sourceHeight = 3000)
            assertTrue(r.left in 0..4000 && r.right in 0..4000, "rotation $rotation left the frame")
            assertTrue(r.top in 0..3000 && r.bottom in 0..3000, "rotation $rotation left the frame")
            assertTrue(r.width > 0 && r.height > 0, "rotation $rotation produced an empty region")
        }
    }

    @Test
    fun `a negative or over-turned rotation is normalised rather than falling through`() {
        assertEquals(
            sourceRect(PixelRect(0, 0, 100, 200), 2, 90, 4000, 3000),
            sourceRect(PixelRect(0, 0, 100, 200), 2, 450, 4000, 3000),
        )
    }

    // ---- which reading to keep --------------------------------------------------------------------

    @Test
    fun `the second reading wins when it read more`() {
        assertEquals("a longer and better reading", betterReading("short", "a longer and better reading"))
    }

    @Test
    fun `the first reading is kept when the crop went wrong`() {
        // The failure this guards: the union comes from the first pass, so a first pass that found
        // only a corner would send the second to re-read that corner. Preferring the longer text
        // makes that cost nothing instead of losing what was already read.
        assertEquals("the whole card as first read", betterReading("the whole card as first read", "corner"))
    }

    @Test
    fun `a tie goes to the sharper image`() {
        assertEquals("second", betterReading("first!", "second"))
    }
}
