package com.latch.android.capture

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.latch.core.model.Item
import com.latch.data.icsCalendar
import com.latch.data.exportItems
import com.latch.data.icsFileName
import java.io.File
import java.util.UUID

/**
 * FR-1005: `.ics` export for any item or chain.
 *
 * **The file goes through a `FileProvider` rather than out as text.** An `.ics` pasted into an
 * `EXTRA_TEXT` is not a calendar file to the app that receives it — nothing opens it, which is
 * the whole point of the requirement — and a `file:` URI has been refused by the platform since
 * Android 7. The provider grants one read to whichever app the user picks and nothing more.
 *
 * **The file is written to the cache directory.** It is a copy of data the user already has,
 * made for the purpose of handing it to something else; keeping it would be a second store of
 * their captures for no one's benefit, and the cache is the one directory the system will clear
 * on their behalf. NFR-205's deletion does not enumerate it for that reason and the SRS row
 * says so — but the directory is cleared here on every export, so at most one file is ever
 * waiting.
 */
fun shareAsIcs(context: Context, items: List<Item>, timeZone: String): Intent? {
    if (items.isEmpty()) return null

    val directory = File(context.cacheDir, EXPORT_DIRECTORY)
    // Cleared each time rather than accumulating: a directory of every item a user has ever
    // exported is a store of their captures that nothing else in this app would know about.
    directory.deleteRecursively()
    if (!directory.mkdirs()) return null

    // A fresh identity for this file. See `exportItems`: the ids a save uses are stable, and
    // two exports carrying the same UIDs would have the second import overwrite the first.
    val identified = exportItems(items, UUID.randomUUID().toString())

    val file = File(directory, icsFileName(identified))
    return runCatching {
        file.writeText(icsCalendar(identified, timeZone))
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.exports", file)
        Intent(Intent.ACTION_SEND).apply {
            type = ICS_MIME
            putExtra(Intent.EXTRA_STREAM, uri)
            // The one grant this hands out, for one launch of one app the user chose.
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }.getOrNull()
}

private const val EXPORT_DIRECTORY = "exports"

/** The registered type for iCalendar. `text/calendar` is what every reader filters on. */
const val ICS_MIME = "text/calendar"
