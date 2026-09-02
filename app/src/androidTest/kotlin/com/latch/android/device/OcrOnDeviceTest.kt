package com.latch.android.device

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.latch.android.capture.CapturedText
import com.latch.android.capture.dateSpans
import com.latch.core.model.CaptureLayer
import com.latch.core.model.CaptureSource
import com.latch.data.sourceBlock
import com.latch.ocr.MlKitOcrReader
import com.latch.ocr.OcrFailure
import com.latch.ocr.OcrResult
import com.latch.parser.DateParser
import com.latch.parser.ParseContext
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The instrumented suite, and the first three tests in it are the three defects the FR-215
 * device pass found on 31 Aug 2026.
 *
 * **This exists because of what `docs/DEPENDENCIES.md` calls a canary and this is not.** The
 * launch canary asserts the app gets off the ground and nothing more, deliberately. What that
 * leaves uncovered is a specific and now well-evidenced class: **a platform call whose stubbed
 * behaviour under JVM unit tests inverts the real one.** `BitmapFactory` is a throwing stub in
 * the `android.jar` those tests compile against — the same property that makes the parser corpus
 * cheap — and it hid a dead image path through a whole slice, with 328 green tests throughout.
 *
 * Each test below names the defect it would have caught. That is the standing convention in
 * `CLAUDE.md` applied to an instrumented suite: name the condition that would make the check
 * fail, and confirm the fixture can produce it.
 */
@RunWith(AndroidJUnit4::class)
class OcrOnDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val reader = MlKitOcrReader(context)

    @After
    fun close() {
        reader.close()
        File(context.cacheDir, FIXTURES).deleteRecursively()
    }

    /**
     * **The first defect, and the reason this suite exists.**
     *
     * `openStream(uri)?.use { decodeStream(it, null, bounds) } ?: return null` — `decodeStream`
     * returns null **by contract** under `inJustDecodeBounds`, so the elvis guarded the decode
     * result rather than the stream and `decodeBitmap` returned null for every image ever passed
     * to it. The image path was dead on every image, through three commits, with every unit test
     * green.
     *
     * The failure signature is exactly what this asserts: `UNREADABLE_SOURCE`, on a file that is
     * perfectly readable.
     */
    @Test
    fun a_real_png_decodes_rather_than_reporting_an_unreadable_source() = runBlocking {
        val result = reader.readImage(assetUri("latin-chat.png"))

        if (result is OcrResult.Failed) {
            assertFalse(
                "decodeBitmap returned null for a readable PNG — the elvis-binding defect is back",
                result.reason == OcrFailure.UNREADABLE_SOURCE,
            )
        }
        val text = assertIsText(result)
        assertTrue("recognition returned nothing at all", text.isNotBlank())
    }

    /**
     * **The second defect.** The Devanagari artifact's combined engine carries a Bengali model
     * and substituted Bengali codepoints into Latin words: `October` came back as `০ctobe`
     * (U+09E6 BENGALI DIGIT ZERO), `12,500` as `12,50০`. In one fixture that broke a date range
     * badly enough to lose a commitment silently.
     *
     * The fix was the dedicated Latin artifact at +8,082 bytes, taken after the NFR-501 reasoning
     * that had refused it turned out to be sound and the outcome wrong. **What no JVM test can
     * check is that both artifacts are still in the APK and still being merged**, which is
     * precisely what this asserts: one line in a build file undoes it.
     */
    @Test
    fun no_bengali_codepoint_appears_in_a_latin_screenshot() = runBlocking {
        val text = assertIsText(reader.readImage(assetUri("latin-chat.png")))

        val bengali = text.filter { it.code in 0x0980..0x09FF }
        assertEquals(
            "Bengali codepoints in Latin text — the Latin artifact is gone or the merge is broken: $bengali",
            "",
            bengali,
        )
    }

    /**
     * **The third defect.** FR-805b's character-radius window covered a ~270-character chat
     * screenshot entirely, so the note written into a user's account was the whole screen — every
     * message in the thread, the sender's name, the amount paid. The rule was working exactly as
     * specified; the shape of the bound was wrong.
     *
     * SRS 1.32 states the test of any implementation verbatim: **the extract shall be strictly
     * shorter** than the recognised text and shall no longer carry the lines that are not about a
     * date. Both halves are here, and the second is the one a length check alone would miss.
     */
    @Test
    fun the_fr805b_extract_is_strictly_shorter_than_what_was_recognised() = runBlocking {
        val recognised = assertIsText(reader.readImage(assetUri("latin-chat.png")))

        val captured = CapturedText(
            text = recognised,
            layer = CaptureLayer.SHARE_SHEET,
            ocrUsed = true,
        )
        val parsed = DateParser.parse(
            recognised,
            ParseContext(LocalDateTime.of(2026, 9, 2, 9, 0), ZoneId.of("Asia/Kolkata")),
        )
        // The condition that makes this check mean anything: the fixture must genuinely hold
        // dates, or the extract falls back to the opening of the text and proves nothing.
        assertTrue("the fixture recognised no dates", dateSpans(parsed).isNotEmpty())

        val extract = sourceBlock(
            source = CaptureSource(layer = captured.layer, ocrUsed = true),
            sourceText = recognised,
            dateSpans = dateSpans(parsed),
        )

        assertTrue(
            "the extract is not shorter than the recognised text (${extract.length} vs ${recognised.length})",
            extract.length < recognised.length,
        )
        // The half a length check would miss. This fixture's own note is what SRS 1.32 quotes:
        // the sender's name and the amount paid were both in it.
        assertFalse("the sender's name survived into the extract", extract.contains("Sharma"))
        assertFalse("the amount paid survived into the extract", extract.contains("12,50"))
    }

    /**
     * FR-207, and the one part of its reporting no JVM test can reach: a PDF is rendered by the
     * platform and read by the same recogniser.
     *
     * `PdfRenderer` is a throwing stub off a device exactly as `BitmapFactory` is, so this whole
     * path has the same standing as the one the first test covers.
     */
    @Test
    fun a_pdf_is_rendered_and_read_and_reports_its_page_coverage() = runBlocking {
        val result = reader.readPdf(assetUri("letter.pdf"))
        val text = assertIsText(result)

        assertTrue("the PDF recognised nothing", text.isNotBlank())
        val pages = (result as OcrResult.Text).pages
        assertNotNull("a PDF must report how much of it was read (FR-207)", pages)
        checkNotNull(pages)
        assertEquals(2, pages.total)
        assertEquals(2, pages.read)
        // "First 2 of 2 pages read" is a message about nothing, and FR-207's cap line must not
        // be shown for it.
        assertFalse("a 2-page PDF must not report a cap", pages.capped)
    }

    /** FR-207's page progress, which reached a screen in the first slice of this session. */
    @Test
    fun a_pdf_reports_its_progress_page_by_page() = runBlocking {
        val seen = mutableListOf<Pair<Int, Int>>()
        reader.readPdf(assetUri("letter.pdf")) { seen += it.page to it.total }

        assertEquals(listOf(1 to 2, 2 to 2), seen)
    }

    // ----- fixtures -----

    private fun assetUri(name: String): Uri {
        val directory = File(context.cacheDir, FIXTURES).apply { mkdirs() }
        val file = File(directory, name)
        context.assets.open(name).use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }
        return Uri.fromFile(file)
    }

    private fun assertIsText(result: OcrResult): String = when (result) {
        is OcrResult.Text -> result.value
        is OcrResult.Failed -> throw AssertionError("recognition failed: ${result.reason}")
    }

    private companion object {
        const val FIXTURES = "instrumented-fixtures"
    }
}
