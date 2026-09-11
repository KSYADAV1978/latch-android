package com.latch.android.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.latch.android.LatchApplication
import com.latch.google.GoogleHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * What did the user actually save, against what the classifier proposed?
 *
 * **The measurement this pillar has never been able to take.** `LatchCardOcr` logs the draft the
 * classifier produced; FR-1205's edits on the sheet are logged nowhere, so a card the user
 * corrected before saving and one they accepted as read leave an identical trace. The correction
 * rate is the single most useful number for FR-1204 - it says how often the classifier is wrong
 * in a way that *mattered enough to fix* - and until now it could not be collected at all.
 *
 * **The account is where the answer is.** A contact Latch created carries FR-1207's record in
 * `clientData`, so the contacts this app wrote can be told from the user's own, and each one's
 * stored fields can be compared against the draft in the log. A difference is an edit.
 *
 * **Read-only, and that is the whole design.** It pages `connections`, filters on the presence of
 * `latch.card.version`, and prints. It creates nothing, patches nothing and deletes nothing -
 * unlike `DebugClientDataProbe`, which writes and sweeps. There is no argument that makes it
 * write. That matters because it runs against a real address book.
 *
 * **It prints contact content**, which nothing else in this application does except
 * `LatchCardOcr`, and for the same reason: the question cannot be answered without it. Debug
 * source set only, so it does not exist in a release build at all.
 *
 * ```
 * adb shell am broadcast -a com.latch.android.debug.PROBE_SAVED_CARDS \
 *   -n com.latch.android/com.latch.android.debug.DebugSavedCardsProbeReceiver
 * adb logcat -d -v time | grep LatchSavedProbe
 * ```
 */
class DebugSavedCardsProbeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as LatchApplication
        CoroutineScope(Dispatchers.IO).launch {
            val http = GoogleHttp(app.authClient)
            val fields = "names,organizations,emailAddresses,phoneNumbers,addresses,urls,clientData"
            var token: String? = null
            var pages = 0
            var found = 0
            try {
                do {
                    val url = "$BASE/people/me/connections?personFields=$fields&pageSize=1000" +
                        (token?.let { "&pageToken=$it" } ?: "")
                    val page = http.get(url)
                    val people = page.optJSONArray("connections")
                    for (i in 0 until (people?.length() ?: 0)) {
                        val person = people?.optJSONObject(i) ?: continue
                        val client = person.optJSONArray("clientData")
                        var isLatch = false
                        var capturedAt = ""
                        for (c in 0 until (client?.length() ?: 0)) {
                            val entry = client?.optJSONObject(c) ?: continue
                            when (entry.optString("key")) {
                                "latch.card.version" -> isLatch = true
                                "latch.card.captured_at" -> capturedAt = entry.optString("value")
                            }
                        }
                        if (!isLatch) continue
                        found++
                        val name = person.optJSONArray("names")?.optJSONObject(0)
                        val org = person.optJSONArray("organizations")?.optJSONObject(0)
                        Log.i(TAG, "--- ${person.optString("resourceName")} captured=$capturedAt")
                        Log.i(TAG, "  name=${name?.optString("displayName")}")
                        Log.i(TAG, "  org=${org?.optString("name")} title=${org?.optString("title")}")
                        Log.i(TAG, "  email=${values(person, "emailAddresses", "value")}")
                        Log.i(TAG, "  phone=${values(person, "phoneNumbers", "value")}")
                        Log.i(TAG, "  addr=${values(person, "addresses", "formattedValue")}")
                    }
                    token = page.optString("nextPageToken").takeIf { it.isNotBlank() }
                    pages++
                } while (token != null && pages < 10)
                Log.i(TAG, "done pages=$pages latchContacts=$found")
            } catch (failure: Exception) {
                Log.i(TAG, "failed ${failure.javaClass.simpleName}: ${failure.message?.take(200)}")
            }
        }
    }

    private fun values(person: com.latch.google.json.JSONObject, key: String, field: String): String {
        val array = person.optJSONArray(key) ?: return ""
        return (0 until array.length())
            .mapNotNull { array.optJSONObject(it)?.optString(field)?.takeIf { v -> v.isNotBlank() } }
            .joinToString(" | ")
    }

    private companion object {
        const val TAG = "LatchSavedProbe"
        const val BASE = "https://people.googleapis.com/v1"
    }
}
