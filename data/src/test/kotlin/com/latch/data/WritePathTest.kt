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

    // ----- FR-805b: an OCR capture stores an extract, never the screen -----

    /**
     * A shared screenshot as the recogniser actually returns one: the message that mattered,
     * with everything else that was on the screen above and below it.
     */
    private val screenful = buildString {
        append("9:41  Vodafone IN  4G  87%\n")
        append("Ritu: did you see the balance on the joint account, it is down to 4,120\n")
        append("Amit: I will move some across tonight\n")
        append("Ritu: also the card ending 8891 was declined at the chemist\n")
        append("Amit: I have told the bank, they are sending a replacement card\n")
        append("Ritu: fine. did Meera drop off the books she borrowed last month\n")
        append("Amit: yes on Sunday, they are on the shelf in the hall\n")
        append("Ritu: good. the plumber still has not called back about the kitchen tap\n")
        append("School office: Parent teacher meeting on 12 September at 11:00 AM in Hall B\n")
        append("Amit: noted, I will go\n")
        append("Ritu: my appointment with Dr Nair is the same week, do not double book\n")
        append("Amit: I will check the calendar tonight and move things around if I have to\n")
        append("Ritu: also the car service is overdue now, it has been fourteen months\n")
        append("Amit: I will book it in for the week after next\n")
        append("Ritu: the insurance renewal quote came in at 41,900 by the way\n")
        append("Ritu: that is a good deal higher than last year, ask them why\n")
    }

    /** Where "12 September" and "11:00 AM" sit in [screenful]. */
    private val screenfulDates: List<IntRange>
        get() = listOf(
            screenful.indexOf("12 September").let { it until it + "12 September".length },
            screenful.indexOf("11:00 AM").let { it until it + "11:00 AM".length },
        )

    private val ocrShare = CaptureSource(CaptureLayer.SHARE_SHEET, appId = "com.google.android.apps.photos", ocrUsed = true)

    @Test
    fun `an ocr capture keeps the row its date sits in, and its neighbours`() {
        val block = sourceBlock(ocrShare, screenful, screenfulDates)

        assertTrue(block.contains("Parent teacher meeting"), "the commitment itself must survive")
        assertTrue(block.contains("12 September"))
    }

    @Test
    fun `an ocr capture does not carry the rest of the thread`() {
        val block = sourceBlock(ocrShare, screenful, screenfulDates)

        // The rows this rule exists to drop: a balance and a card number above, an insurance
        // quote below. None of it is what the user captured.
        assertFalse(block.contains("4,120"), "a balance from another row was stored")
        assertFalse(block.contains("8891"), "a card number from another row was stored")
        assertFalse(block.contains("41,900"), "a quote from another row was stored")
    }

    @Test
    fun `the same screen captured as text stores it whole`() {
        // The discriminator is ocrUsed and nothing else. A user who selected this text meant
        // to send it; a recogniser that read it off a screenshot did not.
        val asText = sourceBlock(shareSheet, screenful, screenfulDates)
        assertTrue(asText.contains("4,120"))
        assertTrue(asText.contains("41,900"))
    }

    @Test
    fun `an extract says where it was cut`() {
        val block = sourceBlock(ocrShare, screenful, screenfulDates)
        assertTrue(block.contains("…"), "no ellipsis marks the elision")
    }

    // ----- FR-805b's conformance fixture: a note that was written into a real account -----

    private val deviceNote: String =
        checkNotNull(javaClass.getResourceAsStream("/fr805b/device-note.txt")) {
            "the FR-805b device fixture is missing"
        }.bufferedReader().readText().trim()

    /** Where "14 September 2026", "20/09/2027" and the mangled October pair sit in the note. */
    private val deviceNoteDates: List<IntRange>
        get() = listOf("14 September 2026", "20/09/2027").map { needle ->
            val at = deviceNote.indexOf(needle)
            at until at + needle.length
        }

    @Test
    fun `the device note is excerpted to strictly less than itself`() {
        // The defect this replaced: on this exact text the character radius kept everything,
        // and the note written to the user's account was the whole screen.
        val extract = sourceExcerpt(deviceNote, deviceNoteDates)

        assertTrue(
            extract.length < deviceNote.length,
            "extract is ${extract.length} chars against a note of ${deviceNote.length}",
        )
    }

    @Test
    fun `the device note loses the rows that are not about a date`() {
        // Length alone would pass on a one-character trim, so name what must be gone.
        val extract = sourceExcerpt(deviceNote, deviceNoteDates)

        assertFalse(extract.contains("Sharma Ji"), "the sender's name survived")
        assertFalse(extract.contains("Rs 12,50"), "the amount paid survived")
        assertFalse(extract.contains("Ok noted"), "an unrelated message survived")
        // And what it must keep: the commitments themselves.
        assertTrue(extract.contains("14 September 2026"))
        assertTrue(extract.contains("20/09/2027"))
    }

    @Test
    fun `a row window keeps whole messages rather than cutting mid-word`() {
        val extract = sourceExcerpt(deviceNote, deviceNoteDates)
        // Every line of the extract that is not an ellipsis is a line of the original.
        val noteLines = deviceNote.lines().map(String::trim).toSet()
        extract.lines().map(String::trim).filter { it.isNotEmpty() && it != "…" }.forEach {
            assertTrue(it in noteLines, "line was cut mid-way: <$it>")
        }
    }

    @Test
    fun `the budget still bounds a capture whose rows are enormous`() {
        // A rendered PDF page arrives as one very long line, which the row rule alone would
        // keep whole. This is what the retained outer bound is for.
        val oneHugeRow = "x".repeat(400) + " 12 September " + "y".repeat(400)
        val extract = sourceExcerpt(oneHugeRow, listOf(400 until 414))

        assertTrue(extract.length <= EXCERPT_BUDGET + 2, "budget overrun: ${extract.length}")
    }

    @Test
    fun `an ocr capture with no date found still carries provenance`() {
        val block = sourceExcerpt(screenful, dateSpans = emptyList())
        assertTrue(block.isNotEmpty())
        assertTrue(block.length <= EXCERPT_BUDGET + 2)
    }

    @Test
    fun `a notification that was also ocr stores nothing`() {
        val both = CaptureSource(CaptureLayer.NOTIFICATION, ocrUsed = true)
        assertEquals("", sourceBlock(both, screenful, screenfulDates))
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
