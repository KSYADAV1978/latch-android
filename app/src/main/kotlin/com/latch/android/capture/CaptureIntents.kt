package com.latch.android.capture

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.getSystemService
import com.latch.core.model.CaptureLayer
import com.latch.ocr.PageCoverage

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
    /**
     * FR-802's source application, where the platform discloses it. Filled in by the
     * activity from `getReferrer()`, not here — reading the intent alone is this file's
     * whole contract, and the referrer is not in the intent.
     */
    val appId: String? = null,
    /**
     * FR-215: [text] was recognised from an image or a PDF rather than read as text.
     *
     * Reaches `CaptureSource.ocrUsed`, and from there FR-805b — an OCR capture's description
     * carries an extract around the matched dates rather than everything the recogniser saw.
     */
    val ocrUsed: Boolean = false,
    /**
     * FR-207: how much of a PDF was read, where this capture was one. Null for text and for
     * images, which have no pages to cap.
     */
    val pages: PageCoverage? = null,
)

/**
 * What an incoming intent turned out to be.
 *
 * Text is resolved here and now; an image or a PDF names work that has to happen off the main
 * thread and cannot be. That distinction is the whole reason this type exists rather than
 * `toCapturedText` simply returning null for an image: NFR-102 requires the confirmation UI
 * to render before the content is ready, and it can only do that if the activity can tell
 * "nothing was shared" apart from "something was shared and is still being read".
 */
sealed interface CaptureRequest {
    /** Ready synchronously, exactly as every capture was before FR-215. */
    data class Ready(val captured: CapturedText) : CaptureRequest

    /** FR-215: an image to recognise. [layer] is which capture layer delivered it. */
    data class Image(
        val uri: Uri,
        val layer: CaptureLayer,
        val preferredTitle: String? = null,
    ) : CaptureRequest

    /** FR-207: a PDF to render and recognise. */
    data class Pdf(
        val uri: Uri,
        val layer: CaptureLayer,
        val preferredTitle: String? = null,
    ) : CaptureRequest

    /**
     * Nothing usable arrived. [fromEmptyClipboard] separates FR-213's own failure mode, and
     * [fromLapsedOffer] FR-208's: a notification offer whose text is gone because the process
     * ended or because it was already answered. Both are ordinary and neither is an error, but
     * they ask different things of the user, so they are told apart.
     */
    data class Nothing(
        val fromEmptyClipboard: Boolean = false,
        val fromLapsedOffer: Boolean = false,
    ) : CaptureRequest
}

/** Marks an intent that the Quick Settings tile started, so the activity reads the clipboard. */
const val EXTRA_READ_CLIPBOARD = "com.latch.android.extra.READ_CLIPBOARD"

/**
 * FR-208/FR-211: the key of a notification capture held in memory.
 *
 * **A key and never the text.** A posted notification's `PendingIntent` extras are held by the
 * system's notification manager, which is not this app's memory — so putting the message there
 * would be the persistent storage FR-210 and NFR-206 forbid, arrived at without anyone writing
 * a file. `NotificationCaptureHolder` holds the text and this names it.
 */
const val EXTRA_NOTIFICATION_KEY = "com.latch.android.extra.NOTIFICATION_KEY"

/**
 * FR-201/FR-203 (layer 1), FR-205/FR-206/FR-207 (layer 2) and FR-213 (layer 4).
 *
 * Each layer must work if the others are disabled, so this reads the intent alone and never
 * consults app state. It also does no I/O: deciding that an intent carries an image is
 * reading its type and its extras, and reading the image itself is the caller's problem —
 * which is what keeps this function testable and the main thread free.
 */
fun Intent.toCaptureRequest(
    context: Context,
    /**
     * FR-208: reads a held notification capture by its key. Passed in because the store is the
     * application's memory and this file's whole contract is reading the intent alone.
     */
    notificationText: (String) -> String? = { null },
): CaptureRequest = when (action) {
    Intent.ACTION_PROCESS_TEXT -> {
        val text = getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
        text?.takeIf { it.isNotBlank() }?.let {
            CaptureRequest.Ready(
                CapturedText(
                    text = it,
                    layer = CaptureLayer.TEXT_SELECTION,
                    readOnly = getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false),
                )
            )
        } ?: CaptureRequest.Nothing()
    }

    Intent.ACTION_SEND -> sharedContent()

    else -> when {
        getBooleanExtra(EXTRA_READ_CLIPBOARD, false) ->
            readClipboard(context)?.let(CaptureRequest::Ready)
                ?: CaptureRequest.Nothing(fromEmptyClipboard = true)

        // FR-208. The text is not in this intent and never was; `notificationText` is a
        // function of the process's own memory, which is what FR-210 requires.
        getStringExtra(EXTRA_NOTIFICATION_KEY) != null ->
            getStringExtra(EXTRA_NOTIFICATION_KEY)
                ?.let(notificationText)
                ?.takeIf { it.isNotBlank() }
                ?.let {
                    CaptureRequest.Ready(
                        CapturedText(text = it, layer = CaptureLayer.NOTIFICATION)
                    )
                }
                // The offer was answered already, or the process died since. Both are the same
                // to the user and both are honestly "there is nothing here any more".
                ?: CaptureRequest.Nothing(fromLapsedOffer = true)

        else -> CaptureRequest.Nothing()
    }
}

/**
 * FR-205 and FR-207: the share sheet delivers text, images and PDFs through one action.
 *
 * **Text wins over a stream where both are present**, which is common: a browser or a mail
 * client sharing a page often attaches both a URL and a preview image. The text is what the
 * user meant to send, and recognising the preview image instead would read the picture of an
 * article rather than its address.
 */
private fun Intent.sharedContent(): CaptureRequest {
    val subject = getStringExtra(Intent.EXTRA_SUBJECT)?.takeIf(String::isNotBlank)

    getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }?.let {
        return CaptureRequest.Ready(
            CapturedText(text = it, layer = CaptureLayer.SHARE_SHEET, preferredTitle = subject)
        )
    }

    val stream = streamUri() ?: return CaptureRequest.Nothing()
    val mime = type.orEmpty()
    return when {
        mime == PDF_MIME -> CaptureRequest.Pdf(stream, CaptureLayer.SHARE_SHEET, subject)
        mime.startsWith("image/") -> CaptureRequest.Image(stream, CaptureLayer.SHARE_SHEET, subject)
        // A type the manifest does not advertise, or none at all. Refused rather than
        // guessed at: a share target that accepts a file it cannot read is worse than one
        // that does not appear.
        else -> CaptureRequest.Nothing()
    }
}

@Suppress("DEPRECATION")
private fun Intent.streamUri(): Uri? =
    // getParcelableExtra(String, Class) is API 33; minSdk here is 26. The typed overload is
    // the one to move to when minSdk rises, not a second code path to carry now.
    getParcelableExtra(Intent.EXTRA_STREAM) as? Uri

const val PDF_MIME = "application/pdf"

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
