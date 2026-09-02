package com.latch.desktop.ocr

import com.latch.parser.DateParser
import com.latch.parser.ParseContext
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * FR-303 against the real recogniser, on the fixtures the Android client's OCR was verified on.
 *
 * **This is the test the Android project could not write.** ML Kit needs a device, so every
 * assertion about recognition there had to wait for a device pass — which is how a dead image
 * path survived a slice with 328 tests green. `Windows.Media.Ocr` is on the machine that runs
 * the build, so the recogniser, the EXIF handling and the reading order are all covered by an
 * ordinary `./gradlew test` here.
 *
 * Skipped rather than passed where the platform cannot answer. An inconclusive run that reports
 * green is the failure mode `CLAUDE.md` records for device passes, and it applies to a build
 * server with no language pack exactly as it applies to a phone.
 */
class WindowsOcrLiveTest {

    private val fixtures: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }
        .resolve("testdata/fr215")

    private fun recognise(name: String): OcrOutcome {
        assumeTrue(WindowsOcr.isAvailable(), "not Windows")
        val file = fixtures.resolve(name)
        assumeTrue(file.isFile, "fixture missing: $file")
        val outcome = WindowsOcr().recognise(file)
        assumeTrue(
            outcome !is OcrOutcome.Failed || outcome.reason != OcrFailure.NO_RECOGNISER,
            "no OCR language pack installed on this machine",
        )
        return outcome
    }

    @Test
    fun `a chat screenshot yields its three dates`() {
        // What failure looks like: any one of these three strings absent. The fixture holds
        // three dates in three different formats on purpose — a month name, a numeric
        // DD/MM/YYYY, and a range — so a recogniser that read only the obvious one is red.
        val result = recognise("three-dates-v4.png")
        assertIs<OcrOutcome.Recognised>(result)
        assertTrue("14 September 2026" in result.text, result.text)
        assertTrue("20/09/2027" in result.text, result.text)
        assertTrue("1 October to 5 October 2027" in result.text, result.text)
    }

    @Test
    fun `the shared parser finds three candidates in what Windows recognised`() {
        // The point of :wire, demonstrated rather than asserted in prose: the parser compiled
        // into the Android app, run over text this machine's recogniser produced, finds the
        // same three dates the phone found. A second parser would be the thing that could
        // disagree here, and there is not one.
        val result = recognise("three-dates-v4.png")
        assertIs<OcrOutcome.Recognised>(result)

        val parsed = DateParser.parse(
            result.text,
            ParseContext(now = LocalDateTime.of(2026, 9, 2, 12, 0)),
        )
        val dates = parsed.candidates.mapNotNull { it.date?.value?.toString() }
        assertEquals(listOf("2026-09-14", "2027-09-20", "2027-10-01"), dates, result.text)
    }

    @Test
    fun `a page photographed portrait is read the right way up`() {
        // The total-failure case, and the reason recognise.ps1 uses a five-argument overload.
        // photo-portrait.jpg is 2800x2000 with EXIF orientation 6; handed to a recogniser
        // unrotated it returns nothing at all, so an empty result here is the exact signature
        // of the defect this guards. Android hit it on 31 Aug 2026.
        val result = recognise("photo-portrait.jpg")
        assertIs<OcrOutcome.Recognised>(result)
        assertTrue(result.text.length > 20, "recognised only: '${result.text}'")
        assertTrue("November" in result.text || "Nov" in result.text, result.text)
    }

    @Test
    fun `the assembled text is in document order`() {
        val result = recognise("three-dates-v4.png")
        assertIs<OcrOutcome.Recognised>(result)
        val first = result.text.indexOf("Sharma")
        val later = result.text.indexOf("Fees due")
        assertTrue(first in 0..<later, "expected the header before the later message:\n${result.text}")
    }

    @Test
    fun `a file that is not an image fails as unreadable rather than as empty`() {
        assumeTrue(WindowsOcr.isAvailable(), "not Windows")
        val notAnImage = File.createTempFile("latch-ocr", ".png").apply {
            writeText("this is not a PNG")
            deleteOnExit()
        }
        val outcome = WindowsOcr().recognise(notAnImage)
        assertEquals(OcrFailure.UNREADABLE_SOURCE, (outcome as OcrOutcome.Failed).reason)
    }

    @Test
    fun `a missing file never reaches the bridge`() {
        val outcome = WindowsOcr().recognise(File("no-such-file.png"))
        assertEquals(OcrFailure.UNREADABLE_SOURCE, (outcome as OcrOutcome.Failed).reason)
    }
}
