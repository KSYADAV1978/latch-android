package com.latch.android.cards

import java.io.File

/**
 * FR-1201a and FR-1211: the photograph taken inside Latch, and how long it is allowed to exist.
 *
 * **Nothing here touches Android.** The decisions are booleans and the storage is `java.io.File`,
 * both of which a JVM test can reach — which is the point, because the file's lifetime is the
 * whole of what FR-1211 now asks for and a rule nothing can test is a rule nobody keeps. The
 * Activity supplies `cacheDir` and calls these; it decides nothing itself.
 */

/**
 * The cache subdirectory a card photograph lives in, for as long as one capture lasts.
 *
 * **Not FR-1005's `exports/`, and the reason was found by reading `shareAsIcs` rather than by
 * worrying about it**: that directory is `deleteRecursively`'d on every export, so exporting an
 * `.ics` while a card sheet was open would delete the photograph out from under the recognition
 * reading it. Two purposes, two directories; the `FileProvider` grants both and nothing else.
 */
const val CAMERA_DIRECTORY = "camera"

/**
 * One name, because there is only ever one photograph.
 *
 * The extension matters: it is what the camera application writes and what `BitmapFactory`
 * is handed afterwards.
 */
private const val CAMERA_FILE = "card.jpg"

/**
 * Where the photograph is, computed and never created.
 *
 * **A fixed name rather than one held in a field**, so the result of the camera can be found
 * again by a process that was killed while the camera application was in front of it. A `Uri`
 * remembered in an Activity field would not survive that, and what it would lose is a
 * photograph the user has already taken.
 */
fun cardPhotoFile(cacheDir: File): File = File(File(cacheDir, CAMERA_DIRECTORY), CAMERA_FILE)

/**
 * A file for the camera application to write the next photograph into, or null if the
 * directory cannot be made.
 *
 * **Sweeps first.** FR-1211's rule is bounded lifetime rather than no file at all, and a sweep
 * before each photograph is what bounds it in the case closing the window cannot cover — a
 * killed process leaves its file behind, and the next photograph is what removes it.
 */
fun newCardPhotoFile(cacheDir: File): File? {
    val directory = File(cacheDir, CAMERA_DIRECTORY)
    directory.deleteRecursively()
    if (!directory.mkdirs()) return null
    return cardPhotoFile(cacheDir)
}

/**
 * FR-1211: the photograph is gone.
 *
 * Called when the capture window finishes and when a camera launch is abandoned. Deliberately
 * **not** called the moment recognition returns: a rotation recreates the capture screen and
 * re-runs the recognition against the same URI, so deleting on the first result would lose a
 * capture to a quarter-turn of the phone. See the FR-1211 note in the SRS.
 */
fun clearCardPhotos(cacheDir: File) {
    File(cacheDir, CAMERA_DIRECTORY).deleteRecursively()
}

/**
 * FR-1202 on the camera path: what to do once a photograph has been read.
 *
 * **The choice was made at the button**, so nothing here asks whether this is a card — it asks
 * only whether there is anything to open a card sheet *with*.
 */
enum class CardPhotoStep {
    /** One of the two readers has not finished. Keep the spinner up. */
    WAITING,

    /** Open the card sheet. Which payload, or the photograph itself, is `openCard`'s decision. */
    OPEN,

    /** Both readers finished and neither found anything. Say so rather than open an empty sheet. */
    NOTHING_READ,
}

/**
 * **Both readers, and this is the opposite ordering to SRS 1.100's.**
 *
 * There the fix was to stop the QR decode waiting on the recognition, because the decode is the
 * faster of the two and folding it into the recognition's state dropped it. Here the sheet must
 * not open until *both* have settled: a card carrying a QR code would otherwise be classified off
 * its own photograph while the payload was still arriving, losing the grammar's exact answer to a
 * race with the guess. "Usually faster" is not an ordering, and this is the place that needs one.
 *
 * [hasText] is deliberately separate from [payloads]: a badly-lit card whose QR still decodes is
 * worth opening with no recognised text at all, and a photograph with text and no code is the
 * ordinary FR-1220 case. Only neither is nothing.
 */
fun cardPhotoStep(
    recognitionSettled: Boolean,
    decodeSettled: Boolean,
    hasText: Boolean,
    payloads: Int,
): CardPhotoStep = when {
    !recognitionSettled || !decodeSettled -> CardPhotoStep.WAITING
    payloads > 0 || hasText -> CardPhotoStep.OPEN
    else -> CardPhotoStep.NOTHING_READ
}
