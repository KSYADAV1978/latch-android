package com.latch.desktop.inbox

import com.latch.core.model.InboxCapture
import com.latch.core.model.InboxStatus
import com.latch.desktop.store.WindowsSecrets
import java.io.File
import java.time.Instant

/**
 * FR-701 to FR-705 on Windows: the Capture Inbox, on disk, under DPAPI.
 *
 * **Nothing here has reached Google** (FR-703). The file is the phone's SQLite table one
 * platform over — same rows, same rules, a different disk, which is the division `:wire` and
 * `:google` draw everywhere else: what a capture *means* is shared, where a client keeps it is
 * not.
 *
 * **One record per line, each encrypted on its own**, exactly as the write queue is. An Inbox
 * row holds the user's captured text and a row the operating system will not decrypt must cost
 * that one capture and not the rest; a single blob loses all of them together.
 *
 * ---
 *
 * **An unreadable row is KEPT, and this client and the phone differ here on purpose.**
 *
 * `SqliteCaptureInbox` deletes a row it cannot decode, and gives a reason: keeping it "would
 * mean a count that never goes down over a row that can never be opened". That reasoning is
 * about the *count*, and it is right about the count. But the thing deleted is a capture that
 * exists nowhere else — FR-703 guarantees precisely that nothing in here has reached Google —
 * so deleting it is the loss the write queue's own note calls the one thing NFR-302 forbids.
 *
 * The two are not actually in tension once the count is separated from the storage: the row
 * **stays** in the file, and it is counted in [InboxStatus.unreadable] rather than in
 * [InboxStatus.due], so FR-704's count still goes down and nothing is thrown away. That is what
 * this store does.
 *
 * **The phone does this too, as of SRS 1.70.** It was recorded as owed rather than changed in
 * the slice that wrote this file, because altering what a live device does with a user's held
 * captures is not a side effect a Windows slice should have. The two stores now follow one
 * reading and `InboxStatus` is shared.
 */
class DesktopInbox(
    private val file: File,
    private val secrets: WindowsSecrets = WindowsSecrets(),
) {
    @Synchronized
    fun add(capture: InboxCapture) {
        val (rows, opaque) = readAll()
        writeAll(rows + capture, opaque)
    }

    /** FR-702: assign a date, edit, snooze. The whole row is written back. */
    @Synchronized
    fun update(capture: InboxCapture) {
        val (rows, opaque) = readAll()
        writeAll(rows.map { if (it.id == capture.id) capture else it }, opaque)
    }

    /** FR-702: discard, and what a save does once the item has reached Google. */
    @Synchronized
    fun discard(id: String) {
        val (rows, opaque) = readAll()
        writeAll(rows.filterNot { it.id == id }, opaque)
    }

    /** Every row, oldest first, snoozed ones included. FR-705's review reads this. */
    @Synchronized
    fun all(): List<InboxCapture> = readAll().first

    /** FR-702's list: what the user is being asked to deal with now. */
    @Synchronized
    fun due(now: Instant): List<InboxCapture> = readAll().first.filter { it.isDue(now) }

    @Synchronized
    fun find(id: String): InboxCapture? = readAll().first.firstOrNull { it.id == id }

    @Synchronized
    fun status(now: Instant): InboxStatus {
        val (rows, opaque) = readAll()
        return InboxStatus(
            due = rows.count { it.isDue(now) },
            snoozed = rows.count { !it.isDue(now) },
            unreadable = opaque.size,
        )
    }

    /** NFR-205. Everything, gone — including the lines this build could not read. */
    @Synchronized
    fun clear() {
        file.delete()
    }

    /**
     * Decodable rows oldest first, and the raw lines that were not decodable and are being kept.
     *
     * Oldest first because FR-705 wants an aged capture surfacing rather than sinking under new
     * ones — the same order the phone's query takes, decided here rather than left to whatever
     * order the file happens to be in.
     */
    private fun readAll(): Pair<List<InboxCapture>, List<String>> {
        val empty = emptyList<InboxCapture>() to emptyList<String>()
        if (!file.isFile) return empty
        val lines = runCatching { file.readLines() }.getOrElse { return empty }
        if (lines.firstOrNull()?.trim() != INBOX_FILE_VERSION) return empty

        val rows = mutableListOf<InboxCapture>()
        val opaque = mutableListOf<String>()
        // One crossing of the DPAPI bridge for the whole file. FR-704 asks for an unobtrusive
        // count and one PowerShell launch per row would have made it cost a second a row.
        val ciphertexts = lines.drop(1).filter { it.isNotBlank() }.map { it.trim() }
        secrets.unprotectAll(ciphertexts).forEachIndexed { index, plaintext ->
            val decoded = plaintext?.let(::decodeInboxCapture)
            if (decoded != null) rows += decoded else opaque += ciphertexts[index]
        }
        return rows.sortedBy { it.capturedAt } to opaque
    }

    private fun writeAll(rows: List<InboxCapture>, opaque: List<String>) {
        if (rows.isEmpty() && opaque.isEmpty()) {
            file.delete()
            return
        }
        val ciphertexts = secrets.protectAll(rows.map(::encodeInboxCapture))
        val body = buildList {
            add(INBOX_FILE_VERSION)
            ciphertexts.forEach { ciphertext ->
                add(
                    ciphertext
                        ?: throw IllegalStateException("Windows would not encrypt a held capture"),
                )
            }
            // Carried through untouched. This build cannot read them and must not lose them.
            addAll(opaque)
        }
        file.parentFile?.mkdirs()
        // Written beside the target and moved into place, so a process that dies mid-write
        // leaves the previous Inbox rather than a truncated one. Every row in here is a capture
        // that exists nowhere else.
        val staging = File(file.parentFile, file.name + ".new")
        staging.writeText(body.joinToString("\n"))
        if (!staging.renameTo(file)) {
            file.delete()
            check(staging.renameTo(file)) { "could not replace " + file }
        }
    }
}

const val INBOX_FILE_VERSION: String = "latch-inbox/1"
