package com.latch.android.notifications

import com.latch.core.model.CaptureLayer
import com.latch.core.model.CaptureSource
import com.latch.wire.sourceBlock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * FR-208 to FR-212, and the two exclusions this layer carries.
 *
 * **Nothing in the listener itself is reachable from a JVM test** — it needs a
 * `NotificationListenerService` — which is the shape that hid the drain's FR-803 check and
 * `decodeBitmap`'s null contract for a slice each. So every rule about *which* of the device's
 * notifications Latch reads at all is a pure function, and this is what pins them.
 */
class NotificationCapturesTest {

    private val ours = "com.latch.android"
    private val monitored = setOf("com.whatsapp")

    private fun decide(
        packageName: String = "com.whatsapp",
        enabled: Boolean = true,
        monitored: Set<String> = this.monitored,
        ongoing: Boolean = false,
        summary: Boolean = false,
        text: String = "PTM on Friday 12 September",
    ) = notificationDecision(
        packageName = packageName,
        ownPackage = ours,
        layerEnabled = enabled,
        monitored = monitored,
        isOngoing = ongoing,
        isGroupSummary = summary,
        text = text,
    )

    @Test
    fun `a monitored app's message is offered`() {
        assertEquals(
            NotificationDecision.Offer("PTM on Friday 12 September"),
            decide(),
        )
    }

    @Test
    fun `nothing is read while the layer is off`() {
        // FR-209 and FR-1003: off by default, and the default is the only default this may have.
        assertEquals(
            NotificationDecision.Skip(NotificationSkip.LAYER_OFF),
            decide(enabled = false),
        )
    }

    @Test
    fun `an app the user did not choose is ignored`() {
        // FR-212. Android grants notification access per app, so the listener *can* see this
        // one; the requirement is that Latch does not act on it.
        assertEquals(
            NotificationDecision.Skip(NotificationSkip.NOT_MONITORED),
            decide(packageName = "com.example.shopping"),
        )
    }

    @Test
    fun `an empty monitored list monitors nothing, which is the default`() {
        assertEquals(
            NotificationDecision.Skip(NotificationSkip.NOT_MONITORED),
            decide(monitored = emptySet()),
        )
    }

    @Test
    fun `our own notification is skipped before anything else is even asked`() {
        // FR-211 has this layer post a notification. A listener that read its own would offer
        // to capture its own offer, for ever — and it would do it with the layer off, with
        // nothing monitored, and with any text, which is why this is the first test in the
        // chain rather than one of the later ones.
        assertEquals(
            NotificationDecision.Skip(NotificationSkip.OUR_OWN),
            decide(packageName = ours, enabled = false, monitored = emptySet()),
        )
    }

    @Test
    fun `an ongoing notification is not a message`() {
        // A media player or a download updates many times a second. Reading them would put the
        // listener through a parse on every progress tick.
        assertEquals(
            NotificationDecision.Skip(NotificationSkip.ONGOING),
            decide(ongoing = true),
        )
    }

    @Test
    fun `a group summary is skipped, because it repeats what it summarises`() {
        assertEquals(
            NotificationDecision.Skip(NotificationSkip.GROUP_SUMMARY),
            decide(summary = true),
        )
    }

    @Test
    fun `a notification with no text is not a message`() {
        assertEquals(NotificationDecision.Skip(NotificationSkip.NO_TEXT), decide(text = "   "))
    }

    @Test
    fun `the offered text is trimmed but otherwise untouched`() {
        val offer = assertIs<NotificationDecision.Offer>(decide(text = "  PTM on Friday  "))
        assertEquals("PTM on Friday", offer.text)
    }

    // ----- FR-210 and NFR-206: the text is held in memory and nowhere else -----

    @Test
    fun `a held capture is returned once and then gone`() {
        // Taking rather than reading: once the user has confirmed, the offer is answered, and a
        // second tap on a stale notification must find nothing rather than open a capture of a
        // message already dealt with.
        val holder = NotificationCaptureHolder()
        val key = holder.hold("PTM on Friday 12 September")

        assertEquals("PTM on Friday 12 September", holder.take(key))
        assertNull(holder.take(key))
    }

    @Test
    fun `an unknown key returns nothing rather than something`() {
        assertNull(NotificationCaptureHolder().take("no-such-key"))
    }

    @Test
    fun `two holds of the same text get different keys`() {
        val holder = NotificationCaptureHolder()
        assertTrue(holder.hold("same") != holder.hold("same"))
    }

    @Test
    fun `the holder is bounded, so an ignored offer does not live for ever`() {
        // A listener sees everything. Without a bound, every message from a monitored app that
        // the user ignored would keep its text alive for the life of the process.
        val holder = NotificationCaptureHolder(capacity = 3)
        val keys = (1..5).map { holder.hold("message $it") }

        assertEquals(3, holder.size())
        // The oldest went.
        assertNull(holder.take(keys[0]))
        assertNull(holder.take(keys[1]))
        assertEquals("message 5", holder.take(keys[4]))
    }

    @Test
    fun `clearing forgets everything, which NFR-205 needs`() {
        val holder = NotificationCaptureHolder()
        holder.hold("a")
        holder.hold("b")
        holder.clear()
        assertEquals(0, holder.size())
    }

    // ----- the two exclusions this layer carries, which are properties rather than code here -----

    @Test
    fun `AC-22 - a notification capture stores no source text at all`() {
        // FR-805a, and the reason it is a property of `CaptureSource` rather than a rule in the
        // listener: `sourceBlock` composes every description in the app, so no call site can
        // compose one differently and none has to remember this.
        val source = CaptureSource(layer = CaptureLayer.NOTIFICATION)
        assertFalse(source.storesSourceText)
        assertEquals("", sourceBlock(source = source, sourceText = "PTM on Friday 12 September"))
    }

    @Test
    fun `the source link survives, because it is provenance and not content`() {
        val source = CaptureSource(layer = CaptureLayer.NOTIFICATION, appId = "com.whatsapp")
        val body = sourceBlock(
            source = source,
            sourceText = "PTM on Friday 12 September",
            sourceLink = "Captured from com.whatsapp",
        )
        assertEquals("Captured from com.whatsapp", body)
        assertFalse(body.contains("PTM"))
    }

    @Test
    fun `AC-19 - no webhook is ever sent for this layer`() {
        // FR-210a: "whether or not a webhook is configured".
        assertFalse(CaptureSource(layer = CaptureLayer.NOTIFICATION).webhookEligible)
    }

    @Test
    fun `a notification capture is never routed to the Inbox`() {
        // NFR-206 again: the Inbox is persistent storage. FR-512's interim behaviour therefore
        // survives permanently for this layer alone, which SRS 1.43 records as a narrowing.
        assertFalse(CaptureSource(layer = CaptureLayer.NOTIFICATION).routableToInbox)
    }

    @Test
    fun `every other layer keeps all three`() {
        // The guard on the guards: an exclusion applied to the wrong layer would be invisible
        // until someone noticed their share-sheet captures had stopped carrying their text.
        listOf(
            CaptureLayer.TEXT_SELECTION,
            CaptureLayer.SHARE_SHEET,
            CaptureLayer.QUICK_TILE,
            CaptureLayer.SCREENSHOT_WATCHER,
        ).forEach { layer ->
            val source = CaptureSource(layer = layer)
            assertTrue(source.storesSourceText, "$layer lost its source text")
            assertTrue(source.webhookEligible, "$layer lost its webhook")
            assertTrue(source.routableToInbox, "$layer lost its Inbox route")
        }
    }

    @Test
    fun `an OCR notification capture still stores nothing, which is how the rules compose`() {
        // "A notification capture that was somehow also OCR-derived stores nothing, because an
        // extract of nothing is nothing." Asserted rather than left as a comment.
        val source = CaptureSource(layer = CaptureLayer.NOTIFICATION, ocrUsed = true)
        assertFalse(source.storesWholeSourceText)
        assertEquals("", sourceBlock(source = source, sourceText = "anything at all"))
    }

    @Test
    fun `the holder gives a key that is not the text`() {
        // The whole shape of FR-210's compliance: a posted notification carries this, and a
        // PendingIntent extra holding the message would hand it to the system's notification
        // manager — not this app's memory, and not this app's to reason about.
        val holder = NotificationCaptureHolder()
        val key = assertNotNull(holder.hold("PTM on Friday 12 September"))
        assertFalse(key.contains("PTM"))
        assertFalse(key.contains("September"))
    }
}
