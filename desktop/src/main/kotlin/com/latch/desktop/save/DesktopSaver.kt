package com.latch.desktop.save

import com.latch.core.model.CaptureLayer
import com.latch.core.model.CaptureSource
import com.latch.core.model.Item
import com.latch.core.model.ItemType
import com.latch.desktop.store.DesktopDefaults
import com.latch.google.CalendarApi
import com.latch.google.EventWrite
import com.latch.google.GoogleFailure
import com.latch.google.GoogleRejected
import com.latch.google.GoogleUnreachable
import com.latch.google.TaskWrite
import com.latch.google.TasksApi
import com.latch.wire.RemoteMetadata
import com.latch.wire.WireCapture
import com.latch.wire.WireDestination
import com.latch.wire.bodyWithNote
import com.latch.wire.dateSpans
import com.latch.wire.itemKeyOf
import com.latch.wire.itemKeyTitle
import com.latch.wire.sourceBlock
import com.latch.wire.sourceHashOf
import java.time.Instant

/** What a save did. Named, so the tray phrases it (NFR-402). */
sealed interface SaveResult {
    data class Written(val count: Int, val ids: List<Pair<ItemType, String>>) : SaveResult

    /** FR-803: this message is already in the account, and nothing was written again. */
    data object AlreadySaved : SaveResult

    data class Failed(val reason: SaveFailure, val detail: String = "") : SaveResult
}

enum class SaveFailure { NOT_SIGNED_IN, NO_DESTINATION, OFFLINE, REFUSED, NOTHING_TO_WRITE }

/**
 * FR-801 to FR-803 on the desktop, through the client `:app` uses.
 *
 * **There is almost nothing here, and that is the result rather than a gap.** The metadata,
 * the hashes, the titles, the dates and the request bodies are all `:wire`'s and `:google`'s,
 * so what this file adds is the order the calls go in. A save on this machine and a save on
 * the phone put the same bytes in front of Google because they run the same code, which is
 * what AC-07 needs of two clients and what §7.2's conformance vectors could only have checked
 * after the fact.
 *
 * **What is deliberately not here yet**, so nothing pretends otherwise: FR-806's queue, so an
 * offline save is reported rather than held; FR-804's reschedule offer, which needs a surface
 * to ask on; and FR-807's undo. Each is owed and each is named in its own failure or in the
 * backlog rather than silently absent.
 */
class DesktopSaver(
    private val calendar: CalendarApi,
    private val tasks: TasksApi,
    /** The zone an event's wall-clock times are read in. The machine's, unless told otherwise. */
    private val zone: String = java.time.ZoneId.systemDefault().id,
) {
    suspend fun save(
        captured: WireCapture,
        /**
         * The parse the items came from.
         *
         * Passed in rather than re-derived here, because FR-515 makes a parse a function of the
         * instant it was made at and re-parsing would run the clock again — a capture confirmed
         * at 23:59 would derive its key from a different reading of "tomorrow" than the one the
         * user saw.
         */
        result: com.latch.parser.ParseResult,
        items: List<Item>,
        defaults: DesktopDefaults,
        chainId: String,
        capturedAt: Instant = Instant.now(),
    ): SaveResult {
        if (items.isEmpty()) return SaveResult.Failed(SaveFailure.NOTHING_TO_WRITE)

        val sourceHash = sourceHashOf(captured.text)
        val metadata = RemoteMetadata(
            sourceHash = sourceHash,
            // FR-804's identity, and the reason it is not the source hash: a reschedule *is*
            // different source text, so a key derived from the text could never match the item
            // it is meant to move. `itemKeyTitle` blanks every date span out of the title.
            itemKey = itemKeyOf(itemKeyTitle(captured, result)),
            chainId = chainId,
            capturedAt = capturedAt,
            sourceApp = null,
        )

        // FR-805, and FR-805b where the capture was recognised from an image. Composed once,
        // here, exactly as `CaptureSaver` composes it on the phone — and by the same function,
        // so the two cannot describe one message differently.
        val body = sourceBlock(
            source = CaptureSource(
                layer = CaptureLayer.SHARE_SHEET,
                ocrUsed = captured.ocrUsed,
                appId = null,
            ),
            sourceText = captured.text,
            // The same spans `itemKeyTitle` blanks: FR-805b excerpts around what §7.2 removes.
            dateSpans = dateSpans(result),
            sourceLink = null,
        )

        // FR-803, once per capture and before anything is written. The chain shares one hash,
        // so one question covers every item in it.
        val existing = runCatching { findExisting(sourceHash, defaults) }
            .getOrElse { return failureFor(it) }
        if (existing) return SaveResult.AlreadySaved

        val written = mutableListOf<Pair<ItemType, String>>()
        items.forEach { item ->
            val written_id = runCatching { write(item, metadata, body, defaults) }
                .getOrElse {
                    // SRS 1.24's shape: what was written stays written and is reported, rather
                    // than a partial chain reported as a total failure.
                    return if (written.isEmpty()) failureFor(it)
                    else SaveResult.Written(written.size, written)
                }
            written += item.type to written_id
        }
        return SaveResult.Written(written.size, written)
    }

    private suspend fun findExisting(sourceHash: String, defaults: DesktopDefaults): Boolean {
        if (calendar.findEventBySourceHash(defaults.calendarId, sourceHash).found) return true
        // `scanCapped` is the Tasks API admitting it read ten pages and stopped. It is not
        // "no duplicate": `DuplicateSearch` keeps them apart precisely so a caller cannot read
        // one as the other, and Android cures it with a local index this client does not have
        // yet. Until it does, a capped scan behaves as "not found" and the residual duplicate
        // risk is the one SRS section 7.2 already records for tasks.
        return tasks.findTaskBySourceHash(defaults.taskListId, sourceHash, null).found
    }

    private suspend fun write(
        item: Item,
        metadata: RemoteMetadata,
        body: String,
        defaults: DesktopDefaults,
    ): String = when (item.type) {
        ItemType.EVENT -> calendar.insertEvent(
            defaults.calendarId,
            EventWrite(
                summary = item.title,
                description = bodyWithNote(item.notes, body),
                location = item.location,
                start = requireNotNull(item.start) { "an event with no start reached the write" },
                end = requireNotNull(item.end) { "an event with no end reached the write" },
                allDay = item.allDay,
                timeZone = zone,
                reminderMinutes = item.reminderMinutes,
                metadata = metadata,
            ),
        )
        ItemType.TASK -> tasks.insertTask(
            defaults.taskListId,
            TaskWrite(
                title = item.title,
                // FR-510: "with the past date recorded in the notes".
                notes = bodyWithNote(item.notes, body),
                due = item.dueDate,
                metadata = metadata,
            ),
        )
    }

    private fun failureFor(error: Throwable): SaveResult = when (error) {
        is GoogleUnreachable -> SaveResult.Failed(SaveFailure.OFFLINE, error.message.orEmpty())
        is GoogleRejected -> SaveResult.Failed(SaveFailure.REFUSED, error.message.orEmpty())
        is GoogleFailure -> SaveResult.Failed(SaveFailure.REFUSED, error.message.orEmpty())
        else -> SaveResult.Failed(SaveFailure.REFUSED, error.message.orEmpty())
    }

}
/** Convenience for the wiring: the destination as `:wire` wants it. */
fun DesktopDefaults.toWireDestination(): WireDestination =
    WireDestination(calendarId = calendarId, taskListId = taskListId)

