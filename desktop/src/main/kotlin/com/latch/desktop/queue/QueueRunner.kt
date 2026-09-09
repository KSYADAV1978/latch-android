package com.latch.desktop.queue

import com.latch.google.CalendarApi
import com.latch.google.RetryPolicy
import com.latch.google.TasksApi
import kotlinx.coroutines.runBlocking
import java.net.NetworkInterface
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Whether this machine looks as though it has a network.
 *
 * **A proxy, and a deliberately weak one.** Android is told by the OS when connectivity is
 * restored; there is no such callback reachable from a JVM here, so this asks whether any
 * non-loopback interface is up. That answers "the Wi-Fi came back", which is the case FR-806's
 * immediate drain exists for, and it is wrong about a captive portal or a router with no route
 * to the internet — both of which look connected and are not.
 *
 * Being wrong costs one early drain attempt that fails and backs off again, which is why a
 * weak proxy is acceptable here and would not be if it gated anything the user could see.
 */
internal fun looksConnected(interfaces: List<NetworkInterface>): Boolean = interfaces.any {
    runCatching { it.isUp && !it.isLoopback && !it.isVirtual }.getOrDefault(false)
}

internal fun readInterfaces(): List<NetworkInterface> =
    runCatching { NetworkInterface.getNetworkInterfaces().toList() }.getOrDefault(emptyList())

/**
 * FR-806's drain, on a thread of this application's own.
 *
 * **There is no WorkManager here, and the difference is worth stating.** On Android the OS
 * keeps the queue alive across process death and reboot and runs it when the constraints are
 * met. This client drains only while it is running: a capture queued and then quit on waits
 * for the next launch. NFR-302 names network failure, app termination and device restart, and
 * the capture survives all three — it is *held* rather than lost — but it is not written until
 * Latch is running again, which is a weaker guarantee than the phone's and is recorded rather
 * than glossed. FR-301's launch-at-sign-in is what closes most of it.
 */
class QueueRunner(
    private val queue: WriteQueue,
    private val calendar: () -> CalendarApi?,
    private val tasks: () -> TasksApi?,
    private val onChanged: () -> Unit = {},
    private val clock: () -> Instant = Instant::now,
    private val tick: Duration = Duration.ofSeconds(15),
) : AutoCloseable {

    /**
     * One drain at a time (SRS 1.195).
     *
     * **Without it two drains wrote the same capture twice into a real calendar**, and FR-803
     * could not have stopped them: the check is a read followed by a write with nothing holding
     * the pair together, so both drains asked *has this message been saved*, both were correctly
     * told no, and both then inserted. A time-of-check-to-time-of-use race, not a broken query —
     * which is worth saying plainly, because SRS 1.37's duplicate hunt *was* a broken query and
     * a reader who assumed the same twice would look in the wrong place.
     *
     * **A lock rather than a skip.** A nudge arriving while a drain runs must wait and then
     * re-read: the running drain has already taken its snapshot, so dropping the nudge would
     * lose exactly the work its caller asked for. Waiting costs nothing — the second drain finds
     * the entry retired and returns.
     *
     * Android needs none of this: its drain is WorkManager unique work and is serialised by
     * construction. The exposure is the price of this client owning its own timer.
     */
    private val drainLock = ReentrantLock()

    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    private var lastImmediateDrain: Instant? = null
    private var wasConnected: Boolean = true

    fun start() {
        if (!running.compareAndSet(false, true)) return
        thread = Thread({
            drain(DrainTrigger.PROCESS_START)
            while (running.get()) {
                try {
                    Thread.sleep(tick.toMillis())
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@Thread
                }
                if (!running.get()) return@Thread

                val connected = looksConnected(readInterfaces())
                val restored = connected && !wasConnected
                wasConnected = connected

                drain(if (restored) DrainTrigger.CONNECTIVITY_RESTORED else DrainTrigger.SCHEDULED)
            }
        }, "latch-queue").apply { isDaemon = true }.also { it.start() }
    }

    /** Called from the save path and from the tray. */
    fun nudge(trigger: DrainTrigger) {
        Thread({ drain(trigger) }, "latch-queue-nudge").apply { isDaemon = true }.start()
    }

    /**
     * Internal rather than private so a JVM test can enter it from two threads at once and
     * assert that only one insert reaches the account — the race is the behaviour under test
     * and `nudge` starts a thread it does not hand back.
     */
    internal fun drain(trigger: DrainTrigger) = drainLock.withLock { drainOnce(trigger) }

    private fun drainOnce(trigger: DrainTrigger) {
        val entries = runCatching { queue.entries() }.getOrDefault(emptyList())
        if (entries.isEmpty()) return

        val now = clock()
        // SRS 1.195: `SIGNED_IN` belongs here and was missing, so `shouldDrainNow` was never
        // consulted for it and the trigger drained unconditionally. The behaviour was right by
        // accident, and the test covering it exercised a function this path never reached with
        // that value — reachability deciding coverage, on the author.
        val bypassing = trigger == DrainTrigger.CONNECTIVITY_RESTORED ||
            trigger == DrainTrigger.REQUEST_SUCCEEDED ||
            trigger == DrainTrigger.USER_ASKED ||
            trigger == DrainTrigger.SIGNED_IN

        if (bypassing) {
            if (!shouldDrainNow(entries, trigger, lastImmediateDrain, now)) return
            lastImmediateDrain = now
            if (trigger == DrainTrigger.USER_ASKED) queue.reviveAll(now)
        }

        val calendarApi = calendar() ?: return
        val tasksApi = tasks() ?: return

        // Re-read, because `reviveAll` may have moved the due times.
        val due = drainable(runCatching { queue.entries() }.getOrDefault(entries), clock())
        if (due.isEmpty()) return

        var changed = false
        due.forEach { entry ->
            if (!running.get() && trigger == DrainTrigger.SCHEDULED) return
            val outcome = runCatching {
                runBlocking {
                    drainEntry(entry, calendarApi, tasksApi, clock()) { progress ->
                        // SRS 1.24: the marker is persisted after each insert, so a process
                        // killed between two items of a chain resumes rather than restarts.
                        queue.replace(progress)
                    }
                }
            }.getOrElse { DrainOutcome.Deferred(afterFailure(entry, it, clock())) }

            changed = true
            when (outcome) {
                is DrainOutcome.Retired -> queue.remove(entry.id)
                is DrainOutcome.Deferred -> queue.replace(outcome.entry)
                is DrainOutcome.GaveUp -> queue.replace(outcome.entry)
            }
        }
        if (changed) onChanged()
    }

    override fun close() {
        running.set(false)
        thread?.interrupt()
        thread = null
    }

    companion object {
        /** So a caller can say how long a given-up entry waited without importing the policy. */
        val GIVE_UP_AFTER: Int = RetryPolicy.GIVE_UP_AFTER
    }
}
