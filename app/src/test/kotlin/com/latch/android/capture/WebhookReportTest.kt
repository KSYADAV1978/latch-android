package com.latch.android.capture

import com.latch.core.model.CaptureLayer
import com.latch.core.model.LatchSettings
import com.latch.core.model.RoutingMode
import com.latch.data.AccountDefaults
import com.latch.parser.DateParser
import com.latch.parser.ParseContext
import com.latch.webhook.WebhookDelivery
import com.latch.webhook.WebhookResult
import com.latch.webhook.decodeDelivery
import com.latch.webhook.encodeDelivery
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * FR-1004b's passive report, on this client.
 *
 * **It did not exist here until now, and the shape of the gap is worth stating**: the delivery
 * was made and its result thrown away, so a user whose endpoint answered 404 saw exactly what a
 * user whose endpoint was working saw, which was nothing. The requirement's first half — "shall
 * not raise a blocking error" — held all along; its second — "shall be reported passively in
 * the Settings screen" — had nothing behind it. Found while building the same requirement on
 * Windows, where it was built.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WebhookReportTest {

    private val context = ParseContext(
        now = LocalDateTime.parse("2026-09-03T09:00:00"),
        zone = ZoneId.of("Asia/Kolkata"),
    )

    private val defaults = AccountDefaults(
        accountId = "acct",
        email = "you@example.com",
        routingMode = RoutingMode.LATCH_CALENDAR,
        destinationCalendarId = "latch-cal",
        destinationCalendarName = "Latch",
        destinationCalendarColour = "#d50000",
        taskListId = "list-1",
    )

    /** What the application's own `recordDelivery` writes into: one key in the secret store. */
    private class Reports {
        var last: WebhookDelivery? = null
        val calls = mutableListOf<WebhookDelivery>()

        /** FR-1004b: one attempt. Counted apart from the reports, so a send that produced no
         *  report would still be visible. */
        var sent: Int = 0
    }

    private fun TestScope.saverWith(
        reports: Reports,
        endpoint: String?,
        enabled: Boolean,
        answer: WebhookDelivery = WebhookDelivery(
            Instant.parse("2026-09-03T09:15:00Z"),
            WebhookResult.UNREACHABLE,
        ),
    ): CaptureSaver {
        val queue = RecordingQueue()
        return CaptureSaver(
            defaultsStore = FixedDefaults(defaults),
            calendarApi = RecordingCalendarApi(),
            tasksApi = RecordingTasksApi(),
            writeQueue = queue,
            inbox = RecordingInbox(),
            index = RecordingIndex(),
            undoOffers = RecordingUndoOffers(),
            requestDrain = { queue.drainsRequested++ },
            scope = this,
            sourceLinkTemplate = "Captured from %1\$s",
            settings = { LatchSettings(webhookEnabled = enabled) },
            webhookEndpoint = { endpoint },
            recordDelivery = { delivery ->
                reports.last = delivery
                reports.calls += delivery
            },
            // The sender is tested against a real socket in `:webhook`. What is under test here
            // is whether its answer is kept, so it is a fake that gives a known one — and a
            // real socket would make this test depend on the network.
            deliverWebhookTo = { _, _ -> reports.sent++; answer },
        )
    }

    private fun TestScope.save(saver: CaptureSaver) {
        val text = "Kickoff 8 September 2027 at 9am"
        val result = DateParser.parse(text, context)
        saver.save(
            captured = CapturedText(text = text, layer = CaptureLayer.SHARE_SHEET),
            result = result,
            context = context,
        )
        runCurrent()
    }

    @Test
    fun `whatever the endpoint answered is what is reported`() = runTest {
        // The condition that would make this fail is the behaviour it replaces: `runCatching {}`
        // around the send with the result dropped. Every outcome is checked rather than one,
        // because a report that collapsed them would be the boolean this replaced.
        listOf(
            WebhookDelivery(Instant.parse("2026-09-03T09:15:00Z"), WebhookResult.DELIVERED, 200),
            WebhookDelivery(Instant.parse("2026-09-03T09:15:00Z"), WebhookResult.ENDPOINT_REFUSED, 404),
            WebhookDelivery(Instant.parse("2026-09-03T09:15:00Z"), WebhookResult.UNREACHABLE),
            WebhookDelivery(Instant.parse("2026-09-03T09:15:00Z"), WebhookResult.REFUSED_BEFORE_SENDING),
        ).forEach { answer ->
            val reports = Reports()
            save(saverWith(reports, endpoint = "https://example.invalid/hook", enabled = true, answer = answer))
            assertEquals(answer, assertNotNull(reports.last, "FR-1004b's outcome was discarded"))
        }
    }

    @Test
    fun `a success is reported too, because silence looks the same either way`() = runTest {
        // FR-1004b names only the failure. Reporting only failures would leave a webhook that
        // silently works and one that silently does nothing indistinguishable, and the second
        // is the state a user needs to notice.
        val reports = Reports()
        val ok = WebhookDelivery(Instant.parse("2026-09-03T09:15:00Z"), WebhookResult.DELIVERED, 204)
        save(saverWith(reports, endpoint = "https://example.invalid/hook", enabled = true, answer = ok))
        assertTrue(assertNotNull(reports.last).ok)
    }

    @Test
    fun `nothing is reported when the webhook is off`() {
        // FR-1004: disabled by default. A report about a delivery that never happened would be
        // worse than none — it would describe an endpoint the user has not switched on.
        runTest {
            val reports = Reports()
            save(saverWith(reports, endpoint = "https://example.invalid/hook", enabled = false))
            assertNull(reports.last)
        }
    }

    @Test
    fun `nothing is reported when there is no endpoint`() = runTest {
        val reports = Reports()
        save(saverWith(reports, endpoint = null, enabled = true))
        assertNull(reports.last)
    }

    @Test
    fun `FR-210a a notification capture reports nothing, because it sends nothing`() = runTest {
        // The third appearance of one rule, and the one that must not be weakened by adding a
        // report: a suppressed delivery is not a failed delivery, and recording it would put a
        // notification-derived capture on the Settings screen.
        val reports = Reports()
        val saver = saverWith(reports, endpoint = "https://example.invalid/hook", enabled = true)
        val text = "PTM on Friday 12 September 2027"
        saver.save(
            captured = CapturedText(text = text, layer = CaptureLayer.NOTIFICATION),
            result = DateParser.parse(text, context),
            context = context,
        )
        runCurrent()
        assertNull(reports.last)
        assertEquals(0, reports.sent, "a notification capture reached the sender")
    }

    @Test
    fun `one save reports once`() = runTest {
        // FR-1004b: "shall not be retried beyond a single immediate attempt". A second report
        // for one save would mean a second attempt.
        val reports = Reports()
        save(saverWith(reports, endpoint = "https://example.invalid/hook", enabled = true))
        assertEquals(1, reports.sent, "the sender was asked more than once")
        assertEquals(1, reports.calls.size)
    }

    // ---- the record both clients store ----------------------------------------------------

    @Test
    fun `the record round trips, and carries no endpoint`() {
        // Shared with the desktop so neither client invents a second shape. The endpoint is
        // deliberately absent: the screen this is shown on masks it (NFR-203), so a record
        // holding the address would be one render away from unmasking it.
        val delivery = WebhookDelivery(
            Instant.parse("2026-09-03T09:15:00Z"),
            WebhookResult.ENDPOINT_REFUSED,
            404,
        )
        val encoded = encodeDelivery(delivery)
        assertEquals(delivery, decodeDelivery(encoded))
        assertTrue("http" !in encoded, encoded)
    }

    @Test
    fun `an unreadable report reads as nothing rather than as a wrong answer`() {
        assertNull(decodeDelivery("{"))
        assertNull(decodeDelivery("{\"result\":\"DELIVERED\"}"))
        assertNull(decodeDelivery("{\"at\":\"2026-09-03T09:15:00Z\",\"result\":\"TELEPATHY\"}"))
    }
}
