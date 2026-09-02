package com.latch.data

import com.latch.core.model.Item
import com.latch.core.model.ItemType
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * FR-1005: `.ics` export.
 *
 * It is a wire format read by somebody else's calendar program, so it gets §7.2's treatment
 * rather than a smoke test. The three things that actually break an `.ics` file are the line
 * endings, the folding and the escaping, and none of them is visible by looking at the output
 * on a screen.
 */
class IcsExportTest {

    private val now = Instant.parse("2026-09-02T09:15:00Z")

    private val event = Item(
        id = "chain#0",
        captureId = "capture",
        chainId = "chain",
        type = ItemType.EVENT,
        title = "Project sync",
        start = LocalDateTime.parse("2027-09-08T09:00"),
        end = LocalDateTime.parse("2027-09-08T10:00"),
        location = "Room 3",
        notes = "the captured text",
        calendarId = "latch-cal",
        reminderMinutes = listOf(30),
    )

    private val allDay = event.copy(
        id = "chain#1",
        title = "Offsite",
        start = LocalDateTime.parse("2027-09-20T00:00"),
        // Exclusive, exactly as `ItemDrafts` made it for Google's all-day API — so no
        // arithmetic happens in the exporter and none can go wrong there.
        end = LocalDateTime.parse("2027-09-25T00:00"),
        allDay = true,
        location = null,
        notes = null,
        reminderMinutes = emptyList(),
    )

    private val task = Item(
        id = "chain#2",
        captureId = "capture",
        chainId = "chain",
        type = ItemType.TASK,
        title = "Fees",
        dueDate = LocalDate.parse("2027-09-20"),
        notes = "Originally dated 12 March 2026.",
        taskListId = "list-1",
    )

    private fun export(vararg items: Item) = icsCalendar(items.toList(), "Asia/Kolkata", now)

    @Test
    fun `the envelope is what a reader expects`() {
        val ics = export(event)
        assertTrue(ics.startsWith("BEGIN:VCALENDAR\r\n"))
        assertTrue(ics.contains("VERSION:2.0\r\n"))
        assertTrue(ics.contains("PRODID:"))
        // PUBLISH, not REQUEST: an export is not an invitation asking the reader to reply.
        assertTrue(ics.contains("METHOD:PUBLISH\r\n"))
        assertTrue(ics.endsWith("END:VCALENDAR\r\n"))
    }

    @Test
    fun `every line ends CRLF, which RFC 5545 requires`() {
        // "Most readers accept bare newlines" is not a property to ship a file format on.
        val ics = export(event, task)
        ics.split("\r\n").dropLast(1).forEach { line ->
            assertFalse(line.contains('\n'), "a bare newline survived: $line")
            assertFalse(line.contains('\r'), "a bare carriage return survived: $line")
        }
    }

    @Test
    fun `a timed event carries its zone and its local time`() {
        val ics = export(event)
        assertTrue(ics.contains("BEGIN:VEVENT\r\n"))
        assertTrue(ics.contains("DTSTART;TZID=Asia/Kolkata:20270908T090000\r\n"), ics)
        assertTrue(ics.contains("DTEND;TZID=Asia/Kolkata:20270908T100000\r\n"), ics)
        assertTrue(ics.contains("SUMMARY:Project sync\r\n"))
        assertTrue(ics.contains("LOCATION:Room 3\r\n"))
    }

    @Test
    fun `an all-day event uses VALUE=DATE and keeps the exclusive end`() {
        val ics = export(allDay)
        assertTrue(ics.contains("DTSTART;VALUE=DATE:20270920\r\n"), ics)
        // The 25th, not the 24th: the exclusive end is what the drafting step already produced
        // for Google, and RFC 5545 reads DTEND the same way.
        assertTrue(ics.contains("DTEND;VALUE=DATE:20270925\r\n"), ics)
    }

    @Test
    fun `a task exports as a VTODO, not as a day-long appointment`() {
        // The two are different things in RFC 5545 and most readers show them differently.
        // Exporting a to-do as an all-day event would put a block on someone's calendar that
        // Latch never created.
        val ics = export(task)
        assertTrue(ics.contains("BEGIN:VTODO\r\n"))
        assertTrue(ics.contains("DUE;VALUE=DATE:20270920\r\n"), ics)
        assertFalse(ics.contains("VEVENT"))
    }

    @Test
    fun `an undated task exports with no DUE rather than with one invented`() {
        // Design principle 1, reaching as far as a file format.
        val ics = export(task.copy(dueDate = null))
        assertTrue(ics.contains("BEGIN:VTODO"))
        assertFalse(ics.contains("DUE"))
    }

    @Test
    fun `a reminder becomes a VALARM`() {
        val ics = export(event)
        assertTrue(ics.contains("BEGIN:VALARM\r\n"))
        assertTrue(ics.contains("TRIGGER:-PT30M\r\n"), ics)
        assertTrue(ics.contains("END:VALARM\r\n"))
    }

    @Test
    fun `no latch metadata is exported`() {
        // The keys are a cross-client contract between §4.1's three clients writing to one
        // Google account. They mean nothing to another calendar program, and exporting a
        // source_hash would put a digest of the user's own message into a file they may email.
        val ics = export(event, task)
        listOf(
            KEY_SOURCE_HASH, KEY_ITEM_KEY, "latch.chain_id",
            "latch.captured_at", "latch.version", "latch.recipe", "latch.source_app",
        ).forEach { key -> assertFalse(ics.contains(key), "$key reached the export") }
        assertFalse(ics.contains("X-LATCH"))
        // `latch.app` in the UID is the export's own domain and is not metadata; the
        // assertion above is over §7.2's keys rather than over the string "latch".
        assertTrue(ics.contains("@latch.app"))
    }

    // ----- escaping, §3.3.11 -----

    @Test
    fun `the four escapable characters are escaped, and the backslash goes first`() {
        // The order is the one place this can go wrong: escaping the backslash last would
        // escape the backslashes this function itself introduces a second time.
        assertEquals("a\\\\b", escape("a\\b"))
        assertEquals("a\\;b", escape("a;b"))
        assertEquals("a\\,b", escape("a,b"))
        assertEquals("a\\nb", escape("a\nb"))
        assertEquals("a\\nb", escape("a\r\nb"))
    }

    @Test
    fun `a title with a semicolon does not break the line it is on`() {
        val ics = export(event.copy(title = "Sync; then lunch, maybe"))
        assertTrue(ics.contains("SUMMARY:Sync\\; then lunch\\, maybe\r\n"), ics)
    }

    @Test
    fun `a multi-line note becomes one folded line`() {
        val ics = export(task.copy(notes = "first\nsecond"))
        assertFalse(ics.contains("DESCRIPTION:first\r\nsecond"))
        assertTrue(ics.contains("first\\nsecond"), ics)
    }

    // ----- folding, §3.1 -----

    @Test
    fun `a short line is not folded`() {
        assertEquals(listOf("SUMMARY:short"), fold("SUMMARY:short"))
    }

    @Test
    fun `a long line folds at 75 octets with a leading space on each continuation`() {
        val folded = fold("SUMMARY:" + "a".repeat(200))
        assertTrue(folded.size > 1)
        assertEquals(75, folded.first().toByteArray(Charsets.UTF_8).size)
        folded.drop(1).forEach { line ->
            assertTrue(line.startsWith(" "), "a continuation must begin with a space: $line")
            assertTrue(line.toByteArray(Charsets.UTF_8).size <= 75)
        }
        // Unfolding is removing the CRLF and the following space; the original must come back.
        assertEquals("SUMMARY:" + "a".repeat(200), folded.joinToString("") { it.removePrefix(" ") })
    }

    @Test
    fun `folding never splits a multi-byte character in half`() {
        // This is the whole reason the fold counts octets rather than characters. A Devanagari
        // title is three bytes per character, and a fold at 75 *characters* would break a
        // sequence in half and produce a file nothing can decode.
        val devanagari = "बैठक".repeat(40)
        val folded = fold("SUMMARY:$devanagari")
        folded.forEach { line ->
            assertTrue(line.toByteArray(Charsets.UTF_8).size <= 75, "line is ${line.toByteArray(Charsets.UTF_8).size} octets")
            // Round-tripping through UTF-8 is lossless only if no character was split.
            assertEquals(line, String(line.toByteArray(Charsets.UTF_8), Charsets.UTF_8))
        }
        assertEquals("SUMMARY:$devanagari", folded.joinToString("") { it.removePrefix(" ") })
    }

    @Test
    fun `a long title in a real export is folded rather than emitted whole`() {
        val ics = export(event.copy(title = "Project sync ".repeat(20)))
        ics.split("\r\n").forEach { line ->
            assertTrue(line.toByteArray(Charsets.UTF_8).size <= 75, "unfolded line: $line")
        }
    }

    // ----- the filename -----

    @Test
    fun `the filename comes from the first title and is safe everywhere`() {
        assertEquals("Project-sync.ics", icsFileName(listOf(event)))
        assertEquals("Fees.ics", icsFileName(listOf(task, event)))
    }

    @Test
    fun `a title of punctuation still produces a usable filename`() {
        assertEquals("latch-export.ics", icsFileName(listOf(event.copy(title = "…/?*"))))
        assertEquals("latch-export.ics", icsFileName(emptyList()))
    }

    @Test
    fun `a chain exports as one file with one component each`() {
        val ics = export(event, allDay, task)
        assertEquals(2, Regex("BEGIN:VEVENT").findAll(ics).count())
        assertEquals(1, Regex("BEGIN:VTODO").findAll(ics).count())
        assertEquals(1, Regex("BEGIN:VCALENDAR").findAll(ics).count())
    }

    @Test
    fun `every component carries a stamp and a unique id`() {
        val ics = export(event, task)
        assertEquals(2, Regex("DTSTAMP:20260902T091500Z").findAll(ics).count(), ics)
        assertTrue(ics.contains("UID:chain#0@latch.app"))
        assertTrue(ics.contains("UID:chain#2@latch.app"))
    }

    @Test
    fun `two exports of the same capture carry different ids`() {
        // A UID is what a calendar program uses to decide whether an import is a new item or a
        // replacement for one it already has. Two exports sharing a UID would have the second
        // silently overwrite the first, which is the failure `exportItems` mints a fresh chain
        // id to prevent — the item ids the *save* path uses are stable by design, and stable is
        // exactly wrong here.
        val first = exportItems(listOf(event), chainId = "export-1")
        val second = exportItems(listOf(event), chainId = "export-2")
        assertTrue(icsCalendar(first, "Asia/Kolkata", now).contains("UID:export-1#0@latch.app"))
        assertTrue(icsCalendar(second, "Asia/Kolkata", now).contains("UID:export-2#0@latch.app"))
    }
}
