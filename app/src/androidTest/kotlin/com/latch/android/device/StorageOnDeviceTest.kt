package com.latch.android.device

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.latch.android.capture.drainEntry
import com.latch.core.model.CaptureLayer
import com.latch.core.model.Direction
import com.latch.core.model.Item
import com.latch.core.model.ItemType
import com.latch.core.model.OffsetUnit
import com.latch.core.model.Recipe
import com.latch.core.model.RecipeStep
import com.latch.google.CalendarApi
import com.latch.data.CreatedItem
import com.latch.google.DuplicateSearch
import com.latch.data.EncryptedSecretStore
import com.latch.data.EncryptedSettingsStore
import com.latch.data.EncryptedWriteQueueStore
import com.latch.google.EventWrite
import com.latch.core.model.InboxCapture
import com.latch.core.model.InboxReason
import com.latch.google.ItemDates
import com.latch.core.model.LatchSettings
import com.latch.data.PendingWrite
import com.latch.google.RescheduleSearch
import com.latch.data.SqliteCaptureInbox
import com.latch.data.SqliteItemIndex
import com.latch.data.SqliteRecipeStore
import com.latch.data.SqliteUndoOfferStore
import com.latch.data.StoredUndoOffer
import com.latch.google.TaskList
import com.latch.google.TaskWrite
import com.latch.google.TasksApi
import com.latch.google.WritableCalendar
import com.latch.data.WrittenItem
import com.latch.webhook.maskedEndpoint
import com.latch.wire.RemoteMetadata
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The stores, on a device.
 *
 * **Nothing in this file has ever run.** `SQLiteOpenHelper`, `SharedPreferences` and the Android
 * Keystore are all throwing stubs under JVM unit tests — the same property that hid the
 * `decodeBitmap` defect in `:ocr` for a whole slice — so every record format in `:data` is
 * tested and none of the storage under it is. That is the single largest device-owed gap left by
 * the FR-701 slice, and this closes it.
 *
 * Each test writes, reads back, and asserts the value survived the **encryption** round trip as
 * well as the SQL: a record format test proves the encoding, and only a device proves that
 * `KeystoreCipher` can decrypt what it encrypted.
 *
 * **This suite destroys the app's local data, and must not be run on a device carrying captures
 * anyone cares about.** It writes to the real stores under the real names — there is no test
 * database — so [clean] empties the Inbox, the queue, the recipes, the settings and the secret
 * store both before *and* after. Running it on a phone in real use will lose whatever was in
 * them.
 *
 * **The `@After` half was missing on the first run and it mattered.** `@Before` alone leaves
 * whatever the *last* test created, and three of the tests below enqueue a write. On 2 Sep 2026
 * that left a fake entry — `Row 0`, calendar id `latch-cal` — sitting in a real user's queue,
 * where FR-806's drain would eventually have tried to insert it **into their Google account**.
 * It would have failed with a 404 and created nothing, because no calendar has that id, and it
 * would have been marked permanently given up and shown on their home screen as a capture that
 * could not be saved. A test suite whose residue attempts a write to a live account is a defect
 * in the suite whatever the write does.
 */
@RunWith(AndroidJUnit4::class)
class StorageOnDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val inbox = SqliteCaptureInbox(context)
    private val index = SqliteItemIndex(context)
    private val undoOffers = SqliteUndoOfferStore(context)
    private val recipes = SqliteRecipeStore(context)
    private val settings = EncryptedSettingsStore(context)
    private val secrets = EncryptedSecretStore(context)
    private val queue = EncryptedWriteQueueStore(context)

    /**
     * Before **and** after — see the note on this class.
     *
     * Before, because instrumented tests share one installed app and a suite whose tests
     * depended on order would pass in one arrangement and fail in another. After, because
     * whatever the last test created otherwise stays on the device, and for three of the tests
     * here that is a queue entry the app will try to write to a real Google account.
     */
    @Before
    @After
    fun clean() = runBlocking {
        inbox.deleteAll()
        index.clear()
        undoOffers.clear()
        recipes.deleteAll()
        settings.write(LatchSettings())
        secrets.clear()
        queue.pending().forEach { queue.drop(it.id) }
    }

    // ----- FR-701: the Capture Inbox -----

    @Test
    fun an_inbox_row_survives_the_database_and_the_cipher() = runBlocking {
        val capture = InboxCapture(
            id = "capture-1",
            rawText = "Sharma Ji\nPTM next week sometime\n\nOk noted.",
            layer = CaptureLayer.SHARE_SHEET,
            appId = "com.whatsapp",
            ocrUsed = true,
            capturedAt = Instant.parse("2026-09-01T09:15:00Z"),
            capturedLocal = LocalDateTime.parse("2026-09-01T14:45:00"),
            zone = "Asia/Kolkata",
            confidence = 0.5,
            reason = InboxReason.LOW_CONFIDENCE,
        )
        inbox.add(capture)

        assertEquals(capture, inbox.find("capture-1"))
        assertEquals(listOf(capture), inbox.all())
        // The pair the whole Inbox design rests on: without these it re-parses "kal" against
        // today and the date walks forward every time the list is opened.
        assertEquals("Asia/Kolkata", inbox.find("capture-1")?.zone)
        assertEquals(LocalDateTime.parse("2026-09-01T14:45:00"), inbox.find("capture-1")?.capturedLocal)
    }

    @Test
    fun a_snoozed_row_leaves_the_count_and_comes_back(): Unit = runBlocking {
        val now = Instant.parse("2026-09-02T09:00:00Z")
        inbox.add(row("a", now))
        inbox.add(row("b", now).copy(snoozedUntil = Instant.parse("2026-09-09T09:00:00Z")))

        // FR-704's count excludes a snoozed row: it is not pending on the user.
        assertEquals(1, inbox.pendingCount(now))
        assertEquals(2, inbox.all().size)
        assertEquals(2, inbox.pendingCount(Instant.parse("2026-09-10T09:00:00Z")))
    }

    @Test
    fun the_inbox_reads_oldest_first_so_an_aged_capture_surfaces(): Unit = runBlocking {
        inbox.add(row("new", Instant.parse("2026-09-02T09:00:00Z")))
        inbox.add(row("old", Instant.parse("2026-08-01T09:00:00Z")))

        assertEquals(listOf("old", "new"), inbox.all().map { it.id })
    }

    @Test
    fun a_discarded_row_is_gone(): Unit = runBlocking {
        inbox.add(row("a", Instant.now()))
        inbox.discard("a")
        assertNull(inbox.find("a"))
    }

    // ----- FR-803's local index -----

    @Test
    fun the_index_finds_by_hash_and_by_key_and_forgets(): Unit = runBlocking {
        val written = WrittenItem(
            remoteId = "event-1",
            containerId = "latch-cal",
            type = ItemType.EVENT,
            sourceHash = "a".repeat(64),
            itemKey = "b".repeat(64),
            dates = ItemDates.Event(
                start = LocalDateTime.parse("2027-09-08T09:00"),
                end = LocalDateTime.parse("2027-09-08T10:00"),
                timeZone = "Asia/Kolkata",
            ),
            writtenAt = Instant.parse("2026-09-02T09:00:00Z"),
        )
        index.remember(written)

        assertEquals(written, index.bySourceHash("a".repeat(64)))
        assertEquals(listOf(written), index.byItemKey("b".repeat(64)))

        // FR-807: an item the user took back must not block a re-capture.
        index.forget("latch-cal", "event-1")
        assertNull(index.bySourceHash("a".repeat(64)))
    }

    @Test
    fun the_index_returns_the_most_recently_written_first(): Unit = runBlocking {
        // §5.8's tie-break gives an FR-804 offer to the latest position, and the most recently
        // written row is the closest this index gets to knowing which.
        index.remember(indexRow("old", Instant.parse("2026-01-01T00:00:00Z")))
        index.remember(indexRow("new", Instant.parse("2026-09-01T00:00:00Z")))

        assertEquals(listOf("new", "old"), index.byItemKey("b".repeat(64)).map { it.remoteId })
    }

    // ----- FR-807's stored offer -----

    @Test
    fun an_open_offer_comes_back_and_a_lapsed_one_is_swept(): Unit = runBlocking {
        val now = Instant.parse("2026-09-02T09:00:00Z")
        val open = StoredUndoOffer(
            chainId = "chain-open",
            expiresAt = now.plusSeconds(10),
            created = listOf(CreatedItem.Written(ItemType.EVENT, "latch-cal", "event-1")),
        )
        val lapsed = open.copy(chainId = "chain-lapsed", expiresAt = now.minusSeconds(1))
        undoOffers.remember(lapsed)
        undoOffers.remember(open)

        assertEquals(open, undoOffers.open(now))
        // The lapsed one was swept on the way past — there is no scheduled cleanup, because
        // NFR-104 forbids a background service to do it.
        assertNull(undoOffers.open(now.plusSeconds(30)))
    }

    @Test
    fun an_update_offer_keeps_the_prior_dates_an_undo_would_restore(): Unit = runBlocking {
        val prior = ItemDates.Task(LocalDate.parse("2027-09-20"))
        undoOffers.remember(
            StoredUndoOffer(
                chainId = "chain-1",
                expiresAt = Instant.parse("2026-09-02T09:00:10Z"),
                created = listOf(CreatedItem.Updated(ItemType.TASK, "list-1", "task-1", prior)),
            )
        )
        val read = undoOffers.open(Instant.parse("2026-09-02T09:00:00Z"))
        assertNotNull("the stored offer did not come back", read)
        checkNotNull(read)
        // After the patch these exist nowhere else (SRS 1.19), so losing them in storage would
        // mean an update that could not be undone.
        assertEquals(prior, (read.created.single() as CreatedItem.Updated).priorDates)
    }

    // ----- FR-603's recipes -----

    @Test
    fun a_user_recipe_survives_with_its_steps(): Unit = runBlocking {
        val recipe = Recipe(
            id = "user.abc",
            name = "My meeting",
            builtIn = false,
            steps = listOf(
                RecipeStep(3, OffsetUnit.WORKING_DAYS, Direction.BEFORE, ItemType.TASK, "Prep {title}"),
            ),
        )
        recipes.save(recipe)

        assertEquals(listOf(recipe), recipes.all())
        recipes.delete("user.abc")
        assertTrue(recipes.all().isEmpty())
    }

    // ----- FR-1001's settings, and NFR-203's secret -----

    @Test
    fun settings_survive_and_an_absent_record_reads_as_the_defaults(): Unit = runBlocking {
        // The defaults are the behaviour the app shipped with, so an unreadable record is a
        // working app with some preferences forgotten rather than a broken one.
        assertEquals(LatchSettings(), settings.read())

        val changed = LatchSettings(
            workingDays = setOf(DayOfWeek.MONDAY, DayOfWeek.SATURDAY),
            dayFirstDates = false,
            confidenceThreshold = 0.8,
            monitoredPackages = setOf("com.whatsapp"),
            webhookEnabled = true,
        )
        settings.write(changed)
        assertEquals(changed, settings.read())
    }

    @Test
    fun a_secret_survives_and_is_masked_for_display(): Unit = runBlocking {
        val endpoint = "https://hooks.example.invalid/services/T000/XXXXsecretXXXX"
        secrets.put(EncryptedSecretStore.KEY_WEBHOOK_ENDPOINT, endpoint)

        assertEquals(endpoint, secrets.get(EncryptedSecretStore.KEY_WEBHOOK_ENDPOINT))
        // NFR-203: masked once saved. The screen never holds the real value.
        val masked = maskedEndpoint(endpoint)
        assertFalse("the mask disclosed the token", masked.contains("secret"))
        assertTrue(masked.startsWith("https://hooks.example.invalid"))
    }

    // ----- FR-806: the queue, on the real store -----

    @Test
    fun a_chain_is_one_queue_entry_carrying_all_of_its_items(): Unit = runBlocking {
        // SRS §7.1, corrected a third time at v1.23. Held one entry per item, FR-803's
        // re-check at drain would find the first entry's item and conclude the message was
        // saved, removing the second without writing — a chain arriving one item short,
        // silently, losing more the longer it was.
        val id = queue.enqueue(chainWrite(4))

        val pending = queue.pending()
        assertEquals("a chain must be one entry", 1, pending.size)
        assertEquals(id, pending.single().id)
        assertEquals("the entry must carry the whole chain", 4, pending.single().write.items.size)
    }

    @Test
    fun a_drain_that_finds_the_message_already_saved_retires_without_writing(): Unit = runBlocking {
        // The 31 Aug defect, on the real store: an entry whose message was already in the
        // account was drained and written a second time. The count in the account is the only
        // instrument that found it, and this is the JVM-side rule running against the storage
        // that was previously untested.
        queue.enqueue(chainWrite(1))
        val entry = queue.pending().single()

        val calendar = FoundCalendarApi(existingId = "event-already-there")
        val tasks = RefusingTasksApi()
        drainEntry(entry, queue, calendar, tasks)

        assertEquals("nothing may be written when the message is already saved", 0, calendar.inserted)
        assertTrue("the entry must be retired", queue.pending().isEmpty())
    }

    @Test
    fun a_marked_item_survives_so_a_failed_chain_resumes(): Unit = runBlocking {
        // SRS 1.24's cure, on the store that has to hold it: a marker written to memory and not
        // to disk would be lost by exactly the process death it exists to survive.
        val id = queue.enqueue(chainWrite(3))
        queue.markItemWritten(id, "chain#0", "event-1")

        assertEquals(setOf("chain#0"), queue.pending().single().writtenItemIds)
    }

    @Test
    fun a_given_up_entry_stays_and_can_be_revived(): Unit = runBlocking {
        // FR-806: an entry given up on stays, because dropping it would lose the capture, and
        // FR-806's own note recorded that it had no manual retry until Settings existed.
        val id = queue.enqueue(chainWrite(1))
        queue.markFailed(id, "403", permanent = true)

        assertEquals(1, queue.status().givenUp)
        assertEquals(0, queue.status().waiting)

        assertEquals(1, queue.reviveGivenUp())
        assertEquals(0, queue.status().givenUp)
        assertEquals(1, queue.status().waiting)
    }

    // ----- fixtures -----

    private fun row(id: String, at: Instant) = InboxCapture(
        id = id,
        rawText = "Ask about the uniform order",
        layer = CaptureLayer.SHARE_SHEET,
        capturedAt = at,
        capturedLocal = LocalDateTime.parse("2026-09-01T14:45:00"),
        zone = "Asia/Kolkata",
        confidence = 0.0,
        reason = InboxReason.UNDATED,
    )

    private fun indexRow(remoteId: String, at: Instant) = WrittenItem(
        remoteId = remoteId,
        containerId = "latch-cal",
        type = ItemType.EVENT,
        sourceHash = "a".repeat(64),
        itemKey = "b".repeat(64),
        dates = ItemDates.Task(null),
        writtenAt = at,
    )

    private fun chainWrite(items: Int) = PendingWrite(
        items = (0 until items).map { index ->
            Item(
                id = "chain#$index",
                captureId = "capture",
                chainId = "chain",
                type = ItemType.EVENT,
                title = "Row $index",
                start = LocalDateTime.parse("2027-09-0${index + 1}T09:00"),
                end = LocalDateTime.parse("2027-09-0${index + 1}T10:00"),
                calendarId = "latch-cal",
            )
        },
        metadata = RemoteMetadata(
            sourceHash = "c".repeat(64),
            itemKey = "d".repeat(64),
            chainId = "chain",
            capturedAt = Instant.parse("2026-09-02T08:00:00Z"),
        ),
        body = "the capture",
        timeZone = "Asia/Kolkata",
    )
}

/** Answers FR-803 with a hit, so the drain must retire rather than write. */
private class FoundCalendarApi(private val existingId: String) : CalendarApi {
    var inserted = 0

    override suspend fun findEventBySourceHash(calendarId: String, sourceHash: String) =
        DuplicateSearch(existingId)

    override suspend fun insertEvent(calendarId: String, event: EventWrite): String {
        inserted++
        return "should-not-happen"
    }

    override suspend fun listWritableCalendars(): List<WritableCalendar> = emptyList()
    override suspend fun createLatchCalendar(summary: String, description: String) = "unused"
    override suspend fun setColourAndVisibility(calendarId: String, colorId: String, visible: Boolean): String? = null
    override suspend fun makeVisible(calendarId: String) = Unit
    override suspend fun deleteEvent(calendarId: String, eventId: String) = Unit
    override suspend fun findEventByItemKey(calendarId: String, itemKey: String) = RescheduleSearch()
    override suspend fun patchEventDates(calendarId: String, eventId: String, dates: ItemDates.Event) = Unit
}

/** Fails loudly rather than quietly: nothing in these tests should reach the Tasks API. */
private class RefusingTasksApi : TasksApi {
    override suspend fun listTaskLists(): List<TaskList> = emptyList()
    override suspend fun insertTask(taskListId: String, task: TaskWrite): String =
        throw AssertionError("the Tasks API should not have been called")

    override suspend fun findTaskBySourceHash(taskListId: String, sourceHash: String, due: LocalDate?) =
        DuplicateSearch(existingId = null)

    override suspend fun deleteTask(taskListId: String, taskId: String) = Unit
    override suspend fun findTaskByItemKey(taskListId: String, itemKey: String) = RescheduleSearch()
    override suspend fun patchTaskDates(taskListId: String, taskId: String, dates: ItemDates.Task) = Unit
}
