package com.latch.android.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.latch.android.LatchApplication
import com.latch.google.GoogleHttp
import com.latch.google.json.JSONArray
import com.latch.google.json.JSONObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * FR-1240(a): what are the People API's limits on `clientData`?
 *
 * **This probe exists because the documentation does not answer the question.** The Person
 * resource reference defines `clientData` as key/value pairs with duplicates allowed and states
 * no limit on entry count, key length or value length; neither does the People API overview, and
 * a search of Google's developer documentation and issue tracker turns up nothing. FR-1207 is
 * unimplementable without those numbers — §7.2's analogue has to fit — so an empirical answer is
 * the *only* answer available, which is why FR-1240 gates contact-write code on it.
 *
 * **What it is actually looking for is silent truncation, not an error.** An API that refuses an
 * over-large payload is safe: the write fails, the caller knows. An API that accepts 200 entries
 * and stores 50 is not, because §7.2's metadata would go missing from items already in a user's
 * account, and FR-1208's duplicate check would then miss them for ever with nothing to report.
 * So every trial **reads the contact back** and compares what was stored against what was sent.
 * A trial that "succeeded" and stored less is the finding.
 *
 * **Account safety.** Each trial creates one contact, reads it, and deletes it before the next
 * begins — so at most one probe contact exists at a time and none is left behind on the ordinary
 * path. Every contact is named with [MARKER], and [sweep] finds and deletes any straggler from a
 * run that died half way. Nothing here touches a contact it did not create.
 *
 * **It lives in the `debug` source set**, not behind a `BuildConfig.DEBUG` branch, for the reason
 * `DebugDedupProbe` gives: a manifest component guarded by a constant is still declared in the
 * release manifest, and an exported receiver that writes to a user's contacts is not something a
 * release build should advertise. Here it does not exist in release at all.
 *
 * ```
 * adb shell am broadcast -a com.latch.android.debug.PROBE_CLIENT_DATA \
 *   -n com.latch.android/com.latch.android.debug.DebugClientDataProbeReceiver
 * adb logcat -d -v time | grep LatchCardProbe
 * ```
 */
class DebugClientDataProbeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as LatchApplication
        val deleteOnly = intent.getStringExtra("delete")
        CoroutineScope(Dispatchers.IO).launch {
            val http = GoogleHttp(app.authClient)

            // Targeted cleanup: `--es delete people/c123…`. More precise than the sweep and
            // needed because `searchContacts` is not reliable immediately after a write.
            if (!deleteOnly.isNullOrBlank()) {
                // Does it still exist? A 404 here means an earlier attempt succeeded and
                // there is nothing to chase — which is a different answer from "delete failed".
                val exists = runCatching { http.get("$BASE/$deleteOnly?personFields=names") }
                Log.i(
                    TAG,
                    "exists=${exists.isSuccess} " +
                        exists.exceptionOrNull()?.let { "${it.javaClass.simpleName}: ${it.message?.take(200)}" }
                            .orEmpty(),
                )
                repeat(3) { attempt ->
                    val done = runCatching { http.delete("$BASE/$deleteOnly:deleteContact") }
                    if (done.isSuccess) {
                        Log.i(TAG, "deleted $deleteOnly")
                        return@launch
                    }
                    val failure = done.exceptionOrNull()
                    Log.w(
                        TAG,
                        "delete attempt $attempt: ${failure?.javaClass?.simpleName}: " +
                            "${failure?.message?.take(240)} cause=${failure?.cause}",
                    )
                }
                Log.w(TAG, "COULD NOT DELETE $deleteOnly")
                return@launch
            }

            runCatching { sweep(http) }
                .onFailure { Log.w(TAG, "sweep failed: ${it.javaClass.simpleName}") }

            // **One trial per process, driven from outside** (SRS 1.90). This build gets exactly
            // one successful People API request per process and then `people.googleapis.com`
            // stops resolving until a force-stop — reproduced three times. Until that is
            // understood, the probe is driven one trial at a time with a force-stop between, so
            // the limits can be measured without the answer depending on the defect.
            // Sweep only: confirm the account holds no probe contact, creating nothing.
            if (intent.getStringExtra("sweep") != null) {
                runCatching { sweep(http) }
                    .onFailure { Log.w(TAG, "sweep failed: ${it.javaClass.simpleName}: ${it.message?.take(160)}") }
                Log.i(TAG, "probe finished")
                return@launch
            }

            val oneEntries = intent.getIntExtra("entries", -1)
            val oneChars = intent.getIntExtra("chars", -1)
            if (oneEntries > 0 && oneChars > 0) {
                trial(http, "entries=$oneEntries chars=$oneChars", oneEntries, oneChars)
                Log.i(TAG, "probe finished")
                return@launch
            }

            // Entry counts first, then value sizes, each escalating until something gives.
            // Ordinary use is four to six entries of about seventy characters — §7.2's keys —
            // so the first trial of each series is the shape Latch would actually write, and a
            // failure there would be a finding on its own.
            for (count in listOf(6, 25, 50, 100, 250, 500)) {
                if (!trial(http, "entries=$count", entries = count, valueChars = 64)) break
            }
            for (chars in listOf(64, 1_000, 8_000, 32_000, 131_072)) {
                if (!trial(http, "valueChars=$chars", entries = 4, valueChars = chars)) break
            }

            runCatching { sweep(http) }
            Log.i(TAG, "probe finished")
        }
    }

    /**
     * One trial. Returns false to stop the series — either the API refused, or it accepted and
     * stored something different, and in both cases larger is not worth asking about.
     */
    private suspend fun trial(
        http: GoogleHttp,
        label: String,
        entries: Int,
        valueChars: Int,
    ): Boolean {
        val sent = (0 until entries).map { index -> "latch.probe.$index" to "v".repeat(valueChars) }
        val body = JSONObject()
            .put("names", JSONArray().put(JSONObject().put("givenName", MARKER)))
            .put(
                "clientData",
                JSONArray().also { array ->
                    sent.forEach { (key, value) ->
                        array.put(JSONObject().put("key", key).put("value", value))
                    }
                },
            )

        // **A probe that cannot tell a refusal from a dropped connection invents limits**
        // (SRS 1.90). The first run of this reported `entries=25 REFUSED` and the cause was
        // `UnknownHostException` — a DNS blip — which would have gone into the specification as
        // "the People API refuses 25 entries". So a transport failure is retried, and if it
        // persists the series stops as INCONCLUSIVE rather than claiming a boundary.
        var created: JSONObject? = null
        var transportFailures = 0
        while (created == null) {
            try {
                created = http.post("$BASE/people:createContact?personFields=names,clientData", body)
            } catch (failure: Exception) {
                val cause = failure.cause
                if (cause is java.io.IOException && transportFailures < 2) {
                    transportFailures++
                    Log.w(TAG, "$label transport failure ${cause.javaClass.simpleName}, retrying")
                    kotlinx.coroutines.delay(1_500)
                    continue
                }
                if (cause is java.io.IOException) {
                    Log.w(TAG, "$label INCONCLUSIVE — network, not the API: ${cause.message?.take(140)}")
                    return false
                }
                // The safe outcome: refused by Google, loudly, before anything was stored.
                Log.i(
                    TAG,
                    "$label REFUSED ${failure.javaClass.simpleName}: ${failure.message?.take(120)} " +
                        "cause=${cause?.javaClass?.name}: ${cause?.message?.take(200)}",
                )
                return false
            }
        }

        val name = created.optString("resourceName")
        try {
            val readBack = http.get("$BASE/$name?personFields=clientData")
            val stored = readBack.optJSONArray("clientData")
            val storedCount = stored?.length() ?: 0
            val storedChars = (0 until storedCount)
                .mapNotNull { stored?.optJSONObject(it)?.optString("value")?.length }
                .maxOrNull() ?: 0

            // The comparison is the whole point. Accepting and storing less is worse than
            // refusing, because nothing would ever report it.
            val intact = storedCount == entries && storedChars == valueChars
            Log.i(
                TAG,
                "$label ACCEPTED stored entries=$storedCount/$entries " +
                    "valueChars=$storedChars/$valueChars ${if (intact) "INTACT" else "TRUNCATED"}",
            )
            return intact
        } finally {
            // Retried, because leaving a contact behind in somebody's account is the one
            // outcome this probe must not have — and the first run did exactly that when a
            // single delete met a DNS failure.
            var deleted = false
            repeat(4) {
                if (!deleted && runCatching { http.delete("$BASE/$name:deleteContact") }.isSuccess) {
                    deleted = true
                }
            }
            if (!deleted) Log.w(TAG, "COULD NOT DELETE $name — delete it with --es delete $name")
        }
    }

    /**
     * Delete any probe contact left behind by a run that died between creating and deleting.
     *
     * Matches on [MARKER] and on nothing else. A sweep that deleted by any looser rule would be
     * a debug build capable of removing a user's own contacts, which is not a thing to write
     * even once.
     */
    private suspend fun sweep(http: GoogleHttp) {
        val found = http.get(
            "$BASE/people:searchContacts?query=$MARKER&readMask=names&pageSize=30"
        ).optJSONArray("results") ?: return
        Log.i(TAG, "sweep: searchContacts returned ${found.length()} result(s) for the marker")
        for (index in 0 until found.length()) {
            val person = found.optJSONObject(index)?.optJSONObject("person") ?: continue
            val given = person.optJSONArray("names")?.optJSONObject(0)?.optString("givenName")
            if (given != MARKER) continue
            val name = person.optString("resourceName")
            runCatching { http.delete("$BASE/$name:deleteContact") }
                .onSuccess { Log.i(TAG, "swept $name") }
        }
    }

    private companion object {
        const val TAG = "LatchCardProbe"
        /**
         * The version root, not the collection. `resourceName` comes back as `people/c123…`
         * already carrying the collection, so a base ending in `/people` produces
         * `/v1/people/people/c123:deleteContact` — which is a 404 against a real account and
         * would have left every probe contact behind for the sweep to find.
         */
        const val BASE = "https://people.googleapis.com/v1"

        /** Distinctive on purpose: the sweep deletes what matches this and nothing else. */
        const val MARKER = "LatchClientDataProbeDeleteMe"
    }
}
