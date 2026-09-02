package com.latch.android.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.latch.android.LatchApplication
import com.latch.android.R
import com.latch.android.capture.CaptureActivity
import com.latch.android.capture.EXTRA_NOTIFICATION_KEY
import com.latch.core.model.CaptureLayer
import com.latch.parser.DateParser
import com.latch.parser.ParseContext
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * FR-208 to FR-212: the notification listener.
 *
 * **The one component NFR-104 permits to be persistent**, and it says so by name: "No persistent
 * background service on Android other than the optional notification listener." It is bound by
 * the system, not started by this app, and only after the user has granted notification access
 * through the disclosure screen FR-209 requires.
 *
 * **Nothing this reads is written anywhere.** FR-210 and NFR-206 say so, and the way it is met
 * is structural rather than careful: the message text goes into [NotificationCaptureHolder], in
 * this process's memory, and the notification this posts carries only a key. The obvious
 * implementation — the message in a `PendingIntent`'s extras — would hand the text to the
 * system's notification manager, which is not this app's memory to reason about.
 *
 * **It never creates an item** (FR-211). It posts a low-priority offer; the user taps it, the
 * ordinary confirmation screen opens, and everything after that is the path every other capture
 * takes — including FR-805a, which stores no source text for this layer, and FR-210a, which
 * sends no webhook for it. Both are properties of `CaptureSource`, so this file does not
 * implement either and cannot get either wrong.
 *
 * **A capture from here is never routed to the Inbox**, for the same reason and by the same
 * property (`routableToInbox`): the Inbox is persistent storage.
 */
class LatchNotificationListener : NotificationListenerService() {

    private val app get() = applicationContext as LatchApplication

    override fun onListenerConnected() {
        super.onListenerConnected()
        ensureChannel()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val settings = app.settings.value

        // FR-212's list is built from what the listener has seen, which is the only way to
        // offer one without `QUERY_ALL_PACKAGES` — a restricted permission this app will not
        // ask for to populate a settings screen. A package name is not notification *content*,
        // which is what NFR-206 governs, and the reading is recorded in the SRS.
        app.rememberNotifyingPackage(sbn.packageName)

        val extras = sbn.notification.extras
        val text = listOfNotNull(
            extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
            extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            // BIG_TEXT carries the whole message where the collapsed one was truncated, and a
            // truncated date is a wrong date rather than a missing one.
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
        ).distinct().joinToString("\n").trim()

        val decision = notificationDecision(
            packageName = sbn.packageName,
            ownPackage = packageName,
            layerEnabled = CaptureLayer.NOTIFICATION in settings.enabledLayers,
            monitored = settings.monitoredPackages,
            isOngoing = sbn.isOngoing,
            isGroupSummary = sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0,
            text = text,
        )
        val offer = (decision as? NotificationDecision.Offer) ?: return

        // FR-208 detects **dates**. A message with none is not an offer, and asking the parser
        // only after the decision above means a notification from an app the user did not
        // choose is never parsed at all.
        val zone = settings.timeZone?.let { runCatching { ZoneId.of(it) }.getOrNull() }
            ?: ZoneId.systemDefault()
        val parsed = DateParser.parse(offer.text, ParseContext(LocalDateTime.now(zone), zone))
        if (parsed.primary.date == null && parsed.primary.time == null) return

        postOffer(offer.text, parsed.title.value, sbn.id)
    }

    /**
     * FR-211: "a **low-priority** notification offering to capture it."
     *
     * `IMPORTANCE_LOW` means no sound and no heads-up — this is an offer about a message the
     * user has already been notified of once, and interrupting them a second time for it would
     * make the feature something to turn off.
     *
     * The notification's own text is the **derived title**, never the message: it appears in the
     * shade, on a lock screen, and in whatever the user's watch mirrors. FR-805a already keeps
     * the message out of the item; putting it back on a lock screen would be the same mistake
     * one surface over.
     */
    private fun postOffer(text: String, title: String, sourceId: Int) {
        val key = app.notificationCaptures.hold(text)

        val intent = Intent(this, CaptureActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra(EXTRA_NOTIFICATION_KEY, key)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pending = PendingIntent.getActivity(
            this,
            key.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_today)
            .setContentTitle(getString(R.string.notification_offer_title))
            .setContentText(title)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()

        getSystemService(NotificationManager::class.java)
            ?.notify(OFFER_ID_BASE + sourceId, notification)
    }

    private fun ensureChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            // FR-211's "low-priority", as the platform expresses it.
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = getString(R.string.notification_channel_description) }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private companion object {
        const val CHANNEL_ID = "latch.capture-offers"

        /**
         * Offsets our ids away from anything else this app might post, and keys them on the
         * source notification so a message updated in place replaces its own offer rather than
         * stacking a second one.
         */
        const val OFFER_ID_BASE = 8_000
    }
}
