package com.latch.android.setup

import android.app.PendingIntent
import android.content.Intent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Carries the consent screen across the gap between a suspending sign-in and an Activity
 * result.
 *
 * `AuthorizationClient` answers a first-time request with a `PendingIntent` that only an
 * Activity can launch, and only through `ActivityResultLauncher`. But `SetupCoordinator`
 * deliberately outlives `MainActivity` — a rotation partway through setup must not restart it
 * (FR-105) — so the thing that waits for the answer cannot be the thing that receives it.
 *
 * This holds the waiting side, and lives as long as the process. `MainActivity` attaches
 * itself as the receiving side while it is started and detaches when it is not. A rotation
 * therefore swaps the launcher underneath a wait that never notices.
 */
class AuthResolutionBridge {

    private val lock = Any()
    private var launcher: ActivityResultLauncher<IntentSenderRequest>? = null
    private var waiter: CompletableDeferred<Intent?>? = null
    private var queued: PendingIntent? = null

    /**
     * Whether the consent screen has already gone out for the current wait. Without this,
     * an Activity recreated between the launch and the result re-launches it, and the user
     * is asked to consent twice.
     */
    private var launched = false

    /** Called from `onStart`. Any consent that was waiting for an Activity goes out now. */
    fun attach(target: ActivityResultLauncher<IntentSenderRequest>) {
        synchronized(lock) { launcher = target }
        launchIfPossible()
    }

    /** Called from `onStop`. Guarded so a slow teardown cannot unseat a newer Activity. */
    fun detach(target: ActivityResultLauncher<IntentSenderRequest>) {
        synchronized(lock) { if (launcher === target) launcher = null }
    }

    /**
     * Shows the consent screen and suspends until the user answers. Null means they
     * dismissed it.
     *
     * Single-slot on purpose. A second concurrent request would orphan the first, and an
     * orphaned wait is the worst failure this app has: step 1 renders no Retry for a
     * `Loading` sign-in and offers no Back, so the screen would be dead but for quitting.
     * The reducer already refuses to ask twice; this refuses to be asked twice.
     */
    suspend fun resolve(pendingIntent: PendingIntent): Intent? {
        val deferred = CompletableDeferred<Intent?>()
        synchronized(lock) {
            check(waiter == null) { "A consent resolution is already in flight" }
            waiter = deferred
            queued = pendingIntent
            launched = false
        }

        // ActivityResultLauncher.launch is main-thread only, and setup's effects run on the
        // application scope, which is Dispatchers.Default.
        withContext(Dispatchers.Main) { launchIfPossible() }

        return try {
            deferred.await()
        } finally {
            synchronized(lock) {
                waiter = null
                queued = null
                launched = false
            }
        }
    }

    /**
     * The Activity result. Tolerates having no one to give it to: if the process was killed
     * while the consent screen was up, the coordinator that asked no longer exists. That is
     * FR-105 working — the new process starts setup afresh, having created nothing — and the
     * grant now recorded in the user's account means the next authorize returns without UI.
     */
    fun deliver(data: Intent?) {
        synchronized(lock) { waiter }?.complete(data)
    }

    /** Main thread only. Does nothing until there is both something to show and somewhere to show it. */
    private fun launchIfPossible() {
        val target: ActivityResultLauncher<IntentSenderRequest>
        val intent: PendingIntent
        synchronized(lock) {
            if (launched) return
            target = launcher ?: return
            intent = queued ?: return
            launched = true
        }
        target.launch(IntentSenderRequest.Builder(intent.intentSender).build())
    }
}
