package com.latch.android.capture

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.core.content.getSystemService
import com.latch.core.model.CaptureLayer

/**
 * Text handed to the app by one of the capture layers (§5.2), with where it came from.
 *
 * [preferredTitle] carries FR-206: where the sending app supplies a subject line — typical
 * of mail clients — it is the better title than the first line of the body.
 */
data class CapturedText(
    val text: String,
    val layer: CaptureLayer,
    val preferredTitle: String? = null,
    /** FR-203: true when the host will not accept modified text back. */
    val readOnly: Boolean = true,
)

/** Marks an intent that the Quick Settings tile started, so the activity reads the clipboard. */
const val EXTRA_READ_CLIPBOARD = "com.latch.android.extra.READ_CLIPBOARD"

/**
 * FR-201/FR-203 (layer 1), FR-205/FR-206 (layer 2) and FR-213 (layer 4).
 *
 * Each layer must work if the others are disabled, so this reads the intent alone and never
 * consults app state.
 */
fun Intent.toCapturedText(context: Context): CapturedText? = when (action) {
    Intent.ACTION_PROCESS_TEXT -> {
        val text = getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
        text?.takeIf { it.isNotBlank() }?.let {
            CapturedText(
                text = it,
                layer = CaptureLayer.TEXT_SELECTION,
                readOnly = getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false),
            )
        }
    }

    Intent.ACTION_SEND -> {
        val text = getStringExtra(Intent.EXTRA_TEXT)
        text?.takeIf { it.isNotBlank() }?.let {
            CapturedText(
                text = it,
                layer = CaptureLayer.SHARE_SHEET,
                preferredTitle = getStringExtra(Intent.EXTRA_SUBJECT)?.takeIf(String::isNotBlank),
            )
        }
    }

    else -> if (getBooleanExtra(EXTRA_READ_CLIPBOARD, false)) readClipboard(context) else null
}

/**
 * FR-213/FR-214: the clipboard is read here, in a foreground activity. Android 10 and later
 * refuse the read to anything in the background, which is why the tile has to bring this
 * window up first rather than capturing silently (§8.4).
 */
private fun readClipboard(context: Context): CapturedText? {
    val clipboard = context.getSystemService<ClipboardManager>() ?: return null
    val text = clipboard.primaryClip
        ?.takeIf { it.itemCount > 0 }
        ?.getItemAt(0)
        ?.coerceToText(context)
        ?.toString()

    return text?.takeIf { it.isNotBlank() }?.let {
        CapturedText(text = it, layer = CaptureLayer.QUICK_TILE)
    }
}
