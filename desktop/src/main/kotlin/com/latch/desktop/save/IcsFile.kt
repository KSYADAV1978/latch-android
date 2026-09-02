package com.latch.desktop.save

import com.latch.core.model.Item
import com.latch.wire.exportItems
import com.latch.wire.icsCalendar
import com.latch.wire.icsFileName
import java.io.File
import java.nio.charset.StandardCharsets
import java.time.Instant

/**
 * FR-1005 on the desktop.
 *
 * The file's bytes are `:wire`'s — CRLF, folding at 75 **octets** so a Devanagari title is not
 * cut through a UTF-8 sequence, and a `VTODO` for a task rather than an all-day `VEVENT`. What
 * this file adds is where it lands.
 *
 * **Downloads rather than a temporary directory**, which is the opposite of the Android
 * client's choice and for a reason that does not carry across. There the file goes to a cache
 * subdirectory behind a `FileProvider` because it is handed to another application through the
 * share sheet and the system clears that directory anyway. On a desktop there is no share
 * sheet: the user is given a file, and a file the user is given belongs where they look for
 * files. It is **not** deleted afterwards, because deleting a document somebody exported would
 * be losing their data to tidy up.
 *
 * **An export mints fresh item ids**, which `exportItems` does. A `UID` is how a reader tells a
 * new item from a replacement, and the save path's ids are stable by design — so two exports of
 * one capture would have the second import silently overwrite the first.
 */
object IcsFile {

    fun write(
        items: List<Item>,
        chainId: String,
        timeZone: String,
        now: Instant = Instant.now(),
        /**
         * Where the file lands. Defaults to Downloads and is a parameter so a test can write
         * somewhere else — a suite that put files in the user's own Downloads folder would be
         * doing to this machine what the instrumented suite once did to a real phone.
         */
        directory: File = downloadsDirectory(),
    ): File? {
        if (items.isEmpty()) return null
        val exported = exportItems(items, chainId)
        val text = icsCalendar(exported, timeZone, now)
        val file = uniqueIn(directory, icsFileName(exported))
        return runCatching {
            file.parentFile?.mkdirs()
            // Written as UTF-8 explicitly. The default charset on a Windows JVM is not always
            // UTF-8, and a file that a calendar program cannot decode is the failure FR-1005
            // is most likely to have: nothing opens it, and nothing says why.
            file.writeBytes(text.toByteArray(StandardCharsets.UTF_8))
            file
        }.getOrNull()
    }

    /** `%USERPROFILE%\Downloads`, or the home directory where there is no such folder. */
    internal fun downloadsDirectory(): File {
        val home = File(System.getProperty("user.home"))
        val downloads = File(home, "Downloads")
        return if (downloads.isDirectory) downloads else home
    }
}

/**
 * A name that is not already taken.
 *
 * Exporting the same capture twice must produce two files rather than one overwriting the
 * other — the same reason `exportItems` mints fresh ids, one level up: a user who exported,
 * edited and exported again has two things and should still have both.
 */
internal fun uniqueIn(directory: File, name: String): File {
    val candidate = File(directory, name)
    if (!candidate.exists()) return candidate
    val stem = name.substringBeforeLast('.')
    val extension = name.substringAfterLast('.', "ics")
    var counter = 2
    while (counter < 1000) {
        val next = File(directory, stem + "-" + counter + "." + extension)
        if (!next.exists()) return next
        counter++
    }
    return File(directory, stem + "-" + System.currentTimeMillis() + "." + extension)
}
