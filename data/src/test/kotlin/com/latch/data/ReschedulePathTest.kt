package com.latch.data

import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.json.JSONArray
import org.json.JSONObject

/**
 * FR-804: finding the item a capture reschedules, and moving it.
 *
 * The parts that can be held still — the URLs, the request bodies, the response readers and
 * the tie-break — are all here. What is left for a device is whether Google answers the
 * `privateExtendedProperty` query with the event, which is the same half of FR-803 that had
 * to be watched on a phone.
 *
 * The dates are 2030 so that nothing here rolls into the past: 5 March 2030 is a Tuesday and
 * 7 March 2030 is a Thursday, which is the reschedule pair the device sequence uses too.
 */
class ReschedulePathTest {

    private val key = itemKeyOf("Project sync")

    // ----- the queries -----

    @Test
    fun `the event query filters on item_key and does not stop at the first hit`() {
        val url = eventItemKeyUrl("you@example.com", key, pageToken = null)

        assertTrue(url.contains("privateExtendedProperty="), "Google must do the matching")
        assertTrue(url.contains("latch.item_key%3D$key"), url)
        // FR-803's query uses maxResults=1 because it asks a yes/no question. This one has to
        // see every match, because SRS §5.8 gives the offer to the latest of them.
        assertFalse(url.contains("maxResults=1&"), url)
        assertFalse(url.endsWith("maxResults=1"), url)
    }

    @Test
    fun `the task query carries no due window at all`() {
        val url = taskItemKeyUrl("list_1", pageToken = null)

        // The whole difference from the FR-803 scan. That window rests on a duplicate having
        // the same date; a reschedule has a different one by definition, so bounding by the
        // new date would search exactly where the item is not.
        assertFalse(url.contains("dueMin"), url)
        assertFalse(url.contains("dueMax"), url)
        // A ticked-off item still exists and can still be moved.
        assertTrue(url.contains("showCompleted=true"), url)
        assertTrue(url.contains("showHidden=true"), url)
    }

    @Test
    fun `the task dedup scan still has its window, so the two are not confused`() {
        val dedup = taskDedupUrl("list_1", LocalDate.parse("2030-03-05"), pageToken = null)
        assertTrue(dedup.contains("dueMin"), dedup)
        assertTrue(dedup.contains("dueMax"), dedup)
    }

    // ----- the patch bodies: dates only (§7.2's write-once invariant) -----

    @Test
    fun `the event patch carries dates and nothing else`() {
        val body = eventDatesBody(
            ItemDates.Event(
                start = LocalDateTime.parse("2030-03-07T21:30:00"),
                end = LocalDateTime.parse("2030-03-07T22:30:00"),
                timeZone = "Asia/Kolkata",
            ),
        )

        assertEquals(setOf("start", "end"), body.keys().asSequence().toSet())
        // The invariant: §7.2 metadata is written once, at insert. A patch that carried
        // extendedProperties would rewrite source_hash as a side effect of moving a date.
        assertFalse(body.has("extendedProperties"))
        assertFalse(body.has("summary"))
        assertFalse(body.has("description"))
        assertEquals("2030-03-07T21:30:00", body.getJSONObject("start").getString("dateTime"))
        assertEquals("Asia/Kolkata", body.getJSONObject("start").getString("timeZone"))
    }

    @Test
    fun `an event with no zone is patched without one, rather than with a guess`() {
        val body = eventDatesBody(
            ItemDates.Event(
                start = LocalDateTime.parse("2030-03-07T21:30:00"),
                end = LocalDateTime.parse("2030-03-07T22:30:00"),
                timeZone = null,
            ),
        )
        // It was using its calendar's default; leaving the field out puts it back on the
        // same footing, where naming a zone would silently pin it to one.
        assertFalse(body.getJSONObject("start").has("timeZone"))
    }

    @Test
    fun `an all-day event patches as dates`() {
        val body = eventDatesBody(
            ItemDates.Event(
                start = LocalDateTime.parse("2030-03-07T00:00:00"),
                end = LocalDateTime.parse("2030-03-08T00:00:00"),
                allDay = true,
            ),
        )
        assertEquals("2030-03-07", body.getJSONObject("start").getString("date"))
        assertFalse(body.getJSONObject("start").has("dateTime"))
    }

    @Test
    fun `the task patch carries due and never notes`() {
        val body = taskDatesBody(ItemDates.Task(LocalDate.parse("2030-03-07")))

        assertEquals(setOf("due"), body.keys().asSequence().toSet())
        // On tasks the §7.2 metadata lives inside the notes, so sending them would rewrite
        // the item's identity while moving its date.
        assertFalse(body.has("notes"))
        assertEquals("2030-03-07T00:00:00.000Z", body.getString("due"))
    }

    @Test
    fun `clearing a due date is an explicit null, not an omission`() {
        val body = taskDatesBody(ItemDates.Task(null))
        // Omitting a field in a patch means "leave it alone", which is the opposite.
        assertTrue(body.has("due"))
        assertEquals(JSONObject.NULL, body.get("due"))
    }

    // ----- reading what is there now, which is what undo will restore -----

    @Test
    fun `an event's current dates are read back, offset and all`() {
        val dates = assertNotNull(
            eventDatesFrom(
                JSONObject()
                    .put(
                        "start",
                        JSONObject()
                            .put("dateTime", "2030-03-05T21:30:00+05:30")
                            .put("timeZone", "Asia/Kolkata"),
                    )
                    .put("end", JSONObject().put("dateTime", "2030-03-05T22:30:00+05:30")),
            ),
        )

        val event = assertIs<ItemDates.Event>(dates)
        // The wall time is what an event's fields hold; the zone travels beside it.
        assertEquals(LocalDateTime.parse("2030-03-05T21:30:00"), event.start)
        assertEquals("Asia/Kolkata", event.timeZone)
        assertFalse(event.allDay)
    }

    @Test
    fun `an all-day event reads back as all-day`() {
        val dates = eventDatesFrom(
            JSONObject()
                .put("start", JSONObject().put("date", "2030-03-05"))
                .put("end", JSONObject().put("date", "2030-03-06")),
        )
        val event = assertIs<ItemDates.Event>(assertNotNull(dates))
        assertTrue(event.allDay)
        assertEquals(LocalDateTime.parse("2030-03-05T00:00:00"), event.start)
    }

    @Test
    fun `an event whose dates cannot be read is not a match`() {
        // A match with no readable prior state would be an update that could not be undone,
        // which is worse than not offering the update at all.
        assertNull(eventDatesFrom(JSONObject().put("start", JSONObject().put("dateTime", "not a date"))))
        assertNull(eventDatesFrom(JSONObject()))
    }

    @Test
    fun `a recurring event is refused, and is refused by rule rather than by accident`() {
        val recurring = JSONObject()
            .put("id", "ev_series")
            .put("recurrence", JSONArray().put("RRULE:FREQ=WEEKLY;BYDAY=TU"))
            .put("start", JSONObject().put("dateTime", "2030-03-05T21:30:00+05:30"))
            .put("end", JSONObject().put("dateTime", "2030-03-05T22:30:00+05:30"))

        // The dates are perfectly readable, which is the point: nothing else in this reader
        // would have stopped it, and events.patch on a series master moves every occurrence.
        assertNotNull(eventTimePointProbe(recurring), "the dates parse — so the refusal is not incidental")
        assertNull(eventDatesFrom(recurring))
    }

    @Test
    fun `one instance of a series is refused too`() {
        val instance = JSONObject()
            .put("id", "ev_instance")
            .put("recurringEventId", "ev_series")
            .put("start", JSONObject().put("dateTime", "2030-03-05T21:30:00+05:30"))
            .put("end", JSONObject().put("dateTime", "2030-03-05T22:30:00+05:30"))

        assertNull(eventDatesFrom(instance))
    }

    @Test
    fun `an empty recurrence array is not a series`() {
        val notRecurring = JSONObject()
            .put("recurrence", JSONArray())
            .put("start", JSONObject().put("dateTime", "2030-03-05T21:30:00+05:30"))
            .put("end", JSONObject().put("dateTime", "2030-03-05T22:30:00+05:30"))

        assertNotNull(eventDatesFrom(notRecurring))
    }

    @Test
    fun `a refused event is dropped from the matches rather than matched blindly`() {
        val page = JSONObject().put(
            "items",
            JSONArray()
                .put(
                    JSONObject()
                        .put("id", "ev_series")
                        .put("recurrence", JSONArray().put("RRULE:FREQ=WEEKLY"))
                        .put("start", JSONObject().put("dateTime", "2030-03-05T21:30:00+05:30"))
                        .put("end", JSONObject().put("dateTime", "2030-03-05T22:30:00+05:30")),
                )
                .put(
                    JSONObject()
                        .put("id", "ev_single")
                        .put("start", JSONObject().put("dateTime", "2030-03-05T21:30:00+05:30"))
                        .put("end", JSONObject().put("dateTime", "2030-03-05T22:30:00+05:30")),
                ),
        )

        assertEquals(listOf("ev_single"), eventMatchesFrom(page).map { it.remoteId })
    }

    /** Reads the same start the refusal skips past, to show the refusal is deliberate. */
    private fun eventTimePointProbe(event: JSONObject): ItemDates.Event? =
        eventDatesFrom(JSONObject(event.toString()).also { it.remove("recurrence") })

    @Test
    fun `an event match carries the stored summary, not anything derived from the capture`() {
        val page = JSONObject().put(
            "items",
            JSONArray().put(
                JSONObject()
                    .put("id", "ev_1")
                    .put("summary", "Project sync")
                    .put("start", JSONObject().put("dateTime", "2030-03-05T21:30:00+05:30"))
                    .put("end", JSONObject().put("dateTime", "2030-03-05T22:30:00+05:30")),
            ),
        )

        // FR-804's offer asks about the item already in the account, so the item's own name
        // has to travel with the match — the app has no other way to reach it.
        assertEquals("Project sync", eventMatchesFrom(page).single().title)
    }

    @Test
    fun `an event with no summary matches with a blank title rather than not matching`() {
        val page = JSONObject().put(
            "items",
            JSONArray().put(
                JSONObject()
                    .put("id", "ev_1")
                    .put("start", JSONObject().put("dateTime", "2030-03-05T21:30:00+05:30"))
                    .put("end", JSONObject().put("dateTime", "2030-03-05T22:30:00+05:30")),
            ),
        )

        // A nameless item is still the item to move. What to show for it is the app's
        // problem, not a reason to lose the match.
        assertEquals("", eventMatchesFrom(page).single().title)
    }

    @Test
    fun `a task match carries the stored title`() {
        val metadata = RemoteMetadata(
            sourceHash = sourceHashOf("Project sync on Tuesday 5 March 2030"),
            itemKey = key,
            chainId = "3f2504e0-4f89-41d3-9a0c-0305e82c3301",
            capturedAt = java.time.Instant.parse("2030-03-01T09:00:00Z"),
        )
        val page = JSONObject().put(
            "items",
            JSONArray().put(
                JSONObject()
                    .put("id", "t_1")
                    .put("title", "Project sync")
                    .put("notes", metadata.toTaskNotes("body")),
            ),
        )

        assertEquals("Project sync", taskMatchesFrom(page, key).single().title)
    }

    @Test
    fun `a task's due date is read back`() {
        assertEquals(
            LocalDate.parse("2030-03-05"),
            taskDueFrom(JSONObject().put("due", "2030-03-05T00:00:00.000Z")),
        )
        assertNull(taskDueFrom(JSONObject()))
    }

    @Test
    fun `tasks are matched on item_key, and unreadable notes are simply not a match`() {
        val metadata = RemoteMetadata(
            sourceHash = sourceHashOf("Project sync on Tuesday 5 March 2030"),
            itemKey = key,
            chainId = "3f2504e0-4f89-41d3-9a0c-0305e82c3301",
            capturedAt = java.time.Instant.parse("2030-03-01T09:00:00Z"),
        )
        val page = JSONObject().put(
            "items",
            JSONArray()
                .put(JSONObject().put("id", "t_match").put("notes", metadata.toTaskNotes("body")))
                .put(JSONObject().put("id", "t_other").put("notes", "notes a user rewrote"))
                .put(JSONObject().put("id", "t_none")),
        )

        val matches = taskMatchesFrom(page, key)

        assertEquals(listOf("t_match"), matches.map { it.remoteId })
    }

    @Test
    fun `a task carrying a different key is not a match`() {
        val other = RemoteMetadata(
            sourceHash = sourceHashOf("something else"),
            itemKey = itemKeyOf("Dentist"),
            chainId = "3f2504e0-4f89-41d3-9a0c-0305e82c3301",
            capturedAt = java.time.Instant.parse("2030-03-01T09:00:00Z"),
        )
        val page = JSONObject().put(
            "items",
            JSONArray().put(JSONObject().put("id", "t_1").put("notes", other.toTaskNotes("body"))),
        )
        assertTrue(taskMatchesFrom(page, key).isEmpty())
    }

    // ----- SRS §5.8's tie-break -----

    @Test
    fun `where several events share a key the latest wins`() {
        val early = RescheduleMatch("ev_early", eventAt("2030-03-05T21:30:00"), "Project sync")
        val late = RescheduleMatch("ev_late", eventAt("2030-03-07T21:30:00"), "Project sync")

        // Whichever order the API returned them in.
        assertEquals("ev_late", latestEventMatch(null, listOf(early, late))?.remoteId)
        assertEquals("ev_late", latestEventMatch(null, listOf(late, early))?.remoteId)
        // And across pages, where the running best is carried in.
        assertEquals("ev_late", latestEventMatch(early, listOf(late))?.remoteId)
        assertEquals("ev_late", latestEventMatch(late, listOf(early))?.remoteId)
    }

    @Test
    fun `an undated task never outranks a dated one`() {
        val dated = RescheduleMatch("t_dated", ItemDates.Task(LocalDate.parse("2030-03-05")), "Project sync")
        val undated = RescheduleMatch("t_undated", ItemDates.Task(null), "Project sync")

        // It has no position, so it cannot be the latest position.
        assertEquals("t_dated", latestTaskMatch(null, listOf(dated, undated))?.remoteId)
        assertEquals("t_dated", latestTaskMatch(null, listOf(undated, dated))?.remoteId)
        // But it is still better than nothing at all.
        assertEquals("t_undated", latestTaskMatch(null, listOf(undated))?.remoteId)
    }

    @Test
    fun `no candidates leaves the running best alone`() {
        val best = RescheduleMatch("ev_1", eventAt("2030-03-05T21:30:00"), "Project sync")
        assertEquals(best, latestEventMatch(best, emptyList()))
        assertNull(latestEventMatch(null, emptyList()))
    }

    @Test
    fun `a search with no match is not found`() {
        assertFalse(RescheduleSearch().found)
        assertTrue(RescheduleSearch(RescheduleMatch("ev_1", eventAt("2030-03-05T21:30:00"), "Project sync")).found)
    }

    private fun eventAt(start: String) = ItemDates.Event(
        start = LocalDateTime.parse(start),
        end = LocalDateTime.parse(start).plusHours(1),
        timeZone = "Asia/Kolkata",
    )
}
