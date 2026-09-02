package com.latch.android.notifications

import java.util.UUID

/**
 * Why a posted notification was, or was not, offered as a capture.
 *
 * Named rather than phrased, and enumerated rather than expressed as an early return, because
 * every one of these is a **filter on content this app may not keep** — and a filter that is
 * only a `return` in a listener is a filter nobody can test. FR-208's listener sees every
 * notification on the device; what it does with almost all of them is nothing, and that is the
 * part worth pinning.
 */
enum class NotificationSkip {
    /** FR-209/FR-1003: the layer is off. The default, and the only default this may have. */
    LAYER_OFF,

    /** FR-212: the user did not choose this application. */
    NOT_MONITORED,

    /**
     * Latch's own notification.
     *
     * FR-211 has this layer *post* a notification, and a listener that read its own would offer
     * to capture its own offer, for ever. The loop is obvious once written down and invisible
     * until it happens on a device.
     */
    OUR_OWN,

    /**
     * An ongoing notification — a media player, a download, a navigation session.
     *
     * These update many times a second and are not messages. Reading them would put the
     * listener through a parse on every progress tick.
     */
    ONGOING,

    /** A group summary, which repeats the text of the notifications it summarises. */
    GROUP_SUMMARY,

    /** Nothing readable. A notification with no text is not a message. */
    NO_TEXT,
}

/** Either an offer to make, or the reason there is none. */
sealed interface NotificationDecision {
    /**
     * FR-211: post a low-priority notification offering to capture. [text] is held in memory and
     * never written anywhere — see [NotificationCaptureHolder].
     */
    data class Offer(val text: String) : NotificationDecision

    data class Skip(val reason: NotificationSkip) : NotificationDecision
}

/**
 * FR-208, FR-209, FR-212: whether this notification is one to offer.
 *
 * Pure, and deliberately so. The listener itself needs a `NotificationListenerService`, so
 * nothing in it is reachable from a JVM test — which is exactly the shape that hid the drain's
 * FR-803 check and `decodeBitmap`'s null contract, each for a slice. What this decides is every
 * rule about which of the device's notifications Latch reads at all, and that is the part of
 * this feature a mistake in would be worst.
 *
 * **The parse is not here.** FR-208 says the layer detects *dates*, so a notification with no
 * date produces no offer — but that is the parser's answer and the caller asks for it after
 * this has said the notification may be read at all. Ordering it this way means a notification
 * from an application the user did not choose is never parsed, rather than parsed and then
 * discarded.
 */
fun notificationDecision(
    packageName: String,
    ownPackage: String,
    layerEnabled: Boolean,
    monitored: Set<String>,
    isOngoing: Boolean,
    isGroupSummary: Boolean,
    text: String,
): NotificationDecision = when {
    packageName == ownPackage -> NotificationDecision.Skip(NotificationSkip.OUR_OWN)
    !layerEnabled -> NotificationDecision.Skip(NotificationSkip.LAYER_OFF)
    packageName !in monitored -> NotificationDecision.Skip(NotificationSkip.NOT_MONITORED)
    isOngoing -> NotificationDecision.Skip(NotificationSkip.ONGOING)
    isGroupSummary -> NotificationDecision.Skip(NotificationSkip.GROUP_SUMMARY)
    text.isBlank() -> NotificationDecision.Skip(NotificationSkip.NO_TEXT)
    else -> NotificationDecision.Offer(text.trim())
}

/**
 * The text of a notification, held **in memory only**, between the offer and the confirmation.
 *
 * **This is how FR-210 and NFR-206 are met structurally rather than carefully.** "Notification
 * content shall be processed entirely in memory, never written to disk, and never transmitted."
 * The obvious implementation — put the message in the `PendingIntent`'s extras — fails that:
 * a posted notification's extras are held by the system's notification manager, which is not
 * this app's memory and not this app's to reason about. So the posted notification carries a
 * **key** and nothing else, and the text lives here, in this process, until the user confirms
 * or the process ends.
 *
 * **A process death loses the offer, and that is correct.** FR-210 says the content is processed
 * entirely in memory; memory that survived a process would be storage by another name. The user
 * gets the notification again the next time the message arrives, or captures it by hand.
 *
 * Bounded, because a listener sees everything: an offer the user ignored must not keep its text
 * alive for the life of the process. The oldest goes when the cap is reached.
 */
class NotificationCaptureHolder(private val capacity: Int = CAPACITY) {

    private val held = object : LinkedHashMap<String, String>(capacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
            size > capacity
    }

    /** Returns the key the posted notification will carry. The text itself never leaves here. */
    @Synchronized
    fun hold(text: String): String {
        val key = UUID.randomUUID().toString()
        held[key] = text
        return key
    }

    /**
     * Takes the text, and **removes it**.
     *
     * Removing rather than reading is the point: once the user has confirmed, the offer is
     * answered, and a second tap on a stale notification must find nothing rather than open a
     * capture of a message that has already been dealt with.
     */
    @Synchronized
    fun take(key: String): String? = held.remove(key)

    /** NFR-205, and anything else that means "forget everything". */
    @Synchronized
    fun clear() = held.clear()

    @Synchronized
    fun size(): Int = held.size

    private companion object {
        /**
         * Enough for a burst of messages while the user is away from the phone, and far short of
         * a day's notifications. The number is a reading: the cost of it being too small is an
         * offer that opens to nothing, which the app can say; the cost of it being too large is
         * message text alive in memory long after anyone cared.
         */
        const val CAPACITY = 20
    }
}
