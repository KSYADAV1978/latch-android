package com.latch.android.cards

import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * FR-1226: the card photograph as the contact's photo, and where it has to sit.
 *
 * **The geometry is here and the bitmap work is not**, for the reason every decision in this
 * pillar is: `Bitmap` and `Canvas` are throwing stubs under JVM unit tests, and a rule about how
 * much of somebody's card survives a crop is worth exactly as much as the test that checks it.
 */

/**
 * The square canvas a contact photo is drawn onto.
 *
 * 720 is comfortably above what any Google surface renders and far below the People API's own
 * limit — the encoded result is tens of kilobytes, which matters because this is a second request
 * on the save path and NFR-101 already budgets that path tightly.
 */
const val CONTACT_PHOTO_SIDE = 720

/** JPEG rather than PNG: a photograph, and a tenth of the bytes for no visible difference. */
const val CONTACT_PHOTO_QUALITY = 85

/** Where the card goes on the square canvas. Integer pixels, ready for a `Canvas.drawBitmap`. */
data class Placement(val left: Int, val top: Int, val width: Int, val height: Int)

/**
 * Fit a card onto the square canvas so that **nothing is lost to Google's circular crop**.
 *
 * **Letterboxing to the square is not enough, and this is the whole reading.** Google renders a
 * contact photo as a circle almost everywhere it appears — the Contacts app, Gmail, the People
 * chip — and the circle inscribed in a square touches its edges only at four points. A landscape
 * card scaled to the *full width* of the canvas therefore loses its left and right ends, which on
 * a business card is precisely the columns the telephone numbers are printed in. The crop would
 * remove the part a photograph of a card is kept for.
 *
 * So the card is scaled until its **diagonal** equals the circle's diameter, which is the largest
 * rectangle of that aspect ratio the circle contains. For a typical 1.75:1 card that is about 87%
 * of the canvas width — a cost of roughly 13% in size, paid to lose nothing at all.
 *
 * **The cost is real and is recorded rather than hidden**: in the few places Google shows a
 * square, the card sits a little smaller with white around it. That is the right way round. A
 * margin is visible and harmless; a cropped telephone number looks like a card that never had one.
 *
 * Never scales *up*: a small photograph stays its own size rather than being blown up into
 * blur, which would look like the app had damaged it.
 */
fun letterboxPlacement(
    sourceWidth: Int,
    sourceHeight: Int,
    canvas: Int = CONTACT_PHOTO_SIDE,
): Placement? {
    if (sourceWidth <= 0 || sourceHeight <= 0 || canvas <= 0) return null

    // The largest w x h of this aspect ratio whose diagonal is the canvas (= the circle's
    // diameter): w = canvas * sourceWidth / hypot(sourceWidth, sourceHeight).
    val diagonal = sqrt(
        sourceWidth.toDouble() * sourceWidth + sourceHeight.toDouble() * sourceHeight
    )
    val scale = min(canvas / diagonal, 1.0)

    val width = (sourceWidth * scale).roundToInt().coerceAtLeast(1)
    val height = (sourceHeight * scale).roundToInt().coerceAtLeast(1)
    return Placement(
        left = (canvas - width) / 2,
        top = (canvas - height) / 2,
        width = width,
        height = height,
    )
}

/**
 * FR-1226's answer, so the sheet says which of the three things happened.
 *
 * Three and not two, because "not asked for" and "asked for and could not be sent" are different
 * facts and only one of them is worth a sentence. The date pillar keeps `SaveState` apart from its
 * outcome for the same reason.
 */
enum class CardPhotoUpload {
    /** The user did not tick it. Nothing is said. */
    NOT_REQUESTED,

    /** Ticked, and the contact now carries the card. */
    ATTACHED,

    /**
     * Ticked, and Google would not take it. **The contact still exists and is still correct** —
     * FR-1226 says a photograph that fails must not fail the save, so this is reported beside a
     * successful save rather than instead of one.
     */
    FAILED,

    /**
     * Ticked, but the card was held rather than written (FR-1212).
     *
     * The queue is persistent storage and FR-1211 forbids a third party's photograph reaching it,
     * so a held card carries none and the photograph is dropped rather than waiting. Said on the
     * sheet **before** the save as well as after it, because a user who ticked the box offline
     * would otherwise learn about it only by noticing an absence later.
     */
    NOT_HELD,
}
