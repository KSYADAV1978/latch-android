package com.latch.android.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.latch.android.LatchApplication
import com.latch.android.capture.DuplicateProbe
import com.latch.android.capture.duplicateProbeFor
import com.latch.android.capture.runDuplicateProbe
import com.latch.core.model.Item
import com.latch.core.model.ItemType
import com.latch.data.CalendarApi
import com.latch.data.GoogleFailure
import com.latch.data.GoogleRejected
import com.latch.data.PendingWrite
import com.latch.data.RemoteMetadata
import com.latch.data.TasksApi
import com.latch.data.eventDedupUrl
import java.time.Instant
import java.time.LocalDateTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * A debug-only instrument for the open FR-806 defect: on 31 Aug 2026 a queued event-only
 * capture drained and wrote a duplicate, although an event carrying the same
 * `latch.source_hash` was already in the same calendar.
 *
 * **It lives in the `debug` source set, not behind a `BuildConfig.DEBUG` branch.** A manifest
 * component guarded by a constant is still declared in the release manifest, and an
 * `exported` receiver that runs a Google query on request is not something a release build
 * should even advertise. Here it does not exist in release at all.
 *
 * **It runs the same probe twice, in the two contexts that differ**, because that is what the
 * evidence needs. Everything reachable from a JVM test is already exonerated: the probe's
 * construction, the drain's decision flow, and the round trip from the hash a write puts on an
 * item to the hash the check queries with. What is left is the *wire*, and the only difference
 * between the entry that retired correctly that night and the one that duplicated is which
 * process asked and which transport answered.
 *
 * Two matching lines each returning one item mean the query is sound and the drain's **inputs**
 * differed that night, which points at the encrypted store's round trip of that entry. A
 * worker returning zero where the foreground returns one means the worker's path itself
 * differs, and the two logged URLs name how.
 *
 * ```
 * adb shell am broadcast -a com.latch.android.debug.PROBE_DEDUP \
 *   --es hash b3375f4a20bb6c1d5cea22c95c59d1f7feb896d40c836671910d9b972543db6a \
 *   -n com.latch.android/com.latch.android.debug.DebugDedupProbeReceiver
 * adb logcat -d -v time | grep LatchDedupProbe
 * ```
 *
 * The hash above is the live fixture: two `Kickoff 8 September 2027 at 9am` events carry it,
 * and both are being kept in the account until this is closed.
 */
class DebugDedupProbeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val hash = intent.getStringExtra("hash")
        if (hash.isNullOrBlank()) {
            Log.w(TAG, "no --es hash given; nothing to probe")
            return
        }
        val app = context.applicationContext as LatchApplication

        // The foreground half runs here, in the app process, exactly as CaptureSaver's would.
        CoroutineScope(Dispatchers.IO).launch {
            val defaults = runCatching { app.accountDefaults.allAccounts().firstOrNull() }.getOrNull()
            if (defaults == null) {
                Log.w(TAG, "app: no account defaults; setup has not run")
                return@launch
            }
            probe(
                where = "app",
                calendarId = defaults.destinationCalendarId,
                sourceHash = hash,
                calendarApi = app.calendarApi,
                tasksApi = app.tasksApi,
            )
        }

        // The worker half runs in WorkManager, through the drain's own duplicateProbeFor.
        WorkManager.getInstance(context).enqueue(
            OneTimeWorkRequestBuilder<DebugDedupProbeWorker>()
                .setInputData(androidx.work.Data.Builder().putString("hash", hash).build())
                .build()
        )
    }

    companion object {
        const val TAG = "LatchDedupProbe"
    }
}

/** The worker half: the same query, issued from where the failing drain issued it. */
class DebugDedupProbeWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {
        val hash = inputData.getString("hash") ?: return Result.failure()
        val app = applicationContext as LatchApplication
        val defaults = runCatching { app.accountDefaults.allAccounts().firstOrNull() }.getOrNull()
            ?: run {
                Log.w(DebugDedupProbeReceiver.TAG, "worker: no account defaults")
                return Result.failure()
            }

        // Built the way a queued entry is, so duplicateProbeFor sees what the drain sees:
        // the calendarId travels on the item, the hash on the metadata.
        val write = PendingWrite(
            items = listOf(
                Item(
                    id = "probe#0",
                    captureId = "probe",
                    type = ItemType.EVENT,
                    title = "probe",
                    start = LocalDateTime.now(),
                    end = LocalDateTime.now().plusHours(1),
                    calendarId = defaults.destinationCalendarId,
                ),
            ),
            metadata = RemoteMetadata(
                sourceHash = hash,
                itemKey = hash,
                chainId = "probe",
                capturedAt = Instant.now(),
            ),
            body = "probe",
            timeZone = "UTC",
        )

        val built = duplicateProbeFor(write)
        val calendarId = (built as? DuplicateProbe.Event)?.calendarId.orEmpty()
        Log.i(
            DebugDedupProbeReceiver.TAG,
            "worker: duplicateProbeFor gave calendarId=<$calendarId> hash=<${built.sourceHash}>",
        )

        return runCatching {
            val found = runDuplicateProbe(built, app.calendarApi, app.tasksApi)
            Log.i(
                DebugDedupProbeReceiver.TAG,
                "worker: url=<${eventDedupUrl(calendarId, built.sourceHash)}> " +
                    "status=200 items=${if (found.found) 1 else 0} id=<${found.existingId.orEmpty()}>",
            )
            Result.success()
        }.getOrElse { failure ->
            logFailure("worker", calendarId, built.sourceHash, failure)
            Result.success()
        }
    }
}

private suspend fun probe(
    where: String,
    calendarId: String,
    sourceHash: String,
    calendarApi: CalendarApi,
    tasksApi: TasksApi,
) {
    Log.i(DebugDedupProbeReceiver.TAG, "$where: calendarId=<$calendarId> hash=<$sourceHash>")
    runCatching { calendarApi.findEventBySourceHash(calendarId, sourceHash) }
        .onSuccess { found ->
            Log.i(
                DebugDedupProbeReceiver.TAG,
                "$where: url=<${eventDedupUrl(calendarId, sourceHash)}> " +
                    "status=200 items=${if (found.found) 1 else 0} id=<${found.existingId.orEmpty()}>",
            )
        }
        .onFailure { logFailure(where, calendarId, sourceHash, it) }
}

/** A non-200 throws before parsing, so the status is only ever available from the failure. */
private fun logFailure(where: String, calendarId: String, sourceHash: String, failure: Throwable) {
    val status = (failure as? GoogleRejected)?.status
    val kind = if (failure is GoogleFailure) failure::class.simpleName else failure::class.simpleName
    Log.w(
        DebugDedupProbeReceiver.TAG,
        "$where: url=<${eventDedupUrl(calendarId, sourceHash)}> " +
            "status=${status ?: "none"} items=none $kind: ${failure.message}",
    )
}
