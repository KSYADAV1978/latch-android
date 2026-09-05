package com.latch.android.cards

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * FR-1201a's decision, FR-1225's photograph set, and FR-1211's lifetime.
 *
 * **All three are reachable from here on purpose.** The steps are booleans and the storage is
 * `java.io.File`, so nothing in this file needs a device — which matters most for FR-1211, because
 * a rule about how long a third party's photograph is allowed to exist is worth exactly as much as
 * the test that checks it.
 */
class CardPhotoTest {

    private val cacheDir: File = Files.createTempDirectory("latch-card-photo").toFile()

    @AfterTest
    fun tearDown() {
        cacheDir.deleteRecursively()
    }

    private fun shoot(file: File?, bytes: String = "a photograph of somebody's card") {
        assertNotNull(file).writeText(bytes)
    }

    // ---- FR-1211: the files' lifetime --------------------------------------------------------

    @Test
    fun `a new capture gets a file under its own directory`() {
        val file = assertNotNull(startCardPhotoSet(cacheDir))
        assertEquals(CAMERA_DIRECTORY, file.parentFile.name)
        assertTrue(file.parentFile.isDirectory)
    }

    @Test
    fun `it is not the directory the ics export clears`() {
        // `shareAsIcs` calls `deleteRecursively` on `exports/` every time it runs, so sharing an
        // `.ics` while a card sheet was open would have deleted the photographs out from under
        // the recognition reading them. Two purposes, two directories.
        assertTrue(startCardPhotoSet(cacheDir)!!.parentFile.name != "exports")
    }

    @Test
    fun `starting a capture sweeps the previous one`() {
        // The case `onDestroy` cannot cover: a killed process leaves its photographs behind, and
        // this is what removes them. Watched on a device on 5 Sep 2026, by way of a reinstall
        // killing the process with a card sheet open.
        shoot(startCardPhotoSet(cacheDir))
        shoot(nextCardPhotoFile(cacheDir))
        val stray = File(cacheDir, "$CAMERA_DIRECTORY/left-behind.jpg").apply { writeText("x") }

        val second = assertNotNull(startCardPhotoSet(cacheDir))

        assertEquals(0L, second.length(), "a previous photograph survived into the next capture")
        assertTrue(cardPhotoFiles(cacheDir).isEmpty(), "the sweep left a photograph behind")
        assertFalse(stray.exists(), "the sweep left a file it did not recognise")
    }

    @Test
    fun `clearing takes every photograph away`() {
        shoot(startCardPhotoSet(cacheDir))
        shoot(nextCardPhotoFile(cacheDir))

        clearCardPhotos(cacheDir)

        assertFalse(File(cacheDir, CAMERA_DIRECTORY).exists())
    }

    @Test
    fun `clearing a directory that was never made is not an error`() {
        // Called from `onDestroy` for every capture, not only a camera one — a sweep that runs on
        // a path nobody thought about is worth more than one that is exact.
        clearCardPhotos(cacheDir)
        assertFalse(File(cacheDir, CAMERA_DIRECTORY).exists())
    }

    // ---- FR-1225: the set ---------------------------------------------------------------------

    @Test
    fun `adding a side does not sweep the one before it`() {
        // The whole difference between starting a capture and extending one. If this ever swept,
        // FR-1225 would silently become "the last photograph wins" — and the sheet would look
        // right, because one classification over one side is a perfectly plausible draft.
        shoot(startCardPhotoSet(cacheDir), "the front")
        shoot(nextCardPhotoFile(cacheDir), "the back")

        assertEquals(2, cardPhotoFiles(cacheDir).size)
        assertEquals(listOf("the front", "the back"), cardPhotoFiles(cacheDir).map { it.readText() })
    }

    @Test
    fun `the photographs come back in the order they were taken`() {
        // FR-1225 concatenates "in the order they were added", so this ordering is the
        // requirement rather than tidiness: the front's name must reach the classifier first.
        repeat(MAX_CARD_PHOTOS) { index ->
            shoot(if (index == 0) startCardPhotoSet(cacheDir) else nextCardPhotoFile(cacheDir), "side $index")
        }
        assertEquals(
            (0 until MAX_CARD_PHOTOS).map { "side $it" },
            cardPhotoFiles(cacheDir).map { it.readText() },
        )
    }

    @Test
    fun `an abandoned photograph does not consume its index`() {
        // `nextCardPhotoFile` names a path and creates nothing, so a shot the user backs out of
        // in the camera leaves no file — and the next attempt reuses the index rather than
        // counting a photograph nobody took, which would spend the cap on nothing.
        shoot(startCardPhotoSet(cacheDir))
        val abandoned = assertNotNull(nextCardPhotoFile(cacheDir))
        assertFalse(abandoned.exists())

        val retried = assertNotNull(nextCardPhotoFile(cacheDir))
        assertEquals(abandoned, retried)
        assertEquals(1, cardPhotoFiles(cacheDir).size)
    }

    @Test
    fun `the cap withdraws the control rather than discarding a photograph`() {
        repeat(MAX_CARD_PHOTOS) { index ->
            shoot(if (index == 0) startCardPhotoSet(cacheDir) else nextCardPhotoFile(cacheDir))
        }
        assertFalse(canAddCardPhoto(MAX_CARD_PHOTOS))
        // Null and not a file: nothing is ever taken and then thrown away, which is why FR-1225's
        // "more images added than the capture will read" cannot arise from the cap here.
        assertNull(nextCardPhotoFile(cacheDir))
        assertEquals(MAX_CARD_PHOTOS, cardPhotoFiles(cacheDir).size)
    }

    @Test
    fun `another side is offered below the cap`() {
        assertTrue(canAddCardPhoto(0))
        assertTrue(canAddCardPhoto(MAX_CARD_PHOTOS - 1))
    }

    // ---- FR-1225: one input, one classification ----------------------------------------------

    @Test
    fun `the sides are concatenated in order`() {
        assertEquals(
            listOf("Anita Sharma", "Head of Sourcing", "12 Mill Road", "Mumbai 400001"),
            cardLinesOf(
                listOf(
                    "Anita Sharma\nHead of Sourcing",
                    "12 Mill Road\nMumbai 400001",
                )
            ),
        )
    }

    @Test
    fun `a trailing blank line does not put a gap between the sides`() {
        // The classifier's positional rules read adjacency, so a photograph that ends in
        // whitespace would otherwise separate the front from the back by an empty line and
        // change what the rules see.
        assertEquals(
            listOf("Anita Sharma", "12 Mill Road"),
            cardLinesOf(listOf("Anita Sharma\n\n", "  \n12 Mill Road")),
        )
    }

    @Test
    fun `no photographs is no lines rather than one empty one`() {
        assertEquals(emptyList(), cardLinesOf(emptyList()))
        assertEquals(emptyList(), cardLinesOf(listOf("")))
    }

    // ---- FR-1225: the coverage report ---------------------------------------------------------

    @Test
    fun `nothing is reported while every photograph gave something`() {
        assertFalse(CardPhotoCoverage(read = 2, added = 2).capped)
    }

    @Test
    fun `a photograph that gave nothing is reported`() {
        // FR-207's reason, one pillar over: a user who adds a third side and sees the sheet not
        // change cannot otherwise tell a failed recognition from a side with nothing on it.
        val coverage = CardPhotoCoverage(read = 2, added = 3)
        assertTrue(coverage.capped)
        assertEquals(2, coverage.read)
        assertEquals(3, coverage.added)
    }

    // ---- FR-1201a: when the sheet opens -------------------------------------------------------

    @Test
    fun `nothing opens until the recognition has finished`() {
        assertEquals(
            CardPhotoStep.WAITING,
            cardPhotoStep(recognitionSettled = false, decodeSettled = true, hasText = false, payloads = 1),
        )
    }

    @Test
    fun `nothing opens until the decode has finished, even with text in hand`() {
        // **This is the row the ordering exists for.** The recognition is the slower of the two
        // in practice, so this case is rare — and "rare" is what SRS 1.100's inverse defect was
        // too. A card carrying a QR code would otherwise be classified off its own photograph
        // while the payload was still arriving: the grammar's exact answer lost to the guess.
        assertEquals(
            CardPhotoStep.WAITING,
            cardPhotoStep(recognitionSettled = true, decodeSettled = false, hasText = true, payloads = 0),
        )
    }

    @Test
    fun `a decoded payload opens the sheet even where nothing was recognised`() {
        // A badly-lit card whose QR still decodes is worth opening: the grammar says which field
        // each value belongs to, and no amount of recognised text would improve on that.
        assertEquals(
            CardPhotoStep.OPEN,
            cardPhotoStep(recognitionSettled = true, decodeSettled = true, hasText = false, payloads = 1),
        )
    }

    @Test
    fun `recognised text with no code opens the sheet — the ordinary FR-1220 case`() {
        assertEquals(
            CardPhotoStep.OPEN,
            cardPhotoStep(recognitionSettled = true, decodeSettled = true, hasText = true, payloads = 0),
        )
    }

    @Test
    fun `neither is neither, and is said rather than opened as an empty sheet`() {
        assertEquals(
            CardPhotoStep.NOTHING_READ,
            cardPhotoStep(recognitionSettled = true, decodeSettled = true, hasText = false, payloads = 0),
        )
    }
}
