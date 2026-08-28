package com.latch.ocr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The FR-215 and FR-207 decisions that can be made without a device.
 *
 * Everything asserted here is a pure function for the reason the setup reducer and the
 * parser are: a device test catches nothing until someone runs it, and there is no CI in
 * this repository to run one.
 */
class OcrDecisionsTest {

    // FR-207: the page cap, and the reporting that has to survive it.

    @Test
    fun `a short pdf is read whole and reports no cap`() {
        assertEquals(3, pagesToRead(total = 3))
        val coverage = PageCoverage(read = 3, total = 3)
        assertFalse(coverage.capped)
    }

    @Test
    fun `a long pdf stops at the cap and says so`() {
        assertEquals(PDF_PAGE_CAP, pagesToRead(total = 34))
        val coverage = PageCoverage(read = PDF_PAGE_CAP, total = 34)
        assertTrue(coverage.capped)
    }

    @Test
    fun `a pdf of exactly the cap is not reported as capped`() {
        // The boundary matters: "first 10 of 10 pages read" is a message about nothing, and
        // a user who sees it will reasonably think a page was dropped.
        assertEquals(10, pagesToRead(total = PDF_PAGE_CAP))
        assertFalse(PageCoverage(read = PDF_PAGE_CAP, total = PDF_PAGE_CAP).capped)
    }

    @Test
    fun `an empty pdf reads nothing rather than failing`() {
        assertEquals(0, pagesToRead(total = 0))
    }

    // FR-215: the downscale, bounded by pixels rather than by an edge.

    @Test
    fun `a phone screenshot is decoded at full resolution`() {
        // 1440 x 3120 is 4.5 MP. A longest-edge rule would have halved this and thrown away
        // the resolution that small text is read from — the case this bound exists to protect.
        assertEquals(1, sampleSizeFor(width = 1440, height = 3120))
    }

    @Test
    fun `a twelve megapixel photograph is halved`() {
        // 4032 x 3024 is 12.2 MP, 48 MB as ARGB_8888. Halved it is 3 MP, which is still ample
        // for a photographed document.
        assertEquals(2, sampleSizeFor(width = 4032, height = 3024))
    }

    @Test
    fun `sample size is always a power of two`() {
        val sizes = listOf(
            sampleSizeFor(8000, 6000),
            sampleSizeFor(16000, 12000),
            sampleSizeFor(1000, 1000),
        )
        sizes.forEach { size -> assertEquals(0, size and (size - 1), "$size is not a power of two") }
    }

    @Test
    fun `a degenerate image does not divide by zero`() {
        assertEquals(1, sampleSizeFor(width = 0, height = 0))
        assertEquals(1, sampleSizeFor(width = -1, height = 100))
    }

    // FR-207: the render scale, and its ceiling.

    @Test
    fun `an a4 page renders near two hundred dpi`() {
        // A4 is 595 x 842 points. 200/72 is a shade under 2.78.
        val scale = renderScaleFor(pageWidthPoints = 595, pageHeightPoints = 842)
        assertEquals(2.78f, scale, absoluteTolerance = 0.01f)
    }

    @Test
    fun `a very large page is clamped rather than asking for an impossible bitmap`() {
        // A0 is 2384 x 3370 points. At 2.78x that is 62 MP and no device allocates it.
        val scale = renderScaleFor(pageWidthPoints = 2384, pageHeightPoints = 3370)
        val pixels = (2384 * scale) * (3370 * scale)
        assertTrue(pixels <= MAX_DECODED_PIXELS, "clamped to $pixels pixels")
        assertTrue(scale < 2.78f, "a large page should render smaller, not larger")
    }

    @Test
    fun `a degenerate page does not produce a nonsense scale`() {
        assertEquals(1f, renderScaleFor(pageWidthPoints = 0, pageHeightPoints = 0))
    }

    // FR-215: EXIF rotation, which ML Kit is told about rather than handed pre-applied.

    @Test
    fun `a screenshot with no orientation tag is not rotated`() {
        assertEquals(0, rotationDegreesFor(1)) // ORIENTATION_NORMAL
        assertEquals(0, rotationDegreesFor(0)) // ORIENTATION_UNDEFINED
    }

    @Test
    fun `the three rotations map to their degrees`() {
        assertEquals(90, rotationDegreesFor(6))
        assertEquals(180, rotationDegreesFor(3))
        assertEquals(270, rotationDegreesFor(8))
    }

    @Test
    fun `a mirrored orientation keeps its axis`() {
        // ML Kit cannot be told about a flip. Getting the axis right is all that is available,
        // and it is better than treating the tag as absent.
        assertEquals(90, rotationDegreesFor(5))  // TRANSPOSE
        assertEquals(270, rotationDegreesFor(7)) // TRANSVERSE
        assertEquals(180, rotationDegreesFor(4)) // FLIP_VERTICAL
    }

    // FR-207: how pages become one capture, which the parser then reads.

    @Test
    fun `pages are separated by a blank line`() {
        // The separator is load-bearing rather than cosmetic: :parser reads a blank line as a
        // boundary, and running the last line of one page into the first of the next is how a
        // date acquires a neighbour it never had on the page.
        assertEquals("one\n\ntwo", joinPages(listOf("one", "two")))
    }

    @Test
    fun `pages that recognised nothing are dropped rather than left as gaps`() {
        assertEquals("one\n\nthree", joinPages(listOf("one", "   ", "", "three")))
    }

    @Test
    fun `a pdf that recognised nothing at all joins to nothing`() {
        assertEquals("", joinPages(listOf("", "  ")))
        assertEquals("", joinPages(emptyList()))
    }
}
