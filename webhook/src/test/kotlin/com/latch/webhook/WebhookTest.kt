package com.latch.webhook

import com.latch.core.model.CaptureLayer
import com.latch.core.model.CaptureSource
import com.latch.core.model.Item
import com.latch.core.model.ItemType
import com.latch.google.ALLOWED_HOSTS
import com.latch.google.json.JSONObject
import com.latch.google.requireGoogleEndpoint
import com.latch.wire.RemoteMetadata
import com.latch.wire.sourceBlock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * FR-1004, FR-1004a, FR-1004b, FR-210a and NFR-203's masking.
 *
 * The delivery itself needs a socket and is not reachable here; what is, and what matters most,
 * is **what may be in the payload**. FR-1004a is a closed list, and the failure it guards
 * against is silent — a field added to `Item` that a reflective serialiser would have started
 * sending to a third party without anyone deciding to.
 */
class WebhookTest {

    private val metadata = RemoteMetadata(
        sourceHash = "a".repeat(64),
        itemKey = "b".repeat(64),
        chainId = "3f2504e0-4f89-41d3-9a0c-0305e82c3301",
        capturedAt = Instant.parse("2026-09-02T09:15:00Z"),
        sourceApp = "android:com.whatsapp",
        recipeId = "builtin.meeting_prep",
    )

    private val event = Item(
        id = "chain#0",
        captureId = "capture",
        chainId = "chain",
        type = ItemType.EVENT,
        title = "Project sync",
        start = LocalDateTime.parse("2027-09-08T09:00"),
        end = LocalDateTime.parse("2027-09-08T10:00"),
        location = "Room 3",
        calendarId = "latch-cal",
        reminderMinutes = listOf(30),
    )

    private val task = Item(
        id = "chain#1",
        captureId = "capture",
        chainId = "chain",
        type = ItemType.TASK,
        title = "Prepare for Project sync",
        dueDate = LocalDate.parse("2027-09-03"),
        taskListId = "list-1",
    )

    // ----- FR-1004a: the closed list -----

    @Test
    fun `the payload carries exactly the keys FR-1004a names`() {
        // The requirement is a list, so this is the list. A key that appeared here without
        // appearing in FR-1004a would be content sent to a third party that no requirement
        // authorised, which is the failure this test exists for rather than a style check.
        val json = JSONObject(webhookPayload(event, metadata, "the captured text"))
        assertEquals(
            setOf("type", "title", "start", "end", "location", "notes", "recipe", "chain_id", "captured_at", "source_app"),
            json.keys().asSequence().toSet(),
        )
    }

    @Test
    fun `a task's payload carries its due date and no start`() {
        val json = JSONObject(webhookPayload(task, metadata, "body"))
        assertEquals("task", json.getString("type"))
        assertEquals("2027-09-03", json.getString("due_date"))
        assertFalse(json.has("start"))
    }

    @Test
    fun `nothing that is absent is written empty`() {
        // §7.2's rule applied here too: an empty value cannot be told from a value that is
        // genuinely empty, and a reader cannot then tell "unknown" from "known to be nothing".
        val bare = metadata.copy(sourceApp = null, recipeId = null)
        val json = JSONObject(webhookPayload(task.copy(location = null), bare, ""))
        assertFalse(json.has("source_app"))
        assertFalse(json.has("recipe"))
        assertFalse(json.has("location"))
        assertFalse(json.has("notes"))
    }

    @Test
    fun `the notes are the composed FR-805 body, so FR-805a and FR-805b already applied`() {
        // A notification capture reaches here with an empty body because `sourceBlock` dropped
        // its text upstream — the rule is structural there and this inherits it.
        val notificationBody = sourceBlock(
            source = CaptureSource(layer = CaptureLayer.NOTIFICATION),
            sourceText = "PTM on Friday 12 September",
        )
        assertFalse(JSONObject(webhookPayload(task, metadata, notificationBody)).has("notes"))
    }

    @Test
    fun `a chain is one delivery, because FR-1004 fires on save and not per item`() {
        val payload = JSONObject(webhookPayloadForChain(listOf(event, task), metadata, "body"))
        assertEquals(1, payload.getInt("version"))
        assertEquals(2, payload.getJSONArray("items").length())
    }

    // ----- FR-210a -----

    @Test
    fun `a notification capture is never delivered, whatever the settings say`() {
        // FR-210a: "shall be suppressed for any capture originating from this layer, whether or
        // not a webhook is configured". The third appearance of one rule, after FR-805a's
        // source-text exclusion and FR-701's Inbox exclusion.
        assertFalse(
            webhookEligible(
                source = CaptureSource(layer = CaptureLayer.NOTIFICATION),
                enabled = true,
                endpoint = "https://example.invalid/hook",
            )
        )
    }

    @Test
    fun `an ordinary capture is delivered only when both halves are true`() {
        val share = CaptureSource(layer = CaptureLayer.SHARE_SHEET)
        assertTrue(webhookEligible(share, enabled = true, endpoint = "https://example.invalid/hook"))
        // FR-1004: disabled by default.
        assertFalse(webhookEligible(share, enabled = false, endpoint = "https://example.invalid/hook"))
        // And requires the URL to have been entered explicitly.
        assertFalse(webhookEligible(share, enabled = true, endpoint = null))
        assertFalse(webhookEligible(share, enabled = true, endpoint = "  "))
    }

    // ----- FR-1004's endpoint, and NFR-203's masking -----

    @Test
    fun `an https endpoint is accepted`() {
        assertEquals(null, validateEndpoint("https://example.invalid/hooks/abc"))
    }

    @Test
    fun `plaintext is refused outright rather than warned about`() {
        // This address receives the user's captured content, and over http that is not a
        // decision a warning makes reasonable.
        assertEquals(EndpointRefusal.NOT_HTTPS, validateEndpoint("http://example.invalid/hook"))
    }

    @Test
    fun `credentials in the authority are refused`() {
        assertEquals(
            EndpointRefusal.CARRIES_CREDENTIALS,
            validateEndpoint("https://user:pass@example.invalid/hook"),
        )
    }

    @Test
    fun `something that is not a URL is refused`() {
        assertEquals(EndpointRefusal.MALFORMED, validateEndpoint("not a url"))
        assertEquals(EndpointRefusal.MALFORMED, validateEndpoint(""))
    }

    @Test
    fun `the mask keeps the host and loses everything after it`() {
        // NFR-203's reason: "such URLs commonly embed a bearer token in the path or query
        // string". The host survives so the user can recognise which endpoint they configured.
        val masked = maskedEndpoint("https://hooks.example.invalid/services/T000/B000/XXXXsecretXXXX")
        assertTrue(masked.startsWith("https://hooks.example.invalid"))
        assertFalse(masked.contains("secret"))
        assertFalse(masked.contains("T000"))
    }

    @Test
    fun `the mask does not disclose the length of what it hides`() {
        val short = maskedEndpoint("https://example.invalid/a")
        val long = maskedEndpoint("https://example.invalid/" + "x".repeat(200))
        assertEquals(short, long)
    }

    @Test
    fun `something unparseable masks to nothing recognisable rather than to itself`() {
        assertFalse(maskedEndpoint("garbage").contains("garbage"))
    }

    // ----- AC-17's guard is untouched -----

    @Test
    fun `a webhook endpoint would still be refused by the Google guard`() {
        // The separation is the point: `ALLOWED_HOSTS` holds AC-17 for every request this app
        // composes, and a webhook is the single documented exception to it (NFR-201, AC-18).
        // Widening that set to carry a webhook would destroy the property it exists to hold, so
        // a webhook is sent by a separate client with its own, narrower rules.
        val refused = runCatching { requireGoogleEndpoint("https://example.invalid/hook") }
        assertTrue(refused.isFailure)
    }

    @Test
    fun `a webhook cannot enter the write queue, structurally`() {
        // FR-1004b: "shall not be placed in the FR-806 write queue". It could not be: every
        // entry there drains through CalendarApi/TasksApi and the guard above, which refuses
        // any non-Google host — so the rule holds without anyone remembering to check it.
        ALLOWED_HOSTS.forEach { host -> assertTrue(host.endsWith("googleapis.com")) }
        assertFalse("example.invalid" in ALLOWED_HOSTS)
    }
}
