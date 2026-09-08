package com.latch.desktop.queue

import com.latch.desktop.store.WindowsSecrets
import java.io.File

/** How much is waiting, for FR-806's count in the tray. */
data class QueueStatus(
    val waiting: Int,
    val givenUp: Int,
    val unreadable: Int,
    /**
     * FR-806a: at least one waiting entry is held for a **sign-in** (SRS 1.192).
     *
     * Only a *waiting* entry counts, which is Android's reading of the same field: one already
     * given up on is not going to move whatever the user does, so offering a sign-in for it
     * would be asking for something that changes nothing.
     */
    val needsSignIn: Boolean = false,
) {
    val total: Int get() = waiting + givenUp
}

/**
 * FR-806's queue, on disk, under DPAPI.
 *
 * **Each entry is encrypted on its own**, which is the shape `SecretFile` already uses and
 * matters more here than anywhere else in this client: an entry holds a capture that exists
 * nowhere else, so one record the operating system will not decrypt must cost that one capture
 * and not the rest. A single encrypted blob loses every queued capture together.
 *
 * **A record that will not decode is kept, not deleted.** Every other store in this project
 * drops an unreadable record, and that is right where dropping means "setup runs again". Here
 * it means losing something the user believes they have saved, which NFR-302 forbids outright.
 * So an undecodable line stays in the file, is counted in [QueueStatus.unreadable] so the tray
 * can say the number, and is left for a future version that might understand it.
 *
 * **The file is written beside itself and moved into place.** A process killed mid-write must
 * leave the previous queue rather than a truncated one.
 */
class WriteQueue(
    private val file: File,
    private val secrets: WindowsSecrets = WindowsSecrets(),
) {
    @Synchronized
    fun add(entry: QueuedWrite) {
        val (entries, opaque) = readAll()
        writeAll(entries + entry, opaque)
    }

    @Synchronized
    fun replace(entry: QueuedWrite) {
        val (entries, opaque) = readAll()
        writeAll(entries.map { if (it.id == entry.id) entry else it }, opaque)
    }

    @Synchronized
    fun remove(id: String) {
        val (entries, opaque) = readAll()
        writeAll(entries.filterNot { it.id == id }, opaque)
    }

    @Synchronized
    fun entries(): List<QueuedWrite> = readAll().first

    @Synchronized
    fun status(): QueueStatus {
        val (entries, opaque) = readAll()
        return QueueStatus(
            waiting = entries.count { !it.givenUp },
            givenUp = entries.count { it.givenUp },
            unreadable = opaque.size,
            needsSignIn = entries.any { !it.givenUp && it.needsSignIn },
        )
    }

    /** FR-806's manual retry: a given-up entry becomes due again. */
    @Synchronized
    fun reviveAll(now: java.time.Instant) {
        val (entries, opaque) = readAll()
        writeAll(
            entries.map {
                if (!it.givenUp) it.copy(nextAttemptAt = now)
                // The attempt count goes back to zero with it. Leaving it would put the revived
                // entry straight back at the half-hour ceiling, so "Retry now" would do nothing
                // a user could see — which is worse than not offering it.
                else it.copy(givenUp = false, attempts = 0, nextAttemptAt = now)
            },
            opaque,
        )
    }

    /** NFR-205. Everything, gone — including the lines this build could not read. */
    @Synchronized
    fun clear() {
        file.delete()
    }

    /** Decodable entries, and the raw lines that were not decodable and are being kept. */
    private fun readAll(): Pair<List<QueuedWrite>, List<String>> {
        if (!file.isFile) return emptyList<QueuedWrite>() to emptyList()
        val lines = runCatching { file.readLines() }.getOrElse { return emptyList<QueuedWrite>() to emptyList() }
        if (lines.firstOrNull()?.trim() != QUEUE_FILE_VERSION) return emptyList<QueuedWrite>() to emptyList()

        val entries = mutableListOf<QueuedWrite>()
        val opaque = mutableListOf<String>()
        // One crossing of the DPAPI bridge for the whole file, not one per entry. The
        // per-record isolation is unchanged — the bridge answers a line per line, so an entry
        // this machine's key cannot open still comes back alone and is still kept.
        val ciphertexts = lines.drop(1).filter { it.isNotBlank() }.map { it.trim() }
        secrets.unprotectAll(ciphertexts).forEachIndexed { index, plaintext ->
            val decoded = plaintext?.let(::decodeQueuedWrite)
            if (decoded != null) entries += decoded else opaque += ciphertexts[index]
        }
        return entries to opaque
    }

    private fun writeAll(entries: List<QueuedWrite>, opaque: List<String>) {
        if (entries.isEmpty() && opaque.isEmpty()) {
            file.delete()
            return
        }
        val ciphertexts = secrets.protectAll(entries.map { it.encode() })
        val body = buildList {
            add(QUEUE_FILE_VERSION)
            ciphertexts.forEach { ciphertext ->
                add(
                    ciphertext
                        ?: throw IllegalStateException("Windows would not encrypt a queued capture"),
                )
            }
            // Carried through untouched. This build cannot read them and must not lose them.
            addAll(opaque)
        }
        file.parentFile?.mkdirs()
        val staging = File(file.parentFile, file.name + ".new")
        staging.writeText(body.joinToString("\n"))
        if (!staging.renameTo(file)) {
            file.delete()
            check(staging.renameTo(file)) { "could not replace " + file }
        }
    }
}

const val QUEUE_FILE_VERSION: String = "latch-queue/1"
