package com.latch.desktop.queue

import com.latch.core.model.ItemType
import com.latch.google.CalendarApi
import com.latch.google.EventWrite
import com.latch.google.FailureClass
import com.latch.google.GoogleFailure
import com.latch.google.GoogleRejected
import com.latch.google.GoogleUnreachable
import com.latch.google.RetryPolicy
import com.latch.google.TaskWrite
import com.latch.google.TasksApi
import com.latch.google.isWorthRetrying
import com.latch.wire.bodyWithNote
import java.time.Duration
import java.time.Instant

/** What has just happened that might mean the queue could drain now (FR-806). */
enum class DrainTrigger {
    /** A capture was queued. There is new work; nothing about the old work has changed. */
    CAPTURE_QUEUED,

    /** The process started, and a queue may have outlived the one that made it. */
    PROCESS_START,

    /** The machine has a network again. The reason the work stopped may have gone away. */
    CONNECTIVITY_RESTORED,

    /** Something else reached Google successfully, which says the same thing more reliably. */
    REQUEST_SUCCEEDED,

    /** The user pressed Retry now. */
    USER_ASKED,

    /** The timer came round. */
    SCHEDULED,
}

/**
 * What a drain of one entry produced.
 *
 * `Retired` covers both "written" and "was already there" deliberately: FR-803 answering yes at
 * drain means the entry has nothing left to do, and treating that as a failure would retry it
 * for ever against an item that already exists.
 */
sealed interface DrainOutcome {
    data class Retired(val written: Int, val alreadySaved: Boolean = false) : DrainOutcome
    data class Deferred(val entry: QueuedWrite) : DrainOutcome
    data class GaveUp(val entry: QueuedWrite) : DrainOutcome
}

/**
 * Which entries are due.
 *
 * **An entry younger than the undo window is skipped.** Android's `drainable` exists so an undo
 * is never a race between dropping a queue entry and chasing an item that has just been
 * written. FR-807's undo is not built on this client yet — and the rule is here anyway, because
 * the window it protects is about to exist and a queue that drained instantly would make the
 * undo unimplementable rather than merely absent.
 */
fun drainable(entries: List<QueuedWrite>, now: Instant, undoWindow: Duration = UNDO_WINDOW): List<QueuedWrite> =
    entries.filter { entry ->
        !entry.givenUp &&
            !entry.queuedAt.plus(undoWindow).isAfter(now) &&
            !entry.nextAttemptAt.isAfter(now)
    }

/** FR-807's ten seconds, named here so the queue and the offer cannot disagree about it. */
val UNDO_WINDOW: Duration = Duration.ofSeconds(10)

/**
 * Whether something that just happened is grounds for draining ahead of the schedule.
 *
 * The distinction is between a trigger that says *there is work* and one that says *the reason
 * the work stopped may have gone away*. Only the second may bypass a backoff, and only for
 * entries whose last failure was transport-class: a 429 is not cured by learning that the
 * socket works, and retrying it early is asking a server that has said it is overloaded to say
 * so again.
 */
fun shouldDrainNow(
    entries: List<QueuedWrite>,
    trigger: DrainTrigger,
    lastImmediateDrain: Instant?,
    now: Instant,
    rateLimit: Duration = RetryPolicy.IMMEDIATE_DRAIN_RATE_LIMIT,
): Boolean {
    if (trigger == DrainTrigger.USER_ASKED) return true
    val waiting = entries.filter { !it.givenUp }
    if (waiting.isEmpty()) return false
    if (trigger != DrainTrigger.CONNECTIVITY_RESTORED && trigger != DrainTrigger.REQUEST_SUCCEEDED) {
        return false
    }
    // An entry that has never failed is already scheduled and needs no bypass; bypassing for it
    // would mean every connectivity blip replacing a request that was about to run anyway.
    if (waiting.none { it.attempts > 0 && it.failureClass == FailureClass.TRANSPORT }) return false
    return lastImmediateDrain == null || !now.isBefore(lastImmediateDrain.plus(rateLimit))
}

/** The entry as it stands after a failure worth retrying. */
fun afterFailure(entry: QueuedWrite, failure: Throwable, now: Instant): QueuedWrite {
    val attempts = entry.attempts + 1
    return entry.copy(
        attempts = attempts,
        failureClass = classify(failure),
        nextAttemptAt = now.plus(RetryPolicy.backoffDelay(attempts)),
        givenUp = attempts >= RetryPolicy.GIVE_UP_AFTER,
    )
}

internal fun classify(failure: Throwable): FailureClass = when {
    failure is GoogleUnreachable -> FailureClass.TRANSPORT
    failure is GoogleRejected && failure.status == 401 -> FailureClass.SIGN_IN
    failure is GoogleRejected && (failure.status == 429 || failure.status >= 500) -> FailureClass.SERVER
    failure is GoogleRejected -> FailureClass.PERMANENT
    failure is GoogleFailure -> FailureClass.PERMANENT
    else -> FailureClass.TRANSPORT
}

/**
 * Drains one entry.
 *
 * **FR-803 runs again here, per entry, immediately before anything is written**, and it is the
 * reason this function is not simply "write the items". Without it, two captures of one message
 * made offline become two items, which is AC-07 failing inside AC-10 — and on Android that
 * exact check went untested for a slice because it lived inside a `CoroutineWorker` no JVM test
 * could construct. It is a free function taking its two APIs for precisely that reason.
 *
 * **A partially written chain resumes rather than restarts** (SRS 1.24). The per-item marker is
 * on the record, so an entry whose first insert succeeded writes only what is left — otherwise
 * the retry duplicates the item it already created, in the one place FR-803 cannot see it.
 */
suspend fun drainEntry(
    entry: QueuedWrite,
    calendar: CalendarApi,
    tasks: TasksApi,
    now: Instant,
    onProgress: (QueuedWrite) -> Unit = {},
): DrainOutcome {
    val remaining = entry.remaining
    if (remaining.isEmpty()) return DrainOutcome.Retired(written = 0)

    // Only where nothing has been written yet. A chain that is half done is *known* to be in
    // the account, and asking would answer yes and retire an entry with items still owed.
    if (remaining.size == entry.items.size) {
        val existing = runCatching { alreadySaved(entry, calendar, tasks) }
            .getOrElse { return deferOrGiveUp(entry, it, now) }
        if (existing) return DrainOutcome.Retired(written = 0, alreadySaved = true)
    }

    var current = entry
    var written = 0
    remaining.forEach { queued ->
        val id = runCatching { write(queued, current, calendar, tasks) }
            .getOrElse { return deferOrGiveUp(current, it, now) }
        written++
        current = current.copy(
            items = current.items.map { if (it.item.id == queued.item.id) it.copy(writtenId = id) else it },
        )
        // Recorded after every single write, not at the end. A process killed between two
        // inserts of a chain must resume, and a marker written only on success of the whole
        // chain is a marker that is never written for the case it exists to cover.
        onProgress(current)
    }
    return DrainOutcome.Retired(written)
}

private fun deferOrGiveUp(entry: QueuedWrite, failure: Throwable, now: Instant): DrainOutcome {
    if (!isWorthRetrying(failure)) {
        // A 400, or a 403 for a scope not granted. Retrying repeats it for ever, so the entry
        // is marked given up and stays visible with a manual retry, rather than being deleted.
        return DrainOutcome.GaveUp(entry.copy(givenUp = true, failureClass = classify(failure)))
    }
    val next = afterFailure(entry, failure, now)
    return if (next.givenUp) DrainOutcome.GaveUp(next) else DrainOutcome.Deferred(next)
}

private suspend fun alreadySaved(entry: QueuedWrite, calendar: CalendarApi, tasks: TasksApi): Boolean {
    val hash = entry.metadata.sourceHash
    if (calendar.findEventBySourceHash(entry.calendarId, hash).found) return true
    return tasks.findTaskBySourceHash(entry.taskListId, hash, null).found
}

private suspend fun write(
    queued: QueuedItem,
    entry: QueuedWrite,
    calendar: CalendarApi,
    tasks: TasksApi,
): String {
    val item = queued.item
    return when (item.type) {
        ItemType.EVENT -> calendar.insertEvent(
            entry.calendarId,
            EventWrite(
                summary = item.title,
                description = bodyWithNote(item.notes, entry.body),
                location = item.location,
                start = requireNotNull(item.start) { "a queued event has no start" },
                end = requireNotNull(item.end) { "a queued event has no end" },
                allDay = item.allDay,
                timeZone = entry.timeZone,
                reminderMinutes = item.reminderMinutes,
                metadata = entry.metadata,
            ),
        )
        ItemType.TASK -> tasks.insertTask(
            entry.taskListId,
            TaskWrite(
                title = item.title,
                notes = bodyWithNote(item.notes, entry.body),
                due = item.dueDate,
                metadata = entry.metadata,
            ),
        )
    }
}
