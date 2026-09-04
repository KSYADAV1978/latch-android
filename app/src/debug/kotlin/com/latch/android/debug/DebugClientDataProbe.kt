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
            // FR-803's index, read from inside the app's own process (SRS 1.92).
            //
            // Pulling `latch.db` off the device does not work — `run-as cat` yields a file of
            // the right length whose `page_count` is zero — so the question is asked here
            // instead. **A duplicate write shows as two rows with one `source_hash`**: the
            // primary key is (container_id, remote_id), so a message written twice leaves two
            // rows, and the index is the only local record of what this device put in the
            // account. Read-only, and it reads `source_hash` alone — a SHA-256 digest is not
            // content, which is why NFR-206 lets this column be stored in the clear.
            if (intent.getStringExtra("index") != null) {
                val db = runCatching {
                    android.database.sqlite.SQLiteDatabase.openDatabase(
                        context.getDatabasePath("latch.db").path,
                        null,
                        android.database.sqlite.SQLiteDatabase.OPEN_READONLY,
                    )
                }.getOrNull()
                if (db == null) {
                    Log.w(TAG, "index: could not open latch.db")
                } else {
                    db.rawQuery("SELECT COUNT(*), COUNT(DISTINCT source_hash) FROM written_items", null)
                        .use { c ->
                            if (c.moveToFirst()) {
                                Log.i(TAG, "index: rows=${c.getInt(0)} distinct source_hash=${c.getInt(1)}")
                            }
                        }
                    db.rawQuery(
                        "SELECT source_hash, item_type, COUNT(*) n FROM written_items " +
                            "GROUP BY source_hash, item_type HAVING n > 1 ORDER BY n DESC",
                        null,
                    ).use { c ->
                        var found = 0
                        while (c.moveToNext()) {
                            found++
                            // The hash is truncated in the log: it is not content, but there is
                            // no reason to print more of it than identifies the row.
                            Log.w(
                                TAG,
                                "index: DUPLICATE ${c.getString(0).take(12)}… " +
                                    "type=${c.getString(1)} written ${c.getInt(2)} times",
                            )
                        }
                        Log.i(TAG, "index: $found source_hash/type pair(s) written more than once")
                    }
                    db.close()
                }
                Log.i(TAG, "probe finished")
                return@launch
            }

            // Diagnosis for the POST defect (SRS 1.90). Counts open file descriptors around
            // each request: if a POST leaks a connection, the count climbs and stays up, and
            // `UnknownHostException` is a process out of sockets rather than a name that will
            // not resolve. If it does not climb, the leak hypothesis is wrong and the recorded
            // reading has to change.
            if (intent.getStringExtra("diag") != null) {
                fun fds() = runCatching { java.io.File("/proc/self/fd").list()?.size ?: -1 }.getOrDefault(-2)
                fun step(label: String, block: suspend () -> Unit) = label to block

                Log.i(TAG, "diag start fds=${fds()}")

                // **Is the poisoning host-specific or process-wide?** That is the whole of the
                // date-pillar question: if a failed People request also kills Calendar and Tasks
                // in the same process, this is a defect in the shipped product rather than a
                // contacts finding.
                suspend fun calendar(label: String) {
                    val r = runCatching {
                        http.get("https://www.googleapis.com/calendar/v3/users/me/calendarList?maxResults=1")
                    }
                    Log.i(
                        TAG,
                        "diag $label CALENDAR ok=${r.isSuccess} " +
                            (r.exceptionOrNull()?.let { "${it.javaClass.simpleName}/${it.cause?.javaClass?.simpleName}" } ?: ""),
                    )
                }
                calendar("before")
                var created: String? = null
                repeat(3) { round ->
                    val post = runCatching {
                        http.post(
                            "$BASE/people:createContact?personFields=names",
                            JSONObject().put(
                                "names",
                                JSONArray().put(JSONObject().put("givenName", MARKER)),
                            ),
                        )
                    }
                    Log.i(
                        TAG,
                        "diag round=$round POST ok=${post.isSuccess} fds=${fds()} " +
                            "thread=${Thread.currentThread().name} " +
                            "interrupted=${Thread.currentThread().isInterrupted} " +
                            (post.exceptionOrNull()?.let { "${it.javaClass.simpleName}/${it.cause?.javaClass?.simpleName}" } ?: ""),
                    )
                    post.getOrNull()?.optString("resourceName")?.let { name ->
                        created = name
                        val del = runCatching { http.delete("$BASE/$name:deleteContact") }
                        Log.i(TAG, "diag round=$round DELETE ok=${del.isSuccess} fds=${fds()}")
                        if (del.isSuccess) created = null
                    }
                    val get = runCatching { http.get("$BASE/people/me?personFields=names") }
                    Log.i(
                        TAG,
                        "diag round=$round GET ok=${get.isSuccess} fds=${fds()} " +
                            "thread=${Thread.currentThread().name} " +
                            "interrupted=${Thread.currentThread().isInterrupted}",
                    )
                }
                calendar("after")
                created?.let { Log.w(TAG, "diag left behind $it") }
                runCatching { sweep(http) }
                Log.i(TAG, "diag end fds=${fds()}")
                Log.i(TAG, "probe finished")
                return@launch
            }

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
