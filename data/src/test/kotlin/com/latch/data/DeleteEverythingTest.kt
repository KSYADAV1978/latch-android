package com.latch.data

import com.latch.core.model.CardDraft
import com.latch.core.model.CaptureLayer
import com.latch.core.model.InboxCapture
import com.latch.core.model.InboxReason
import com.latch.core.model.InboxStatus
import com.latch.core.model.Item
import com.latch.core.model.ItemType
import com.latch.core.model.LatchSettings
import com.latch.core.model.RoutingMode
import com.latch.core.model.Recipe
import com.latch.google.FailureClass
import com.latch.wire.RemoteMetadata
import java.time.Instant
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * NFR-205: **all** local data, and this is the first test the requirement has ever had.
 *
 * **Why it did not have one, which is the finding rather than the fix** (SRS 1.121). The
 * enumeration lived inside `deleteAllLocalData`, which takes a `Context` — a throwing stub under
 * JVM unit tests, and unreachable from a JVM test at any price. So NFR-205 was covered in neither
 * source set, and when FR-1212's card queue became the tenth store nothing said it had been left
 * out: a card captured offline, holding a third party's name, telephone number and email address,
 * survived "delete everything" and would have been written to Google Contacts on the next
 * reconnection — after the account had been disconnected.
 *
 * That is the shape this project has already paid for twice: the drain's FR-803 check private to a
 * `CoroutineWorker`, and `decodeBitmap` behind a platform stub. `deleteEveryStore` is the same
 * remedy, so the checklist is a thing that fails rather than a thing somebody must notice.
 */
class DeleteEverythingTest {

    @Test
    fun `every store is emptied, the held card included`() = runBlocking {
        val stores = Stores()
        stores.deleteEverything()

        assertTrue(stores.queue.entries.isEmpty(), "FR-806's write queue survived")
        // The row this test exists for.
        assertTrue(stores.cards.entries.isEmpty(), "FR-1212's held card survived")
        assertTrue(stores.defaults.accounts.isEmpty(), "the account defaults survived")
        assertTrue(stores.inbox.rows.isEmpty(), "FR-701's Inbox survived")
        assertTrue(stores.index.cleared, "FR-803's index survived")
        assertTrue(stores.undoOffers.cleared, "FR-807's stored offer survived")
        assertTrue(stores.recipes.rows.isEmpty(), "FR-603's user recipes survived")
        assertTrue(stores.secrets.cleared, "NFR-203's secret store survived")
        assertEquals(LatchSettings(), stores.settings.value, "FR-1001's settings were not reset")
    }

    /**
     * The queues go first, and it is worth pinning rather than reading off the source: they are
     * the two stores holding captures that exist nowhere else, so if anything in this sequence is
     * going to throw it should throw before the rest is gone.
     */
    @Test
    fun `the queues are emptied before anything else`() = runBlocking {
        val stores = Stores()
        stores.deleteEverything()

        assertEquals(
            listOf("write-queue", "card-queue"),
            stores.order.take(2),
            "a store that can throw was allowed to run before the captures were secured",
        )
    }

    /**
     * A held card whose record this build cannot read is still the user's to delete.
     *
     * The queue stores deliberately **keep** an unreadable record rather than dropping it —
     * dropping one loses a capture, which is the one thing NFR-302 forbids — so the count they
     * report is not always the count of rows they can decode. What NFR-205 asks is different and
     * stronger: after this call there is nothing left, readable or not.
     */
    @Test
    fun `a card the store cannot read is dropped too`() = runBlocking {
        val stores = Stores()
        stores.cards.entries += heldCard("unreadable-to-a-later-build")
        stores.deleteEverything()

        assertTrue(stores.cards.entries.isEmpty())
    }

    // ---- fakes ------------------------------------------------------------------------------
    //
    // Deliberately dumb: they record what was asked of them and nothing more. A fake that
    // answered by fixture rather than by state is what let `findEventBySourceHash` ignore its
    // argument for as long as the real one was broken, and this file is a checklist test.

    private class Stores {
        val order = mutableListOf<String>()
        val queue = FakeWriteQueue(order)
        val cards = FakeCardQueue(order)
        val defaults = FakeDefaults()
        val inbox = FakeInbox()
        val index = FakeIndex()
        val undoOffers = FakeUndoOffers()
        val recipes = FakeRecipes()
        val settings = FakeSettings()
        val secrets = FakeSecrets()

        suspend fun deleteEverything() = deleteEveryStore(
            defaultsStore = defaults,
            inbox = inbox,
            index = index,
            undoOffers = undoOffers,
            recipes = recipes,
            settings = settings,
            secrets = secrets,
            queue = queue,
            cards = cards,
        )
    }

    private class FakeWriteQueue(private val order: MutableList<String>) : WriteQueue {
        val entries = mutableListOf(queuedWrite("q1"), queuedWrite("q2"))

        override suspend fun pending(): List<QueuedWrite> {
            order += "write-queue"
            return entries.toList()
        }

        override suspend fun drop(queueId: String): Boolean =
            entries.removeIf { it.id == queueId }

        override suspend fun enqueue(write: PendingWrite, operation: WriteOperation): String =
            throw UnsupportedOperationException()

        override suspend fun status(): QueueStatus = throw UnsupportedOperationException()
        override suspend fun markWritten(queueId: String, remoteId: String) =
            throw UnsupportedOperationException()

        override suspend fun markItemWritten(queueId: String, itemId: String, remoteId: String) =
            throw UnsupportedOperationException()

        override suspend fun markFailed(
            queueId: String,
            error: String,
            permanent: Boolean,
            needsSignIn: Boolean,
            failureClass: FailureClass,
        ) = throw UnsupportedOperationException()

        override suspend fun reviveGivenUp(): Int = throw UnsupportedOperationException()
    }

    private class FakeCardQueue(private val order: MutableList<String>) : CardQueue {
        val entries = mutableListOf(heldCard("c1"))

        override suspend fun pending(): List<HeldCard> {
            order += "card-queue"
            return entries.toList()
        }

        override suspend fun drop(id: String): Boolean = entries.removeIf { it.id == id }
        override suspend fun enqueue(entry: HeldCard): String = throw UnsupportedOperationException()
        override suspend fun retire(id: String): Boolean = throw UnsupportedOperationException()
        override suspend fun markAttempted(id: String): Boolean = throw UnsupportedOperationException()
    }

    private class FakeDefaults : AccountDefaultsStore {
        val accounts = mutableListOf(
            AccountDefaults(
                accountId = "a1",
                email = "someone@example.com",
                routingMode = RoutingMode.LATCH_CALENDAR,
                destinationCalendarId = "latch",
                destinationCalendarName = "Latch",
                destinationCalendarColour = "#d50000",
                taskListId = "@default",
            )
        )

        override suspend fun allAccounts(): List<AccountDefaults> = accounts.toList()
        override suspend fun remove(accountId: String) { accounts.removeIf { it.accountId == accountId } }
        override suspend fun defaultsFor(accountId: String): AccountDefaults? =
            accounts.firstOrNull { it.accountId == accountId }

        override suspend fun save(defaults: AccountDefaults) = throw UnsupportedOperationException()
    }

    private class FakeInbox : CaptureInbox {
        val rows = mutableListOf(
            InboxCapture(
                id = "i1",
                rawText = "Ask about the uniform order",
                layer = CaptureLayer.SHARE_SHEET,
                capturedAt = Instant.parse("2026-09-05T09:00:00Z"),
                capturedLocal = LocalDateTime.parse("2026-09-05T14:30"),
                zone = "Asia/Kolkata",
                confidence = 0.4,
                reason = InboxReason.UNDATED,
            )
        )

        override suspend fun deleteAll() { rows.clear() }
        override suspend fun all(): List<InboxCapture> = rows.toList()
        override suspend fun add(capture: InboxCapture) = throw UnsupportedOperationException()
        override suspend fun due(now: Instant): List<InboxCapture> = throw UnsupportedOperationException()
        override suspend fun pendingCount(now: Instant): Int = throw UnsupportedOperationException()
        override suspend fun status(now: Instant): InboxStatus = throw UnsupportedOperationException()
        override suspend fun find(id: String): InboxCapture? = throw UnsupportedOperationException()
        override suspend fun update(capture: InboxCapture) = throw UnsupportedOperationException()
        override suspend fun discard(id: String) = throw UnsupportedOperationException()
    }

    private class FakeIndex : LocalItemIndex {
        var cleared = false
        override suspend fun clear() { cleared = true }
        override suspend fun remember(item: WrittenItem) = throw UnsupportedOperationException()
        override suspend fun bySourceHash(sourceHash: String): WrittenItem? = throw UnsupportedOperationException()
        override suspend fun byItemKey(itemKey: String): List<WrittenItem> = throw UnsupportedOperationException()
        override suspend fun forget(containerId: String, remoteId: String) = throw UnsupportedOperationException()
    }

    private class FakeUndoOffers : UndoOfferStore {
        var cleared = false
        override suspend fun clear() { cleared = true }
        override suspend fun remember(offer: StoredUndoOffer) = throw UnsupportedOperationException()
        override suspend fun open(now: Instant): StoredUndoOffer? = throw UnsupportedOperationException()
        override suspend fun forget(chainId: String) = throw UnsupportedOperationException()
    }

    private class FakeRecipes : RecipeStore {
        val rows = mutableListOf<Recipe>()
        var seeded = true

        override suspend fun deleteAll() { rows.clear(); seeded = false }
        override suspend fun all(): List<Recipe> = rows.toList()
        override suspend fun save(recipe: Recipe) = throw UnsupportedOperationException()
        override suspend fun delete(recipeId: String) = throw UnsupportedOperationException()
    }

    private class FakeSettings : SettingsStore {
        var value = LatchSettings(confidenceThreshold = 0.99)
        override suspend fun read(): LatchSettings = value
        override suspend fun write(settings: LatchSettings) { value = settings }
    }

    private class FakeSecrets : SecretStore {
        var cleared = false
        override suspend fun clear() { cleared = true }
        override suspend fun put(key: String, value: String) = throw UnsupportedOperationException()
        override suspend fun get(key: String): String? = throw UnsupportedOperationException()
    }

    private companion object {
        fun queuedWrite(id: String) = QueuedWrite(
            id = id,
            write = PendingWrite(
                // `PendingWrite` refuses an empty chain — "a queued write with no items would
                // drain to nothing" — so this is the smallest entry the type will accept.
                items = listOf(
                    Item(
                        id = "item",
                        captureId = "capture",
                        type = ItemType.TASK,
                        title = "Ask about the uniform order",
                    )
                ),
                metadata = RemoteMetadata(
                    sourceHash = "hash",
                    itemKey = "key",
                    chainId = "chain",
                    capturedAt = Instant.parse("2026-09-05T09:00:00Z"),
                ),
                body = "",
                timeZone = "Asia/Kolkata",
            ),
            operation = WriteOperation.CREATE,
            attempts = 0,
            lastError = null,
            queuedAt = Instant.parse("2026-09-05T09:00:00Z"),
        )

        fun heldCard(id: String) = HeldCard(
            id = id,
            payload = "BEGIN:VCARD\r\nEND:VCARD",
            draft = CardDraft(displayName = "Anita Sharma"),
            layer = CaptureLayer.CAMERA.name,
            queuedAt = Instant.parse("2026-09-05T09:00:00Z"),
        )
    }
}
