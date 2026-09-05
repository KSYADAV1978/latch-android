package com.latch.android.cards

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * FR-1201a's decision and FR-1211's lifetime.
 *
 * **Both are reachable from here on purpose.** The step is booleans and the storage is
 * `java.io.File`, so nothing in this file needs a device — which matters most for FR-1211,
 * because a rule about how long a third party's photograph is allowed to exist is worth
 * exactly as much as the test that checks it.
 */
class CardPhotoTest {

    private val cacheDir: File = Files.createTempDirectory("latch-card-photo").toFile()

    @AfterTest
    fun tearDown() {
        cacheDir.deleteRecursively()
    }

    // ---- FR-1211: the file's lifetime -------------------------------------------------------

    @Test
    fun `a new photograph gets a file under its own directory`() {
        val file = assertNotNull(newCardPhotoFile(cacheDir))
        assertEquals(CAMERA_DIRECTORY, file.parentFile.name)
        assertTrue(file.parentFile.isDirectory)
    }

    @Test
    fun `it is not the directory the ics export clears`() {
        // `shareAsIcs` calls `deleteRecursively` on `exports/` every time it runs, so sharing an
        // `.ics` while a card sheet was open would have deleted the photograph out from under
        // the recognition reading it. Two purposes, two directories.
        assertTrue(newCardPhotoFile(cacheDir)!!.parentFile.name != "exports")
    }

    @Test
    fun `the previous photograph is swept before the next one`() {
        // The case `onDestroy` cannot cover: a killed process leaves its file behind, and this
        // is what removes it. Written with content, because an empty file would pass a weaker
        // check than the one intended.
        val first = assertNotNull(newCardPhotoFile(cacheDir))
        first.writeText("a photograph of somebody's card")
        val stray = File(first.parentFile, "left-behind.jpg").apply { writeText("x") }

        val second = assertNotNull(newCardPhotoFile(cacheDir))

        assertEquals(0L, second.length(), "the previous photograph survived into the next capture")
        assertFalse(stray.exists(), "the sweep left a file it did not recognise")
    }

    @Test
    fun `clearing takes the photograph away`() {
        newCardPhotoFile(cacheDir)!!.writeText("a photograph of somebody's card")

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

    @Test
    fun `the path is the same one the result callback recomputes`() {
        // The whole reason the name is fixed: the camera application is in front of this process
        // and the process may not survive it, so the photograph is found again by path rather
        // than by a `Uri` held in a field that would have gone with it.
        val made = assertNotNull(newCardPhotoFile(cacheDir))
        assertEquals(made, cardPhotoFile(cacheDir))
    }

    // ---- FR-1201a: when the sheet opens -----------------------------------------------------

    @Test
    fun `nothing opens until the recognition has finished`() {
        assertEquals(
            CardPhotoStep.WAITING,
            cardPhotoStep(
                recognitionSettled = false,
                decodeSettled = true,
                hasText = false,
                payloads = 1,
            ),
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
            cardPhotoStep(
                recognitionSettled = true,
                decodeSettled = false,
                hasText = true,
                payloads = 0,
            ),
        )
    }

    @Test
    fun `a decoded payload opens the sheet even where nothing was recognised`() {
        // A badly-lit card whose QR still decodes is worth opening: the grammar says which field
        // each value belongs to, and no amount of recognised text would improve on that.
        assertEquals(
            CardPhotoStep.OPEN,
            cardPhotoStep(
                recognitionSettled = true,
                decodeSettled = true,
                hasText = false,
                payloads = 1,
            ),
        )
    }

    @Test
    fun `recognised text with no code opens the sheet — the ordinary FR-1220 case`() {
        assertEquals(
            CardPhotoStep.OPEN,
            cardPhotoStep(
                recognitionSettled = true,
                decodeSettled = true,
                hasText = true,
                payloads = 0,
            ),
        )
    }

    @Test
    fun `neither is neither, and is said rather than opened as an empty sheet`() {
        assertEquals(
            CardPhotoStep.NOTHING_READ,
            cardPhotoStep(
                recognitionSettled = true,
                decodeSettled = true,
                hasText = false,
                payloads = 0,
            ),
        )
    }
}
