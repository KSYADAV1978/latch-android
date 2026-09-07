package com.latch.android.cards

import com.latch.google.ContactRecord
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull

/**
 * SRS 1.183. **The rule is `CaptureSaver.reset`'s, so it is asserted the same way.**
 *
 * The failure this guards against is not "the offer is gone". It is that the sheet silently
 * returns to `Save contact` — the control that writes a *second* contact for somebody the offer
 * had just established is already in the address book. So the test that matters is the one for a
 * recreation of the *same* capture, and its opposite: a genuinely new capture must still end the
 * offer, or answering one card's question would write to another card's contact.
 */
class CardOfferMemoryTest {

    private val offer = CardSaveResult.UpdateOffered(
        stored = ContactRecord(resourceName = "people/c1", etag = "e1"),
        changes = emptyList(),
    )

    private fun remembered(accepted: Set<Int> = setOf(0, 2)) = CardOfferMemory.Remembered(
        result = offer,
        savedAt = null,
        accepted = accepted,
        values = mapOf(1 to "corrected"),
        flipped = setOf(1),
    )

    @Test
    fun `a recreation of the same capture keeps the offer`() {
        val memory = CardOfferMemory()
        memory.reset("image:card")
        memory.keep("image:card", remembered())

        // What a rotation does: the Activity is recreated and resets with the same key first.
        memory.reset("image:card")

        val back = assertNotNull(memory.restore("image:card"))
        assertEquals(offer, back.result)
    }

    @Test
    fun `the edits come back with it, not just the question`() {
        val memory = CardOfferMemory()
        memory.keep("image:card", remembered(accepted = setOf(2)))
        memory.reset("image:card")

        val back = assertNotNull(memory.restore("image:card"))
        assertEquals(setOf(2), back.accepted, "restoring the offer and dropping the replies reads as forgetting")
        assertEquals(mapOf(1 to "corrected"), back.values)
        assertEquals(setOf(1), back.flipped)
    }

    @Test
    fun `a genuinely new capture ends the offer`() {
        val memory = CardOfferMemory()
        memory.keep("image:card", remembered())
        memory.reset("image:other")
        assertNull(memory.restore("image:other"))
        assertNull(memory.restore("image:card"), "an offer must not outlive the capture it belongs to")
    }

    @Test
    fun `a save in flight is not kept, because its coroutine is gone`() {
        val memory = CardOfferMemory()
        memory.keep("image:card", remembered())
        memory.keep("image:card", remembered().copy(result = CardSaveResult.Saving))
        assertNull(
            memory.restore("image:card"),
            "restoring Saving would put back a spinner nothing will complete",
        )
    }

    @Test
    fun `going back to Idle clears rather than lingering`() {
        val memory = CardOfferMemory()
        memory.keep("image:card", remembered())
        memory.keep("image:card", remembered().copy(result = CardSaveResult.Idle))
        assertNull(memory.restore("image:card"))
    }
}
