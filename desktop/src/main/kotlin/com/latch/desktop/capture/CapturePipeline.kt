package com.latch.desktop.capture

import com.latch.desktop.ocr.OcrFailure
import com.latch.desktop.ocr.OcrOutcome
import com.latch.wire.WireCapture
import java.awt.image.BufferedImage

/**
 * A capture, as `:wire` needs to see one.
 *
 * The Windows client's own carrier. Android's `CapturedText` holds a `CaptureLayer` and a
 * `PageCoverage` from an Android library; this holds what a clipboard gave it. What they share
 * is [WireCapture], which is the three fields that reach a hash.
 */
data class DesktopCapture(
    override val text: String,
    override val preferredTitle: String? = null,
    override val ocrUsed: Boolean = false,
) : WireCapture

/** Why a hotkey press produced no capture. Named — the UI phrases it (NFR-402). */
enum class EmptyCapture {
    /** Nothing was selected, or the foreground application put nothing on the clipboard. */
    NOTHING_COPIED,

    /** An image was captured and the recogniser found no text in it. */
    NO_TEXT_IN_IMAGE,

    /**
     * FR-303 cannot run on this machine.
     *
     * The asymmetry with Android, and the reason it is a reason of its own: Android bundles
     * ML Kit's models and pays 12.83 MB for them, so recognition is a property of the app.
     * `Windows.Media.Ocr` reads only the language packs the machine has installed, so it is a
     * property of the machine — and a user whose phone reads a notice their desktop cannot is
     * owed the sentence that says which, and that Windows adds packs under Language settings.
     */
    NO_RECOGNISER,

    /** Recognition was attempted and failed. */
    RECOGNITION_FAILED,
}

sealed interface CaptureOutcome {
    data class Ready(val capture: DesktopCapture) : CaptureOutcome
    data class Nothing(val reason: EmptyCapture, val detail: String = "") : CaptureOutcome
}

/**
 * What the clipboard turned out to be worth capturing.
 *
 * Pure, with recognition passed in, so every branch is reachable from a JVM test — including
 * the three failures, which are the ones a user actually meets and the ones hardest to
 * arrange by hand.
 */
fun buildCapture(clip: Clip, recognise: (BufferedImage) -> OcrOutcome): CaptureOutcome = when (clip) {
    is Clip.Text ->
        if (clip.text.isBlank()) CaptureOutcome.Nothing(EmptyCapture.NOTHING_COPIED)
        else CaptureOutcome.Ready(DesktopCapture(text = clip.text, ocrUsed = false))

    is Clip.Image -> when (val recognised = recognise(clip.image)) {
        is OcrOutcome.Recognised -> CaptureOutcome.Ready(
            // FR-215's flag, and it carries further than it looks: `ocrUsed` is what makes
            // FR-509a take a title from the date's own row, and what makes FR-805b excerpt
            // rather than quote. Setting it wrongly changes what Google receives.
            DesktopCapture(text = recognised.text, ocrUsed = true)
        )
        is OcrOutcome.Failed -> CaptureOutcome.Nothing(
            when (recognised.reason) {
                OcrFailure.NO_TEXT -> EmptyCapture.NO_TEXT_IN_IMAGE
                OcrFailure.NO_RECOGNISER, OcrFailure.NO_WINRT -> EmptyCapture.NO_RECOGNISER
                else -> EmptyCapture.RECOGNITION_FAILED
            },
            recognised.detail,
        )
    }

    Clip.Empty -> CaptureOutcome.Nothing(EmptyCapture.NOTHING_COPIED)
}
