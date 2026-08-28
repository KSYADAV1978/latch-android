package com.latch.data

import com.latch.core.model.CaptureLayer
import com.latch.core.model.CaptureSource
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.json.JSONObject

/**
 * FR-801 to FR-803: what the app sends to Google, and the duplicate check that must precede
 * it. Request building and response reading are kept separate from the socket, so all of it
 * runs here without a device or an account.
 */
class WritePathTest {

    private val metadata = RemoteMetadata(
        sourceHash = sourceHashOf("Team sync 5 Sep at 3pm"),
        itemKey = itemKeyOf("Team sync"),
        chainId = "3f2504e0-4f89-41d3-9a0c-0305e82c3301",
        capturedAt = Instant.parse("2026-08-26T14:03:22Z"),
        sourceApp = "android:com.google.android.gm",
    )

    private val timedEvent = EventWrite(
        summary = "Team sync",
        description = "Team sync 5 Sep at 3pm",
        location = "Room 4",
        start = LocalDateTime.parse("2026-09-05T15:00:00"),
        end = LocalDateTime.parse("2026-09-05T16:00:00"),
        timeZone = "Asia/Kolkata",
        metadata = metadata,
    )

    // ----- events.insert -----

    @Test
    fun `a timed event carries dateTime with seconds and a zone`() {
        val body = eventRequestBody(timedEvent)
        val start = body.getJSONObject("start")

        assertEquals("2026-09-05T15:00:00", start.getString("dateTime"))
        assertEquals("Asia/Kolkata", start.getString("timeZone"))
        assertFalse(start.has("date"))
        assertEquals("Team sync", body.getString("summary"))
        assertEquals("Room 4", body.getString("location"))
    }

    @Test
    fun `seconds survive a whole-minute time`() {
        // ISO_LOCAL_DATE_TIME drops :00 seconds, and the API documents RFC 3339, which
        // requires them. This is the case that would have been silently wrong.
        val onTheHour = timedEvent.copy(start = LocalDateTime.parse("2026-09-05T15:00"))
        assertEquals(
            "2026-09-05T15:00:00",
            eventRequestBody(onTheHour).getJSONObject("start").getString("dateTime"),
        )
    }

    @Test
    fun `an all-day event carries a bare date and no zone`() {
        val allDay = timedEvent.copy(
            allDay = true,
            start = LocalDateTime.parse("2026-09-05T00:00:00"),
            // Google's end date is exclusive: a single day on the 5th ends on the 6th.
            end = LocalDateTime.parse("2026-09-06T00:00:00"),
        )
        val body = eventRequestBody(allDay)

        assertEquals("2026-09-05", body.getJSONObject("start").getString("date"))
        assertEquals("2026-09-06", body.getJSONObject("end").getString("date"))
        assertFalse(body.getJSONObject("start").has("dateTime"))
        assertFalse(body.getJSONObject("start").has("timeZone"))
    }

    @Test
    fun `the event carries its metadata privately`() {
        val properties = eventRequestBody(timedEvent)
            .getJSONObject("extendedProperties")
            .getJSONObject("private")

        assertEquals(metadata.sourceHash, properties.getString(KEY_SOURCE_HASH))
        assertEquals(metadata.itemKey, properties.getString(KEY_ITEM_KEY))
        assertEquals("1", properties.getString(KEY_VERSION))
        // Shared properties are visible to every attendee; this is the user's own provenance.
        assertFalse(eventRequestBody(timedEvent).getJSONObject("extendedProperties").has("shared"))
    }

    @Test
    fun `an empty description is omitted rather than sent blank`() {
        val body = eventRequestBody(timedEvent.copy(description = "", location = null))
        assertFalse(body.has("description"))
        assertFalse(body.has("location"))
    }

    // ----- tasks.insert -----

    @Test
    fun `a task carries a date-only due and its metadata line`() {
        val body = taskRequestBody(
            TaskWrite(
                title = "Pay the electricity bill",
                notes = "Due by the 5th",
                due = LocalDate.parse("2026-09-05"),
                metadata = metadata,
            )
        )

        assertEquals("Pay the electricity bill", body.getString("title"))
        assertEquals("2026-09-05T00:00:00.000Z", body.getString("due"))
        assertEquals(metadata, remoteMetadataFromTaskNotes(body.getString("notes")))
        assertEquals("Due by the 5th", taskNotesWithoutMetadata(body.getString("notes")))
    }

    @Test
    fun `an undated task omits due but still carries metadata`() {
        val body = taskRequestBody(TaskWrite("Undated", "", null, metadata))
        assertFalse(body.has("due"))
        assertEquals(metadata, remoteMetadataFromTaskNotes(body.getString("notes")))
    }

    // ----- FR-805a, which is AC-22 at unit level -----

    private val notification = CaptureSource(CaptureLayer.NOTIFICATION, appId = "com.whatsapp")
    private val shareSheet = CaptureSource(CaptureLayer.SHARE_SHEET, appId = "com.whatsapp")
    private val message = "Ravi: pay the school fee by 5 Sep, account 1234"

    @Test
    fun `a notification's text never reaches the item`() {
        val block = sourceBlock(notification, message, sourceLink = "WhatsApp, 26 Aug 14:03")

        assertFalse(block.contains("school fee"))
        assertFalse(block.contains("1234"))
        // The link survives: it is provenance, not content.
        assertEquals("WhatsApp, 26 Aug 14:03", block)
    }

    @Test
    fun `every other layer keeps its source text`() {
        val block = sourceBlock(shareSheet, message, sourceLink = "WhatsApp, 26 Aug 14:03")
        assertTrue(block.contains(message))
        assertTrue(block.contains("WhatsApp, 26 Aug 14:03"))
    }

    @Test
    fun `a notification with no link yields nothing at all to store`() {
        assertEquals("", sourceBlock(notification, message))
    }

    @Test
    fun `the exclusion reaches the request bodies, not just the block`() {
        // The end that AC-22 actually inspects: the message must appear in no field of what
        // is sent to Google.
        val description = sourceBlock(notification, message)
        val event = eventRequestBody(timedEvent.copy(description = description)).toString()
        val task = taskRequestBody(TaskWrite("Fee", description, null, metadata)).toString()

        assertFalse(event.contains("school fee"), "source text leaked into the event body")
        assertFalse(task.contains("school fee"), "source text leaked into the task body")
    }

    // ----- FR-803: the event query -----

    @Test
    fun `the event dedup query filters server-side on the source hash`() {
        val url = eventDedupUrl("work@example.com", "a".repeat(64))

        assertTrue(url.startsWith("https://www.googleapis.com/calendar/v3/calendars/"))
        assertTrue(url.contains("work%40example.com/events"))
        // The key=value pair is one encoded parameter value, not two parameters.
        assertTrue(url.contains("privateExtendedProperty=latch.source_hash%3D" + "a".repeat(64)))
        assertTrue(url.contains("maxResults=1"))
        // Deleted events are excluded by the API default, which is what FR-807 undo needs.
        assertFalse(url.contains("showDeleted"))
    }

    @Test
    fun `an event hit returns the id and a miss returns null`() {
        val hit = JSONObject("""{"items":[{"id":"evt_123","summary":"Team sync"}]}""")
        assertEquals("evt_123", firstEventId(hit))

        assertNull(firstEventId(JSONObject("""{"items":[]}""")))
        assertNull(firstEventId(JSONObject("""{"kind":"calendar#events"}""")))
    }

    // ----- FR-803: the task scan -----

    @Test
    fun `a dated task scan is bounded to the day either side`() {
        val url = taskDedupUrl("list1", LocalDate.parse("2026-09-05"), pageToken = null)

        // The window is a day wide only to absorb time-zone boundary differences between two
        // devices, which is where AC-07 would otherwise fail silently.
        assertTrue(url.contains("dueMin=2026-09-04T00%3A00%3A00.000Z"), url)
        assertTrue(url.contains("dueMax=2026-09-06T23%3A59%3A59.999Z"), url)
        // A duplicate the user already ticked off still exists and must still be found.
        assertTrue(url.contains("showCompleted=true"))
        assertTrue(url.contains("showHidden=true"))
        assertFalse(url.contains("showDeleted"))
    }

    @Test
    fun `an undated task scan has nothing to bound it`() {
        val url = taskDedupUrl("list1", due = null, pageToken = null)
        assertFalse(url.contains("dueMin"))
        assertFalse(url.contains("dueMax"))
        assertTrue(url.contains("maxResults=100"))
    }

    @Test
    fun `a page token is carried into the next request`() {
        assertTrue(taskDedupUrl("list1", null, "TOKEN/2").contains("pageToken=TOKEN%2F2"))
    }

    @Test
    fun `a task is matched by the hash in its notes`() {
        val page = JSONObject().put(
            "items",
            org.json.JSONArray()
                .put(JSONObject().put("id", "other").put("notes", "unrelated task"))
                .put(
                    JSONObject().put("id", "task_42")
                        .put("notes", metadata.toTaskNotes("Due by the 5th"))
                ),
        )
        assertEquals("task_42", matchingTaskId(page, metadata.sourceHash))
        assertNull(matchingTaskId(page, "b".repeat(64)))
    }

    @Test
    fun `a task whose notes the user mangled is skipped, not fatal`() {
        val page = JSONObject().put(
            "items",
            org.json.JSONArray()
                .put(JSONObject().put("id", "broken").put("notes", "[latch]v=1;sh=garbage"))
                .put(JSONObject().put("id", "no_notes"))
                .put(
                    JSONObject().put("id", "good")
                        .put("notes", metadata.toTaskNotes(""))
                ),
        )
        assertEquals("good", matchingTaskId(page, metadata.sourceHash))
    }

    @Test
    fun `a capped scan is reported rather than passed off as no duplicate`() {
        // The distinction that matters: "I looked everywhere and found nothing" and "I gave
        // up" must not arrive at the caller looking the same.
        assertFalse(DuplicateSearch(existingId = null).scanCapped)
        assertFalse(DuplicateSearch(existingId = null, scanCapped = true).found)
        assertTrue(DuplicateSearch(existingId = "task_42").found)
    }

    // ----- FR-807, the delete half -----

    @Test
    fun `a delete addresses the item by its container and its own id`() {
        assertEquals(
            "https://www.googleapis.com/calendar/v3/calendars/you%40example.com/events/ev_42",
            eventItemUrl("you@example.com", "ev_42"),
        )
        assertEquals(
            "https://tasks.googleapis.com/tasks/v1/lists/list_1/tasks/task_42",
            taskItemUrl("list_1", "task_42"),
        )
        // Both ids reach the URL as path segments, so neither may carry a raw separator out
        // of the account and into a path this code did not intend.
        assertTrue(eventItemUrl("a/b", "c d").endsWith("/calendars/a%2Fb/events/c%20d"))
    }

    @Test
    fun `an item that is already gone is a successful undo`() {
        // 404 for an id the API no longer knows, 410 for one deleted since. FR-807 asked for
        // the item not to be in the account, and it is not — reporting a failure would tell
        // the user it survived, and send them looking for something that is not there.
        assertTrue(alreadyGone(GoogleRejected(404, "notFound", "Not Found")))
        assertTrue(alreadyGone(GoogleRejected(410, "deleted", "Gone")))

        // Everything else did leave the item in place and has to reach the user.
        assertFalse(alreadyGone(GoogleRejected(403, "forbidden", "Forbidden")))
        assertFalse(alreadyGone(GoogleRejected(401, null, "Unauthorized")))
        assertFalse(alreadyGone(GoogleRejected(500, null, "Internal Error")))
    }
}
