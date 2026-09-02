package com.latch.android.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.latch.android.LatchApplication
import com.latch.data.DebugPage
import com.latch.wire.KEY_ITEM_KEY
import com.latch.wire.KEY_SOURCE_HASH
import com.latch.data.debugProbeProperty
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * A debug-only instrument for the open FR-803 defect: the event-side duplicate query returns
 * nothing at HEAD, in the app process and in a worker alike, for a `latch.source_hash` carried
 * by events in the very calendar being queried. See CLAUDE.md and SRS 1.37.
 *
 * **It lives in the `debug` source set, not behind a `BuildConfig.DEBUG` branch.** A manifest
 * component guarded by a constant is still declared in the release manifest, and an exported
 * receiver that runs a Google query on request is not something a release build should
 * advertise. Here it does not exist in release at all.
 *
 * **What this second version tests.** The first established that both call sites issue the
 * same URL and both get `items=0`, which exonerated the drain and moved the fault to the query
 * itself. The remaining structural difference between the query that misses and the
 * `latch.item_key` query that matches is `maxResults` — 1 against 250 — and the hypothesis is
 * **paging**: Google's filtered `events.list` applies the filter to a page of the scan rather
 * than selecting the page, so a one-event page can come back empty *carrying a
 * `nextPageToken`*, while 250 covers a small calendar in one go. That would also explain
 * AC-07 passing on 27 Aug, when the calendar held about one event.
 *
 * So it runs three queries and logs `nextPageToken` for each. **An empty first page with a
 * token confirms it**, and the fix is then to follow the token to exhaustion rather than to
 * raise the number — correctness should not depend on how large a calendar is.
 *
 * ```
 * adb shell am broadcast -a com.latch.android.debug.PROBE_DEDUP \
 *   --es hash <source_hash> --es key <item_key> \
 *   -n com.latch.android/com.latch.android.debug.DebugDedupProbeReceiver
 * adb logcat -d -v time | grep LatchDedupProbe
 * ```
 *
 * The live fixture is the pair of `Kickoff 8 September 2027 at 9am` events, kept in the
 * account until this is closed: `sh=b3375f4a…43db6a`, `ik=f5d2496e…84d34`.
 */
class DebugDedupProbeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val hash = intent.getStringExtra("hash")
        val itemKey = intent.getStringExtra("key")
        if (hash.isNullOrBlank()) {
            Log.w(TAG, "no --es hash given; nothing to probe")
            return
        }
        val app = context.applicationContext as LatchApplication

        CoroutineScope(Dispatchers.IO).launch {
            val defaults = runCatching { app.accountDefaults.allAccounts().firstOrNull() }.getOrNull()
            if (defaults == null) {
                Log.w(TAG, "no account defaults; setup has not run")
                return@launch
            }
            val calendar = defaults.destinationCalendarId
            Log.i(TAG, "calendar=<$calendar>")

            // The production path itself, through CalendarApi — the one thing that decides
            // whether FR-803 protects an event-only capture.
            val production = runCatching { app.calendarApi.findEventBySourceHash(calendar, hash) }
            Log.i(
                TAG,
                "PRODUCTION findEventBySourceHash -> " + production.fold(
                    { "found=${it.found} id=<${it.existingId.orEmpty()}> scanCapped=${it.scanCapped}" },
                    { "threw ${it::class.simpleName}: ${it.message}" },
                ),
            )

            // The raw pages behind it, for the record.
            report("hash maxResults=1  ", debugProbeProperty(app.authClient, calendar, KEY_SOURCE_HASH, hash, 1))

            // The same query, only wider. If this one matches, the number was the whole bug.
            report("hash maxResults=250", debugProbeProperty(app.authClient, calendar, KEY_SOURCE_HASH, hash, 250))

            // The FR-804 query that demonstrably does match, as the control.
            if (itemKey.isNullOrBlank()) {
                Log.i(TAG, "key  maxResults=250: skipped, no --es key given")
            } else {
                report("key  maxResults=250", debugProbeProperty(app.authClient, calendar, KEY_ITEM_KEY, itemKey, 250))
            }
        }
    }

    private fun report(label: String, page: DebugPage) {
        Log.i(
            TAG,
            "$label -> status=${page.status ?: "none"} items=${page.itemCount ?: "none"} " +
                "nextPageToken=${if (page.nextPageToken != null) "YES" else "no"}" +
                (page.error?.let { " error=$it" } ?: ""),
        )
    }

    companion object {
        const val TAG = "LatchDedupProbe"
    }
}

/**
 * FR-806a's device confirmation, in about a minute of phone time.
 *
 * AC-10 verified on 28 Aug that an offline capture is queued rather than lost, but it ran
 * inside a session whose token was still cached — so `authorize()` was never called and the
 * expired-token half of that criterion has never been checked. Reproducing it honestly meant
 * waiting an hour for a token to lapse, which is not a thing anyone does on a work phone.
 *
 * This drops the cached token so the next capture must re-authorize:
 *
 * ```
 * adb shell am broadcast -a com.latch.android.debug.INVALIDATE_TOKEN  *   -n com.latch.android/com.latch.android.debug.DebugInvalidateTokenReceiver
 * ```
 *
 * Then: aeroplane mode on, capture the Kickoff text, and the save should report **queued
 * within a second** with the home screen counting it — where before FR-806a it suspended
 * silently and indefinitely. Aeroplane mode off, and the entry should retire without writing.
 */
class DebugInvalidateTokenReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as LatchApplication
        CoroutineScope(Dispatchers.IO).launch {
            val token = runCatching { app.authClient.accessToken() }.getOrNull()
            if (token == null) {
                Log.w(TAG, "no token to invalidate (already cold, or no grant)")
                return@launch
            }
            runCatching { app.authClient.invalidate(token) }
                .onSuccess { Log.i(TAG, "cached token invalidated; the next capture must re-authorize") }
                .onFailure { Log.w(TAG, "invalidate failed: ${it::class.simpleName}: ${it.message}") }
        }
    }

    companion object {
        const val TAG = "LatchTokenHook"
    }
}
