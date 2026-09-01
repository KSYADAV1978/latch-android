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

    // NFR-102 / FR-207: "Reading page N of M…" while a document is read.

    @Test
    fun `progress counts from one to the number of pages that will be read`() {
        assertEquals(
            listOf(PageProgress(1, 3), PageProgress(2, 3), PageProgress(3, 3)),
            pageProgressSteps(total = 3),
        )
    }

    @Test
    fun `progress counts to the cap, not to the size of the document`() {
        // The failure this pins: counting to 34 while stopping at 10 shows a bar that never
        // fills. FR-207's cap is reported afterwards, by capture_pdf_capped, not here.
        val steps = pageProgressSteps(total = 34)
        assertEquals(PDF_PAGE_CAP, steps.size)
        assertEquals(PageProgress(PDF_PAGE_CAP, PDF_PAGE_CAP), steps.last())
    }

    @Test
    fun `a document with no pages reports no progress at all`() {
        assertEquals(emptyList(), pageProgressSteps(total = 0))
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

    // FR-215: which recogniser owns which block.

    private fun block(text: String, top: Int, left: Int = 0, height: Int = 40, width: Int = 300) =
        TextBlock(text, left, top, left + width, top + height)

    @Test
    fun `a latin screenshot is read entirely by the latin model`() {
        // The Devanagari pass returns its own version of every block, and none of it is kept:
        // no Devanagari codepoint anywhere means no region it can prove ownership of. This is
        // the case the switch was made for - no Bengali character set in play.
        val latin = listOf(block("Trip from 1 October", 0), block("to 5 October 2027", 50))
        val devanagari = listOf(block("Trip from 10ctober", 0), block("to 5 ০ctobe 2027", 50))

        val merged = mergeByScript(latin, devanagari)

        assertEquals(latin, merged)
        assertFalse(merged.any { it.text.contains('০') }, "a Bengali digit survived into Latin text")
    }

    @Test
    fun `a hindi screenshot is read by the devanagari model throughout`() {
        val devanagari = listOf(block("सोमवार को", 0))
        // What the Latin model returns for Devanagari glyphs is plausible Latin garbage, not
        // nothing - which is exactly why the test is applied to the Devanagari result.
        val latin = listOf(block("HIRER cbl", 0))

        val merged = mergeByScript(latin, devanagari)

        assertEquals(1, merged.size)
        assertTrue(hasDevanagari(merged.single().text))
    }

    @Test
    fun `a mixed screenshot is read per block by whichever model suits it`() {
        val latinRow = block("Fees due 20/09/2027", 100)
        val hindiRow = block("बैठक सोमवार", 200)

        val merged = mergeByScript(
            latin = listOf(latinRow, block("dSch RImah", 200)),
            devanagari = listOf(block("Fees due 2O/O9/2O27", 100), hindiRow),
        )

        assertTrue(merged.contains(latinRow), "the Latin row did not come from the Latin model")
        assertTrue(merged.contains(hindiRow), "the Hindi row did not come from the Devanagari model")
        assertEquals(2, merged.size, "the overlapping duplicate was not dropped")
    }

    @Test
    fun `a hallucinated devanagari codepoint keeps the wrong pass, and that is accepted`() {
        // The recorded residual. It costs one block rather than the capture, and this test
        // exists so the behaviour is deliberate rather than discovered.
        val latinTruth = block("Fees due 20/09/2027", 0)
        val hallucinated = block("Fees due 20/09/2०27", 0)

        val merged = mergeByScript(listOf(latinTruth), listOf(hallucinated))

        assertEquals(listOf(hallucinated), merged)
    }

    // FR-215: a screenshot's furniture is not its content.

    @Test
    fun `a status bar clock is dropped from a full-height screenshot`() {
        // The observed defect: 10:42 is a status-bar clock, it satisfies FR-503's time
        // formats exactly as a written time does, and the parser paired it with the nearest
        // date - turning a Task into an Event at an hour nobody wrote.
        val clock = TextBlock("10:42", left = 40, top = 20, right = 200, bottom = 70)
        val message = block("Yes. PTM on Monday 14 September 2026.", top = 600)

        val kept = withoutTopChrome(listOf(clock, message), imageHeight = 3120)

        assertEquals(listOf(message), kept)
    }

    @Test
    fun `a block that begins in the band but continues below it survives`() {
        // The risk this rule carries, and the reason it is wholly-within rather than
        // overlapping: discarding a real first line would be worse than keeping a clock.
        val straddling = TextBlock("Fees due 20/09/2027", left = 40, top = 100, right = 900, bottom = 200)

        val kept = withoutTopChrome(listOf(straddling), imageHeight = 3120)

        assertEquals(listOf(straddling), kept, "a block reaching past the band is content")
    }

    @Test
    fun `the rule is inert on a short image, so a cropped screenshot is untouched`() {
        // The guarantee stated in SRS 1.41 and held here. At 900px the band is 36px, thinner
        // than a line of text, so nothing can sit wholly inside it. testdata/fr215/
        // three-dates-cropped.png is the device-side fixture for the same property.
        val firstLine = TextBlock("Thanks. Did the school send", left = 60, top = 0, right = 800, bottom = 50)

        val kept = withoutTopChrome(listOf(firstLine), imageHeight = 900)

        assertEquals(listOf(firstLine), kept, "a cropped screenshot lost its first line")
    }

    @Test
    fun `the band is a fraction, so it scales with the screen`() {
        val clock = TextBlock("09:41", left = 40, top = 10, right = 200, bottom = 60)

        // Tall screen: 4% is 125px, the clock is inside it.
        assertTrue(withoutTopChrome(listOf(clock), imageHeight = 3120).isEmpty())
        // Short screen: 4% is 40px, and the same block is no longer wholly inside.
        assertEquals(listOf(clock), withoutTopChrome(listOf(clock), imageHeight = 1000))
    }

    @Test
    fun `a degenerate height changes nothing`() {
        val any = block("something", top = 0)
        assertEquals(listOf(any), withoutTopChrome(listOf(any), imageHeight = 0))
    }

    // §7.2: the order blocks are assembled in, which item_key depends on.

    @Test
    fun `blocks are ordered by geometry, not by the order ml kit returned them`() {
        // The observed case: a chat screenshot came back with an early bubble late and the
        // last bubble later still, so FR-505's earliest-mention tiebreak was deciding on an
        // order that was not the writer's.
        val outOfOrder = listOf(
            block("Ok noted", top = 700),
            block("Paid the uniform bill", top = 100),
            block("Fees due 20/09/2027", top = 400),
            block("Thanks", top = 250),
        )

        val ordered = inReadingOrder(outOfOrder).map { it.text }

        assertEquals(listOf("Paid the uniform bill", "Thanks", "Fees due 20/09/2027", "Ok noted"), ordered)
    }

    @Test
    fun `blocks sharing a row are ordered left to right`() {
        val right = block("second", top = 100, left = 800, width = 200)
        val left = block("first", top = 105, left = 40, width = 200)

        assertEquals(listOf("first", "second"), inReadingOrder(listOf(right, left)).map { it.text })
    }

    @Test
    fun `blocks that merely come close vertically stay in separate rows`() {
        // A chat's bubbles alternate sides and must not be welded into one row just because
        // their boxes graze each other.
        val upper = block("upper", top = 100, left = 40, height = 40)
        val lower = block("lower", top = 135, left = 800, height = 40)

        assertEquals(listOf("upper", "lower"), inReadingOrder(listOf(lower, upper)).map { it.text })
    }

    @Test
    fun `assembling puts one block per line and drops the empty ones`() {
        val blocks = listOf(block("one", 0), block("   ", 50), block("two", 100))
        assertEquals("one\ntwo", assemble(blocks))
    }

    @Test
    fun `ordering is stable for a single block and for none`() {
        assertEquals(emptyList(), inReadingOrder(emptyList()))
        val only = block("one", 0)
        assertEquals(listOf(only), inReadingOrder(listOf(only)))
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
