package com.latch.android.capture

import com.latch.android.cards.CardOfferMemory
import com.latch.android.cards.CardSaveResult
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * SRS 1.206: two cards photographed in succession must not be one capture.
 *
 * FR-1211 gives every card photograph the same file name, so the URI cannot tell two captures
 * apart. What made that a silent loss rather than a cosmetic fault is the pair of offers keyed on
 * it: the second card restored the first one's `Saved`, opened on *"Saved to your contacts."* with
 * `Close`, and was never written.
 */
class CaptureKeyTest {

    // The URI is deliberately identical in every card case here. That is not a simplification of
    // the fixture — it is the condition, and a fixture that varied it would test nothing.
    private val cameraUri = "content://com.latch.android.exports/camera/card-1.jpg"

    @Test
    fun `two cards photographed in succession are two captures`() {
        val first = imageCaptureKey(cameraUri, cardPath = true, photoStamp = "1000/42")
        val second = imageCaptureKey(cameraUri, cardPath = true, photoStamp = "2000/57")
        assertNotEquals(first, second)
    }

    @Test
    fun `a recreation of one capture is the same capture`() {
        // The half that would be broken by any fix that simply made the key unique per call: a
        // rotation must keep FR-807's undo and FR-1231's offer, which is what the key is for.
        assertEquals(
            imageCaptureKey(cameraUri, cardPath = true, photoStamp = "1000/42"),
            imageCaptureKey(cameraUri, cardPath = true, photoStamp = "1000/42"),
        )
    }

    @Test
    fun `a shared image is keyed on its own uri and is unchanged`() {
        val shared = "content://media/external/images/media/9182"
        assertEquals("image:$shared", imageCaptureKey(shared, cardPath = false, photoStamp = null))
        assertNotEquals(
            imageCaptureKey(shared, cardPath = false, photoStamp = null),
            imageCaptureKey("content://media/external/images/media/9183", false, null),
        )
    }

    @Test
    fun `an unreadable photograph does not mint a new key each time`() {
        // Falling back to something unique would defeat the offers rather than the duplicate.
        assertEquals(
            imageCaptureKey(cameraUri, cardPath = true, photoStamp = null),
            imageCaptureKey(cameraUri, cardPath = true, photoStamp = null),
        )
        assertNull(photoStampOf(lastModified = 0L, length = 0L))
        assertEquals("1700/9", photoStampOf(lastModified = 1700L, length = 9L))
    }

    @Test
    fun `the outcome of one card does not reach the next`() {
        // The defect end to end, over the memory that actually held it.
        val offers = CardOfferMemory()
        val first = imageCaptureKey(cameraUri, cardPath = true, photoStamp = "1000/42")
        offers.reset(first)
        offers.keep(
            first,
            CardOfferMemory.Remembered(
                result = CardSaveResult.Saved(resourceName = "people/c1", checked = true),
                savedAt = Instant.EPOCH,
                accepted = emptySet(),
                values = emptyMap(),
                flipped = emptySet(),
            ),
        )

        val second = imageCaptureKey(cameraUri, cardPath = true, photoStamp = "2000/57")
        offers.reset(second)
        assertNull(offers.restore(second))
    }
}
