package com.latch.wire

import com.latch.core.model.Item
import com.latch.core.model.ItemType
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * FR-1005: `.ics` export for any item or chain.
 *
 * **RFC 5545, and it is a wire format**, so it gets the treatment §7.2 gets: every value's shape
 * decided here, the escaping written out, and the whole of it a pure function with tests. The
 * reader is somebody else's calendar program and there is no second chance to correct a file a
 * user has already emailed.
 *
 * **An exported item carries no §7.2 metadata, and that is a decision rather than an omission.**
 * `latch.source_hash` and `latch.item_key` are a cross-client contract between §4.1's three
 * clients writing to one Google account; they mean nothing to another calendar program, and
 * `X-` properties carrying them would put a digest of the user's own message into a file they
 * may send to somebody else. FR-1005 asks for an export of the *item*, and the item is its
 * title, its dates and its notes.
 *
 * **A task exports as a `VTODO` rather than as an all-day `VEVENT`.** The two are different
 * things in RFC 5545 and most calendar programs show them differently; exporting a to-do as a
 * day-long appointment would put a block on someone's calendar that Latch never created.
 */
fun icsCalendar(
    items: List<Item>,
    timeZone: String,
    /** Stamped on every component. Passed in rather than read, so the output is reproducible. */
    now: Instant = Instant.now(),
): String {
    val lines = buildList {
        add("BEGIN:VCALENDAR")
        add("VERSION:2.0")
        add("PRODID:-//Latch//Latch for Android//EN")
        // REQUEST would ask the reader to reply to an invitation. PUBLISH is what an export is.
        add("METHOD:PUBLISH")
        add("CALSCALE:GREGORIAN")
        items.forEach { addAll(component(it, timeZone, now)) }
        add("END:VCALENDAR")
    }
    // CRLF, which RFC 5545 requires. A file with bare newlines is read by most programs and
    // rejected by some, and "most" is not a property to ship a file format on.
    return lines.flatMap(::fold).joinToString("\r\n", postfix = "\r\n")
}

private fun component(item: Item, timeZone: String, now: Instant): List<String> = when (item.type) {
    ItemType.EVENT -> buildList {
        add("BEGIN:VEVENT")
        add("UID:${item.id}@latch.app")
        add("DTSTAMP:${stamp(now)}")
        add("SUMMARY:${escape(item.title)}")
        val start = requireNotNull(item.start) { "An exported event has no start" }
        val end = requireNotNull(item.end) { "An exported event has no end" }
        if (item.allDay) {
            // VALUE=DATE, and the end stays exclusive — which is what `ItemDrafts` already made
            // it for Google's all-day API, so no arithmetic happens here and none can go wrong.
            add("DTSTART;VALUE=DATE:${date(start)}")
            add("DTEND;VALUE=DATE:${date(end)}")
        } else {
            add("DTSTART;TZID=$timeZone:${dateTime(start)}")
            add("DTEND;TZID=$timeZone:${dateTime(end)}")
        }
        item.location?.takeIf { it.isNotBlank() }?.let { add("LOCATION:${escape(it)}") }
        item.notes?.takeIf { it.isNotBlank() }?.let { add("DESCRIPTION:${escape(it)}") }
        item.reminderMinutes.forEach { minutes ->
            add("BEGIN:VALARM")
            add("ACTION:DISPLAY")
            add("DESCRIPTION:${escape(item.title)}")
            add("TRIGGER:-PT${minutes}M")
            add("END:VALARM")
        }
        add("END:VEVENT")
    }

    ItemType.TASK -> buildList {
        add("BEGIN:VTODO")
        add("UID:${item.id}@latch.app")
        add("DTSTAMP:${stamp(now)}")
        add("SUMMARY:${escape(item.title)}")
        // An undated task exports with no DUE at all rather than with one invented, which is
        // design principle 1 reaching as far as a file format.
        item.dueDate?.let { add("DUE;VALUE=DATE:${it.format(BASIC_DATE)}") }
        item.notes?.takeIf { it.isNotBlank() }?.let { add("DESCRIPTION:${escape(it)}") }
        add("END:VTODO")
    }
}

/**
 * RFC 5545 §3.3.11: `\`, `;`, `,` and newlines are escaped in a TEXT value.
 *
 * The order matters and is the one place this can go wrong: the backslash has to be escaped
 * **first**, or the backslashes this function itself introduces get escaped a second time.
 */
internal fun escape(text: String): String = text
    .replace("\\", "\\\\")
    .replace(";", "\\;")
    .replace(",", "\\,")
    .replace("\r\n", "\\n")
    .replace("\n", "\\n")
    .replace("\r", "\\n")

/**
 * RFC 5545 §3.1: a content line is folded at 75 **octets**, and a continuation begins with one
 * space.
 *
 * Octets rather than characters, which is why this counts UTF-8 bytes and never splits a
 * character across a fold — a Devanagari title is three bytes per character, and a fold that
 * counted characters would break a multi-byte sequence in half and produce a file no reader can
 * decode. That is the whole reason this function exists rather than a `chunked(75)`.
 */
internal fun fold(line: String): List<String> {
    if (line.toByteArray(Charsets.UTF_8).size <= FOLD_OCTETS) return listOf(line)

    val folded = mutableListOf<String>()
    val current = StringBuilder()
    var octets = 0
    // The first line may use all 75; a continuation spends one on its leading space.
    var limit = FOLD_OCTETS

    line.forEach { character ->
        val size = character.toString().toByteArray(Charsets.UTF_8).size
        if (octets + size > limit) {
            folded += if (folded.isEmpty()) current.toString() else " $current"
            current.setLength(0)
            octets = 0
            limit = FOLD_OCTETS - 1
        }
        current.append(character)
        octets += size
    }
    if (current.isNotEmpty()) folded += if (folded.isEmpty()) current.toString() else " $current"
    return folded
}

private const val FOLD_OCTETS = 75

private val BASIC_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
private val BASIC_DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")

private fun date(at: LocalDateTime) = at.toLocalDate().format(BASIC_DATE)
private fun dateTime(at: LocalDateTime) = at.format(BASIC_DATE_TIME)

/** UTC, with the `Z`, which is what `DTSTAMP` requires. */
private fun stamp(now: Instant) =
    LocalDateTime.ofInstant(now.truncatedTo(ChronoUnit.SECONDS), java.time.ZoneOffset.UTC)
        .format(BASIC_DATE_TIME) + "Z"

/**
 * The items, re-identified for **this** export.
 *
 * A `UID` is what a calendar program uses to decide whether an import is a new item or a
 * replacement for one it already holds. The item ids a save uses are derived from the chain id
 * and are stable by design — `"$chainId#$index"`, so a redraft of the same save produces the
 * same ids — and stable is exactly wrong here: two exports of the same capture would carry the
 * same UIDs and the second import would silently overwrite the first.
 *
 * So an export mints its own [chainId] and the ids follow it. Nothing else about the items
 * changes; this is an identity for a file, not for anything Latch stores.
 */
fun exportItems(items: List<Item>, chainId: String): List<Item> =
    items.mapIndexed { index, item -> item.copy(id = "$chainId#$index") }

/**
 * A filename for the export.
 *
 * Derived from the first item's title so a user with several exports in their downloads can
 * tell them apart, and stripped to characters every filesystem this file might reach accepts —
 * an `.ics` is a thing people mail to each other, and the receiving side is not Android.
 */
fun icsFileName(items: List<Item>): String {
    val stem = items.firstOrNull()?.title.orEmpty()
        .replace(Regex("[^A-Za-z0-9 ]"), "")
        .trim()
        .replace(' ', '-')
        .take(40)
        .ifEmpty { "latch-export" }
    return "$stem.ics"
}
