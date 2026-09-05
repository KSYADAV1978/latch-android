package com.latch.android.cards

import java.io.File

/**
 * FR-1201a and FR-1225: the photographs taken inside Latch, and how long they are allowed to exist.
 *
 * **Nothing here touches Android.** The decisions are booleans and the storage is `java.io.File`,
 * both of which a JVM test can reach — which is the point, because the files' lifetime is the whole
 * of what FR-1211 now asks for and a rule nothing can test is a rule nobody keeps.
 */

/**
 * The cache subdirectory a card capture's photographs live in, for as long as the capture lasts.
 *
 * **Not FR-1005's `exports/`, and the reason was found by reading `shareAsIcs` rather than by
 * worrying about it**: that directory is `deleteRecursively`'d on every export, so exporting an
 * `.ics` while a card sheet was open would delete the photographs out from under the recognition
 * reading them. Two purposes, two directories; the `FileProvider` grants both and nothing else.
 */
const val CAMERA_DIRECTORY = "camera"

/**
 * FR-1225: how many photographs one card capture will read.
 *
 * **Four, and the number is a reading.** Two sides is the case the requirement exists for; four
 * leaves room for a fold-out card and for a side re-shot without abandoning the capture. It is not
 * unbounded because each one is a full-resolution image held in the cache and recognised again on
 * every rotation of the phone.
 */
const val MAX_CARD_PHOTOS = 4

private const val CAMERA_PREFIX = "card-"
private const val CAMERA_SUFFIX = ".jpg"

/** The extension matters: it is what the camera application writes and `BitmapFactory` reads. */
private fun photoNamed(index: Int) = "$CAMERA_PREFIX$index$CAMERA_SUFFIX"

/**
 * The photographs of the capture in hand, in the order they were taken.
 *
 * **Enumerated from disk rather than remembered, and that is the simpler answer as well as the
 * only correct one** (SRS 1.124). The capture screen re-runs its recognition on rotation, which
 * has always been acceptable because it costs a second and loses nothing — but state remembered in
 * the composition would drop every photograph but the first, which loses work. FR-1211 already
 * puts them here, so disk was the source of truth and anything else would be a second copy of it.
 *
 * Sorted by the index in the name and not lexicographically: `card-10.jpg` would sort before
 * `card-2.jpg`, and although [MAX_CARD_PHOTOS] puts that out of reach today, a cap is a poor thing
 * for a sort order to depend on.
 */
fun cardPhotoFiles(cacheDir: File): List<File> =
    File(cacheDir, CAMERA_DIRECTORY)
        .listFiles()
        .orEmpty()
        .mapNotNull { file ->
            file.name
                .removeSurrounding(CAMERA_PREFIX, CAMERA_SUFFIX)
                .takeIf { it != file.name }
                ?.toIntOrNull()
                ?.let { index -> index to file }
        }
        .sortedBy { it.first }
        .map { it.second }

/**
 * A file for the first photograph of a new capture, or null if the directory cannot be made.
 *
 * **Sweeps first.** FR-1211's rule is bounded lifetime rather than no file at all, and a sweep at
 * the start of each capture is what bounds it in the case closing the window cannot cover — a
 * killed process leaves its photographs behind, and this is what removes them.
 */
fun startCardPhotoSet(cacheDir: File): File? {
    val directory = File(cacheDir, CAMERA_DIRECTORY)
    directory.deleteRecursively()
    if (!directory.mkdirs()) return null
    return File(directory, photoNamed(1))
}

/**
 * FR-1225: a file for the next photograph of the capture already in hand, or null at the cap.
 *
 * **It does not sweep**, which is the whole difference from [startCardPhotoSet]: these
 * photographs are the sides of one card and the capture needs all of them at once.
 *
 * Returns the path without creating it, so a photograph the user abandons leaves no file and the
 * next attempt reuses the same index rather than counting a shot nobody took.
 */
fun nextCardPhotoFile(cacheDir: File): File? {
    val directory = File(cacheDir, CAMERA_DIRECTORY)
    val taken = cardPhotoFiles(cacheDir).size
    if (!canAddCardPhoto(taken)) return null
    if (!directory.isDirectory && !directory.mkdirs()) return null
    return File(directory, photoNamed(taken + 1))
}

/**
 * FR-1211: the photographs are gone.
 *
 * Called when the capture window finishes and when a camera launch is abandoned. Deliberately
 * **not** called the moment recognition returns: a rotation recreates the capture screen and
 * re-runs the recognition against the same files, so deleting on the first result would lose a
 * capture to a quarter-turn of the phone. See the FR-1211 note in the SRS.
 */
fun clearCardPhotos(cacheDir: File) {
    File(cacheDir, CAMERA_DIRECTORY).deleteRecursively()
}

/**
 * FR-1225: whether another photograph may be added to this capture.
 *
 * **The cap is enforced by withdrawing the control**, not by taking a photograph and then
 * discarding it. That is why the requirement's "more images added than the capture will read"
 * cannot arise from the cap here — see [cardPhotoCoverage] for what it does arise from.
 */
fun canAddCardPhoto(taken: Int): Boolean = taken < MAX_CARD_PHOTOS

/**
 * FR-1225's coverage line, on FR-207's pattern: how many photographs yielded text, of how many
 * were taken.
 *
 * [read] is not the number of photographs; it is the number that produced anything to classify. A
 * side that came out blurred, or a lens cap, adds a photograph and no lines — and a user who takes
 * a third photograph and sees the sheet not change cannot otherwise tell that apart from a side
 * with nothing on it. FR-207 reports its page cap for exactly this reason.
 */
data class CardPhotoCoverage(val read: Int, val added: Int) {
    /** True where a photograph was taken and gave nothing. The app says "N of M photos read". */
    val capped: Boolean get() = read < added
}

/**
 * FR-1225: the lines of every photograph, in the order they were added.
 *
 * **One input to one classification, never two drafts merged.** Merging would apply every FR-1221
 * rule twice and then need a conflict policy for each scalar field — two names, two companies, two
 * job titles, none of them separable afterwards. Concatenating the input needs no such policy:
 * there is one name to find, and the company on the front is in scope when the address on the back
 * is assembled.
 *
 * Blank lines go, because a photograph that ends in whitespace would otherwise put a gap in the
 * middle of the next side and the classifier's positional rules read adjacency.
 */
fun cardLinesOf(recognisedPerPhoto: List<String>): List<String> =
    recognisedPerPhoto.flatMap { text -> text.lines() }.filter { it.isNotBlank() }

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
