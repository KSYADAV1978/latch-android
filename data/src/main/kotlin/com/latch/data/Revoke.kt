package com.latch.data

import android.content.Context
import com.latch.core.model.LatchSettings
import com.latch.google.ALLOWED_HOSTS
import com.latch.google.alreadyGone
import com.latch.google.requireGoogleEndpoint
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

/**
 * NFR-205: "a single action to revoke access and delete all local data."
 *
 * Two halves, and the order between them is the decision.
 *
 * **The revoke goes first, because it needs a token.** Deleting local data first would leave
 * nothing to authenticate with and the grant standing in the user's Google account for ever —
 * which is the half of this requirement they cannot fix themselves afterwards without going to
 * `myaccount.google.com` and knowing to.
 *
 * **The local deletion happens whether or not the revoke succeeded.** The user asked for their
 * data gone; a network failure must not leave it on the phone. The outcome says which half
 * worked, so a failed revoke is reported rather than swallowed — NFR-303's instinct applied to
 * something that is not a write — and the user can be told to remove the grant in their Google
 * account by hand, which is the recourse.
 */
data class RevokeOutcome(val accessRevoked: Boolean, val localDataDeleted: Boolean)

/**
 * `POST https://oauth2.googleapis.com/revoke?token=…`
 *
 * That host has been in [ALLOWED_HOSTS] since the guard was written, with a comment saying it
 * was there "for NFR-205's revoke, which is not built" — this is the call it was listed for, so
 * the allowlist does not move.
 *
 * Google answers 200 for a token it revoked and **400 for one it does not recognise**, and both
 * mean the same thing to this requirement: no grant of ours is left standing on that token. A
 * 400 is therefore success, which is the same reasoning `alreadyGone` applies to a delete under
 * FR-807 — the caller asked for something not to exist, and it does not.
 */
suspend fun revokeGrant(accessToken: String): Boolean {
    val url = requireGoogleEndpoint("$REVOKE_ENDPOINT?token=$accessToken")
    return runInterruptible(Dispatchers.IO) {
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            instanceFollowRedirects = false
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            doOutput = true
            setFixedLengthStreamingMode(0)
        }
        try {
            connection.outputStream.use { }
            val status = connection.responseCode
            status in 200..299 || status == HttpURLConnection.HTTP_BAD_REQUEST
        } catch (failure: IOException) {
            false
        } finally {
            connection.disconnect()
        }
    }
}

private const val REVOKE_ENDPOINT = "https://oauth2.googleapis.com/revoke"
private const val TIMEOUT_MS = 10_000

/**
 * NFR-205's second half: **all** local data.
 *
 * Enumerated rather than swept, and every store this app has is named here. A directory wipe
 * would be shorter and would silently start missing things the day someone adds a store without
 * remembering — which for this requirement means a user asking for their data to be deleted and
 * some of it staying. Adding a store is therefore a change to this function, visible in a diff.
 *
 * **And that is exactly what did not happen when the tenth store arrived** (SRS 1.121). FR-1212's
 * card queue was added in its own slice and never named here, so a card captured offline — a third
 * party's name, telephone number and email address — survived "delete everything" and would have
 * been written to Google Contacts on the next reconnection, after the account had been
 * disconnected. An enumeration only catches a missing store if somebody looks, so the
 * enumeration is now [deleteEveryStore] — which takes stores and no `Context`, and is therefore
 * reachable from a JVM test that fails rather than from a reviewer who has to notice.
 *
 * The Keystore keys go too. Without that, a later install would generate new records under keys
 * that had never been rotated; with it, anything that somehow survived is unreadable, which is
 * the same reasoning that makes an unreadable record "absent" everywhere else in this module.
 */
suspend fun deleteAllLocalData(
    context: Context,
    defaultsStore: AccountDefaultsStore,
    inbox: CaptureInbox,
    index: LocalItemIndex,
    undoOffers: UndoOfferStore,
    recipes: RecipeStore,
    settings: SettingsStore,
    secrets: SecretStore,
    queue: WriteQueue,
    /** FR-1212's held cards. See the note above for how long this was missing and why. */
    cards: CardQueue,
): Boolean = runCatching {
    deleteEveryStore(
        defaultsStore = defaultsStore,
        inbox = inbox,
        index = index,
        undoOffers = undoOffers,
        recipes = recipes,
        settings = settings,
        secrets = secrets,
        queue = queue,
        cards = cards,
    )
    // The database file itself, after its tables — deleting the file first would leave the
    // helpers above opening a fresh empty one behind them. It is the one step here that needs
    // a `Context`, which is the whole reason it is not in `deleteEveryStore`.
    context.deleteDatabase(LatchDatabase.DATABASE_NAME)
    true
}.getOrDefault(false)

/**
 * NFR-205's enumeration, with nothing platform-bound in it.
 *
 * **Split out so a JVM test can call it** (SRS 1.121). `deleteAllLocalData` takes a `Context`,
 * which is a throwing stub under unit tests, so for as long as the enumeration lived inside it
 * the requirement had no test at all in either source set — and the day a tenth store was added
 * without being named here, nothing said so. This is the standing convention in this project
 * applied to the one function whose whole job is to be complete.
 *
 * Every store the app has, and it is meant to be read as a checklist.
 */
internal suspend fun deleteEveryStore(
    defaultsStore: AccountDefaultsStore,
    inbox: CaptureInbox,
    index: LocalItemIndex,
    undoOffers: UndoOfferStore,
    recipes: RecipeStore,
    settings: SettingsStore,
    secrets: SecretStore,
    queue: WriteQueue,
    cards: CardQueue,
) {
    // The queues first: they are the stores holding captures that exist nowhere else, so if
    // anything here is going to fail it should fail before the rest is gone.
    queue.pending().forEach { queue.drop(it.id) }
    cards.pending().forEach { cards.drop(it.id) }
    defaultsStore.allAccounts().forEach { defaultsStore.remove(it.accountId) }
    inbox.deleteAll()
    index.clear()
    undoOffers.clear()
    recipes.deleteAll()
    settings.write(LatchSettings())
    secrets.clear()
}
