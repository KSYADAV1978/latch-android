package com.latch.ocr

import android.net.Uri

/**
 * On-device text recognition over images and PDFs (FR-215, FR-216, FR-207).
 *
 * Contracts and the decisions that can be made without a device. The ML Kit implementation is
 * in `MlKitOcr.kt`, and nothing here exposes an ML Kit type — that is what keeps the
 * dependency's blast radius to this module's build file.
 *
 * **Nothing in this module reaches the network** and no image leaves the device (FR-216). The
 * models are in the APK; the inference is local. ML Kit opens connections of its own for its
 * own logging, which is Google's traffic and not ours to route — the same standing Play
 * services' OAuth grant has, and what AC-17's restatement records.
 */

/**
 * Why recognition produced nothing usable.
 *
 * NFR-402: this module names what happened and the app phrases it, the same division
 * `SaveFailure` and `DraftBlocker` already keep. A module that returned display text would
 * put user-facing strings outside `strings.xml`.
 */
enum class OcrFailure {
    /** The URI could not be opened, or its bytes were not an image or PDF this device decodes. */
    UNREADABLE_SOURCE,

    /** Recognition ran and found no text. A photograph of a wall is not an error. */
    NO_TEXT_FOUND,

    /** Recognition itself failed. Distinct from [NO_TEXT_FOUND]: nothing was read, rather than nothing being there. */
    RECOGNITION_FAILED,
}

/**
 * How much of a PDF was read, so FR-207's cap can be **reported** rather than applied
 * silently.
 *
 * The requirement's note is explicit about why this is a value and not an internal detail: a
 * search that stopped looking must say so, because the caller cannot otherwise tell it apart
 * from having looked everywhere and found nothing. `DuplicateSearch.scanCapped` is the same
 * signal one layer down, and callers are forbidden from reading it as "there was nothing
 * there" for the same reason.
 */
data class PageCoverage(val read: Int, val total: Int) {
    /** True where pages were left unread. The app says "first N of M pages read". */
    val capped: Boolean get() = read < total
}

/**
 * How far through a document the reader has got, so NFR-102's spinner can say "Reading page N
 * of M…" rather than only that something is happening.
 *
 * **[total] is the number of pages that will be read, not the number in the document.** This is
 * a progress indicator over work: counting to fourteen while stopping at ten would show a bar
 * that never fills. FR-207's cap is reported separately and afterwards, by [PageCoverage] and
 * the "first 10 of 14 pages read" line, which is where the requirement puts it.
 */
data class PageProgress(val page: Int, val total: Int)

/**
 * The progress steps a document of [total] pages will report, in order.
 *
 * A pure function so the sequence is testable without a PDF — the arrangement [pagesToRead]
 * and [renderScaleFor] already have, and the only way anything in this module gets a JVM test.
 */
fun pageProgressSteps(total: Int, cap: Int = PDF_PAGE_CAP): List<PageProgress> {
    val toRead = pagesToRead(total, cap)
    return (1..toRead).map { PageProgress(page = it, total = toRead) }
}

/** Either the recognised text, or why there is none. */
sealed interface OcrResult {
    /**
     * [pages] is null for an image, which has no pages to cap, and present for a PDF whether
     * or not the cap bit — the screen needs the totals either way.
     */
    data class Text(val value: String, val pages: PageCoverage? = null) : OcrResult

    data class Failed(val reason: OcrFailure) : OcrResult
}

/**
 * Reads text out of an image or a PDF, entirely on device.
 *
 * An interface so `:app` can be built and tested against it without ML Kit or a device, the
 * same reason `CalendarApi` and `TasksApi` are interfaces in `:data`.
 *
 * **One recogniser handles both scripts.** FR-215 requires Latin and Devanagari at minimum,
 * and ML Kit's Devanagari model is a combined *Devanagari and Latin* engine — verified by
 * what the built APK actually ships: `gocrdevanagari_and_latin`, with the Latn, Deva and Beng
 * models beside it. So both scripts are met in a **single recognition pass**, which is what
 * keeps NFR-101's 2.5 s budget reachable and is also the only shape that reads a mixed-script
 * image correctly. Running a Latin pass and a Devanagari pass and choosing between them would
 * cost twice the time and would discard one script's text whenever an image held both.
 *
 * **If the device pass shows the combined engine reading Latin worse than the dedicated
 * model**, the escalation is to add `com.google.mlkit:text-recognition` — measured at +8,082
 * bytes, because the models are already there — and run it for Latin. That is a one-line
 * change and a new NFR-501 line, not a redesign. It is recorded here rather than pre-empted,
 * since an unused artifact declared against a risk that may not exist is what NFR-501 forbids.
 *
 * Implementations hold a native recogniser and are [AutoCloseable]. Loading it is not free, so
 * one instance should outlive a single capture — `:app` holds it on `LatchApplication`, for
 * the same reason `CaptureSaver` lives there.
 */
interface OcrReader : AutoCloseable {
    /** FR-215, via any layer that can share an image. */
    suspend fun readImage(uri: Uri): OcrResult

    /**
     * FR-207. Reads at most [PDF_PAGE_CAP] pages and reports how many of how many.
     *
     * [onPage] is called before each page is read, so NFR-102's progress state can count.
     * It is called from whatever thread the recognition runs on and must therefore do nothing
     * but publish a value; the default is the caller that does not care, which is every caller
     * for an image.
     */
    suspend fun readPdf(uri: Uri, onPage: (PageProgress) -> Unit = {}): OcrResult
}

/**
 * FR-207's cap, as a reading recorded in the SRS and movable by revision.
 *
 * Rendering and recognising is per-page work with a per-page cost, and NFR-101 budgets 2.5 s
 * for a *single* full-screen image; an uncapped PDF is an unbounded wait. Ten is chosen to be
 * past where a shared document stops being a message and starts being a file — a ticket, a
 * letter, an invoice, a school circular are all inside it.
 */
const val PDF_PAGE_CAP: Int = 10

/** How many pages will actually be read. Pure, so FR-207's cap is tested without a PDF. */
fun pagesToRead(total: Int, cap: Int = PDF_PAGE_CAP): Int = total.coerceIn(0, cap)

/**
 * The `inSampleSize` to decode a [width] x [height] image at, bounded by total pixels rather
 * than by either edge.
 *
 * Bounding by pixels is what makes one rule serve both shapes this app sees: a phone
 * screenshot is tall and narrow (1440 x 3120, 4.5 MP) and passes untouched, while a camera
 * photograph of a document is nearly square and enormous (4032 x 3024, 12.2 MP) and is halved.
 * A longest-edge rule would have halved the screenshot — throwing away the resolution small
 * text is read from — to solve a problem the screenshot did not have.
 *
 * The cap is 8 MP because the bitmap is `ARGB_8888`: 8 MP is 32 MB in one allocation, which a
 * mid-range 4 GB device (NFR-101's stated target) can find and a 12 MP photograph's 48 MB it
 * may not. `BitmapFactory` only accepts powers of two, so the result is one.
 */
fun sampleSizeFor(width: Int, height: Int, maxPixels: Int = MAX_DECODED_PIXELS): Int {
    if (width <= 0 || height <= 0 || maxPixels <= 0) return 1
    var sample = 1
    // Long, because 4032 * 3024 already exceeds a third of Int.MAX_VALUE and a larger
    // photograph would overflow the comparison rather than fail it.
    while (width.toLong() / sample * (height.toLong() / sample) > maxPixels) sample *= 2
    return sample
}

/** 8 MP as ARGB_8888 is a 32 MB allocation. See [sampleSizeFor]. */
const val MAX_DECODED_PIXELS: Int = 8_000_000

/**
 * The scale to render a PDF page at, from its size in points (1/72 inch).
 *
 * OCR wants something near 200 dpi; below roughly 150 the strokes of small body text stop
 * being separable and the recogniser starts inventing characters, which under design
 * principle 1 is worse than reading nothing. The result is clamped so that no page renders
 * to more than [MAX_DECODED_PIXELS], because a large-format page — a poster, an engineering
 * drawing — would otherwise ask for a bitmap no device will allocate.
 */
fun renderScaleFor(pageWidthPoints: Int, pageHeightPoints: Int): Float {
    if (pageWidthPoints <= 0 || pageHeightPoints <= 0) return 1f
    val target = OCR_TARGET_DPI / POINTS_PER_INCH
    val pixels = pageWidthPoints.toLong() * pageHeightPoints * target * target
    if (pixels <= MAX_DECODED_PIXELS) return target
    val room = MAX_DECODED_PIXELS.toDouble() / (pageWidthPoints.toLong() * pageHeightPoints)
    return kotlin.math.sqrt(room).toFloat()
}

private const val OCR_TARGET_DPI = 200f
private const val POINTS_PER_INCH = 72f

/**
 * Degrees of clockwise rotation for an EXIF orientation, which ML Kit takes alongside the
 * bitmap rather than expecting it to be applied.
 *
 * Screenshots carry no orientation and land on 0. A photograph of a document taken in
 * portrait very often does carry one, and without this the recogniser is handed a sideways
 * page and returns nothing at all — a failure that looks like OCR not working rather than
 * like an unread tag. The mirrored orientations are mapped to their rotation: ML Kit has no
 * way to be told about a flip, and a mirrored image is unreadable either way, so this at
 * least gets the axis right.
 */
fun rotationDegreesFor(exifOrientation: Int): Int = when (exifOrientation) {
    ORIENTATION_ROTATE_90, ORIENTATION_TRANSPOSE -> 90
    ORIENTATION_ROTATE_180, ORIENTATION_FLIP_VERTICAL -> 180
    ORIENTATION_ROTATE_270, ORIENTATION_TRANSVERSE -> 270
    else -> 0
}

// android.media.ExifInterface's constants, named here so this function is a pure Kotlin
// mapping and can be tested on the JVM. The platform class is API 24 and is read in
// MlKitOcr.kt, which is where a Context exists.
private const val ORIENTATION_FLIP_VERTICAL = 4
private const val ORIENTATION_TRANSPOSE = 5
private const val ORIENTATION_ROTATE_90 = 6
private const val ORIENTATION_TRANSVERSE = 7
private const val ORIENTATION_ROTATE_270 = 8
private const val ORIENTATION_ROTATE_180 = 3

/**
 * One recognised block of text and where it sat on the image.
 *
 * Deliberately not an ML Kit type. The two decisions that matter — which recogniser's version
 * of a block to keep, and what order the blocks go in — are then pure functions over this,
 * testable on the JVM beside the page cap and the downscale, which is the only way they get
 * tested at all given `:ocr` has no other JVM-reachable surface.
 */
data class TextBlock(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    /**
     * FR-1228: how far this block's baseline is turned from horizontal, in degrees.
     *
     * **Defaulted, so every existing caller and fixture is untouched.** It comes from ML Kit's
     * corner points, which describe the text's own quadrilateral rather than the axis-aligned box
     * the other four fields hold — and that difference is the entire reason a rotated card
     * scrambles: `inReadingOrder` groups rows by vertical overlap of *boxes*, and a turned card's
     * boxes all overlap everything.
     */
    val angle: Double = 0.0,
) {
    val height: Int get() = (bottom - top).coerceAtLeast(1)
    val width: Int get() = (right - left).coerceAtLeast(1)
}

/**
 * A rectangle in pixels. FR-1228's arithmetic works in three different frames and this is what
 * travels between them.
 */
data class PixelRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)
}

/**
 * FR-1228: where the text is, as one rectangle.
 *
 * **The union of the text blocks and not the card's outline**, which is the requirement's own
 * substance. The app does not want the card's edges; it wants what is printed on it, and this is
 * already computed by the time the first pass finishes. It is indifferent to the background, to
 * the card's colour, to whether the card is rectangular, and to the phone's aspect ratio.
 */
fun textUnion(blocks: List<TextBlock>): PixelRect? {
    if (blocks.isEmpty()) return null
    return PixelRect(
        left = blocks.minOf { it.left },
        top = blocks.minOf { it.top },
        right = blocks.maxOf { it.right },
        bottom = blocks.maxOf { it.bottom },
    )
}

/**
 * FR-1228: the region to re-read, with a margin, clamped to the image.
 *
 * **A margin, because the union is where text was *found*.** A character the first pass missed
 * at the edge — the `+` of a telephone number, the last letter of a domain — sits just outside
 * it, and re-reading a region cropped exactly to what was already read would be the one crop
 * guaranteed to preserve the first pass's mistakes.
 */
fun paddedRegion(
    union: PixelRect,
    imageWidth: Int,
    imageHeight: Int,
    margin: Double = REREAD_MARGIN,
): PixelRect {
    val padX = (union.width * margin).toInt()
    val padY = (union.height * margin).toInt()
    return PixelRect(
        left = (union.left - padX).coerceAtLeast(0),
        top = (union.top - padY).coerceAtLeast(0),
        right = (union.right + padX).coerceAtMost(imageWidth),
        bottom = (union.bottom + padY).coerceAtMost(imageHeight),
    )
}

/** See [paddedRegion]. Six per cent of the text's own extent, not of the frame's. */
const val REREAD_MARGIN: Double = 0.06

/**
 * FR-1228: is a second pass worth its cost?
 *
 * **The two sample sizes are the whole story**, and the first version of this got it wrong in a
 * way a device found. It compared the region's width before against its width after — which is the
 * same quantity only when the image is not turned, because `padded` is measured in the **upright**
 * frame and `region` in the **source** frame, and a quarter turn swaps those axes. On a card laid
 * sideways it therefore compared a height against a width and declined a pass that would have
 * doubled the resolution, logging `skipped 835px of 714px available` — an "available" smaller than
 * the current, which is impossible and was the tell.
 *
 * Nothing about the axes matters. The whole image was decoded at [sample] and the region will be
 * decoded at [regionSample]; the linear gain is the ratio of those two and applies to both axes
 * equally. A card filling the frame gives a region nearly as large as the image, so the two sample
 * sizes agree and the ratio is one.
 *
 * The threshold is a **half again**, because a second recognition costs real time against
 * NFR-101's 2.5 s and a gain of a few per cent would not move a glyph across the threshold that
 * matters.
 */
fun rereadWorthwhile(sample: Int, regionSample: Int): Boolean =
    sample > 0 && regionSample > 0 && sample.toDouble() / regionSample >= REREAD_GAIN

/** See [rereadWorthwhile]. */
const val REREAD_GAIN: Double = 1.5

/**
 * FR-1228: a rectangle in the **upright** frame, mapped back to the **source** image.
 *
 * Three frames meet here and getting them confused would re-read the wrong part of the picture,
 * which is a failure that looks exactly like bad OCR.
 *
 *  - the **source**: the file as it was written, unrotated, full size. `BitmapRegionDecoder` reads
 *    in these coordinates and nothing else does.
 *  - the **bitmap**: the source divided by `inSampleSize`, still unrotated.
 *  - the **upright**: the bitmap turned by the EXIF rotation. ML Kit reports its boxes here, which
 *    is what `recognise` already relies on when it measures the top-chrome band.
 *
 * So the mapping is the rotation undone, then the sample multiplied back. Rotating an image
 * clockwise by 90 sends a point (x, y) to (h - y, x) where h is the *source* height of that
 * rotation — undoing it is what each branch below does.
 */
fun sourceRect(
    upright: PixelRect,
    sample: Int,
    rotationDegrees: Int,
    sourceWidth: Int,
    sourceHeight: Int,
): PixelRect {
    val bw = sourceWidth / sample
    val bh = sourceHeight / sample
    val inBitmap = when (((rotationDegrees % 360) + 360) % 360) {
        90 -> PixelRect(upright.top, bh - upright.right, upright.bottom, bh - upright.left)
        180 -> PixelRect(bw - upright.right, bh - upright.bottom, bw - upright.left, bh - upright.top)
        270 -> PixelRect(bw - upright.bottom, upright.left, bw - upright.top, upright.right)
        else -> upright
    }
    return PixelRect(
        left = (inBitmap.left * sample).coerceIn(0, sourceWidth),
        top = (inBitmap.top * sample).coerceIn(0, sourceHeight),
        right = (inBitmap.right * sample).coerceIn(0, sourceWidth),
        bottom = (inBitmap.bottom * sample).coerceIn(0, sourceHeight),
    )
}

/**
 * FR-1228: which of the two readings to keep.
 *
 * **The longer one, and that is a deliberate choice of failure.** The second pass reads a better
 * image of the same text and should win — but the union it was cropped to is derived from the
 * first pass, so a first pass that found only a corner of the card would send the second one to
 * read that corner. Preferring the longer text means such a crop costs nothing rather than losing
 * what was already read. A tie goes to the second, which is the sharper image of the two.
 */
fun betterReading(first: String, second: String): String =
    if (second.length >= first.length) second else first

/**
 * FR-1228: the angle the writing on this image actually runs at.
 *
 * **The median and not the mean**, because one block read crookedly — a logo, a stray mark caught
 * as a character — would drag an average and cannot move a median. Blocks narrower than a few
 * characters are dropped first for the same reason: a two-letter box has almost no baseline to
 * measure and its angle is mostly noise.
 *
 * Returns 0 where there is nothing to measure, which the caller reads as "leave the image alone".
 */
fun dominantTextAngle(blocks: List<TextBlock>): Double {
    val measurable = blocks.filter { it.width >= MIN_ANGLE_WIDTH }.map { it.angle }.sorted()
    if (measurable.isEmpty()) return 0.0
    val middle = measurable.size / 2
    return if (measurable.size % 2 == 1) measurable[middle]
    else (measurable[middle - 1] + measurable[middle]) / 2.0
}

/** A box narrower than this has too little baseline for its angle to mean anything. */
const val MIN_ANGLE_WIDTH: Int = 40

/**
 * FR-1228: is the writing turned far enough to be worth straightening?
 *
 * **Two degrees**, because below that the rotation costs an allocation and a resample to buy
 * nothing — `inReadingOrder`'s row grouping tolerates a slight tilt perfectly well, and it is the
 * larger turns that collapse every line into a single row.
 */
fun worthLevelling(angle: Double): Boolean = kotlin.math.abs(angle) >= LEVEL_THRESHOLD_DEGREES

/** See [worthLevelling]. */
const val LEVEL_THRESHOLD_DEGREES: Double = 2.0

/** Devanagari's Unicode block. The test that decides which recogniser owns a region. */
fun hasDevanagari(text: String): Boolean = text.any { it.code in 0x0900..0x097F }

/**
 * FR-215's two scripts, merged per block rather than per image.
 *
 * Both recognisers read the whole image; this decides which one's version of each region to
 * keep. The test is applied to the **Devanagari** result, because that is the pass which can
 * prove Devanagari is present — a block of it containing a Devanagari codepoint is kept, and
 * every other region falls to the Latin pass. Keying off the Devanagari result rather than
 * asking whether the Latin pass "failed" matters: given Devanagari glyphs the Latin model does
 * not return nothing, it returns plausible Latin garbage, which no test on its own output
 * could distinguish from real text.
 *
 * The three cases this has to get right. **A Latin screenshot** keeps no Devanagari block, so
 * it is read entirely by the Latin model — no Bengali character set in play, and a Latin
 * language model that knows `October` is a word where `0ctober` is not. **A Hindi screenshot**
 * has Devanagari in every block, so the Devanagari model's text is used throughout and the
 * Latin pass contributes nothing. **A mixed screenshot** — English and Hindi in one thread,
 * which is the realistic Indian case — is read per bubble by whichever model suits it.
 *
 * **One residual, accepted.** Where the Devanagari model hallucinates a genuine Devanagari
 * codepoint inside what is really a Latin block, that block is kept from the wrong pass and
 * the Latin version is discarded. It is rare, it costs one block rather than the capture, and
 * the mixed-script fixture is what would catch it becoming common.
 */
fun mergeByScript(latin: List<TextBlock>, devanagari: List<TextBlock>): List<TextBlock> {
    val owned = devanagari.filter { hasDevanagari(it.text) }
    if (owned.isEmpty()) return latin
    val kept = latin.filterNot { block -> owned.any { it.substantiallyOverlaps(block) } }
    return owned + kept
}

/** True where the two blocks cover enough of the same area to be the same region read twice. */
private fun TextBlock.substantiallyOverlaps(other: TextBlock, minFraction: Double = 0.5): Boolean {
    val width = (minOf(right, other.right) - maxOf(left, other.left)).coerceAtLeast(0)
    val tall = (minOf(bottom, other.bottom) - maxOf(top, other.top)).coerceAtLeast(0)
    val intersection = width.toLong() * tall
    if (intersection == 0L) return false
    val smaller = minOf(
        (right - left).toLong() * (bottom - top),
        (other.right - other.left).toLong() * (other.bottom - other.top),
    ).coerceAtLeast(1)
    return intersection.toDouble() / smaller >= minFraction
}

/**
 * FR-215: drops a screenshot's own furniture before its text is assembled.
 *
 * A recogniser returns everything on the screen, and the top of a screenshot is a status bar.
 * Its clock satisfies FR-503's time formats exactly as a written time does — at the level of
 * text there is no difference — so it is paired with the nearest date and the capture acquires
 * a time nobody wrote. Under FR-506 that turns a Task into an Event and puts it on the
 * calendar at an hour taken from the phone's status bar. It reaches the title too, which is
 * what FR-509a exists to stop.
 *
 * **A fraction of the image, not a pixel count.** A status bar is three to five per cent of any
 * screen this app will meet, and a constant chosen against one screen is wrong on the next. On
 * a 1440×3120 screenshot [TOP_BAND_FRACTION] is 125 pixels: taller than a status bar, shorter
 * than a message row, which is the gap this rule lives in.
 *
 * **Wholly within the band, never merely overlapping it.** Discarding a real first line is the
 * risk that kept this deferred through the FR-215 slice, so a block that begins in the band and
 * continues below it is content and survives.
 *
 * **It follows that the rule is inert on a short image**, and that is a guarantee rather than an
 * accident: a block cannot sit wholly inside 4% until 4% exceeds a line of text, which for a
 * ~40-pixel line means around a thousand pixels of height. A cropped screenshot whose first
 * real line begins at the very top therefore passes through untouched.
 *
 * **Bottom chrome — the composer placeholder, the navigation bar — is deliberately not
 * handled.** It is the same class of furniture, but the arithmetic does not transfer: the
 * bottom of a screenshot is where the most recent message sits, which is where a commitment
 * most often is, so a symmetric rule would risk dropping exactly what the user captured.
 *
 * @param imageHeight the height of the image **as the recogniser saw it**, upright. For a
 *   rotated capture that is not the bitmap's own height — see the caller.
 */
fun withoutTopChrome(
    blocks: List<TextBlock>,
    imageHeight: Int,
    fraction: Double = TOP_BAND_FRACTION,
): List<TextBlock> {
    if (imageHeight <= 0) return blocks
    val band = imageHeight * fraction
    return blocks.filterNot { it.bottom <= band }
}

/** How much of an image's height counts as chrome. See [withoutTopChrome]. */
const val TOP_BAND_FRACTION: Double = 0.04

/**
 * Blocks in the order a person reads them, from their positions on the image.
 *
 * **Recorded as a reading against §7.2, because it is one.** `latch.item_key` is derived from
 * character positions in the assembled text, so whatever decides the order of that text
 * decides an identity written into a user's Google account. ML Kit's own block order is an
 * internal detail of the library: leaving it in place makes `item_key` depend on an ML Kit
 * version, and an upgrade could move it silently — the same class of wire-contract drift as
 * the v1.14 and v1.23 moves, but caused by a dependency rather than by a decision. Sorting on
 * geometry replaces a library internal with a property of the image.
 *
 * It is not hypothetical. A chat screenshot returned `Paid the uniform bill` *after* the line
 * three messages below it, and `Ok noted…` last of all, so FR-505's "earliest mention"
 * tiebreak was deciding on an order that was not the writer's.
 *
 * Blocks are grouped into rows by vertical overlap — two share a row when they overlap
 * vertically by more than [ROW_OVERLAP] of the shorter one — then rows go by top edge and
 * blocks within a row by left edge. A chat screenshot, whose bubbles stack vertically at
 * alternating x, comes out exactly right; so does a single-column document. **Genuine
 * multi-column text interleaves**, which is a known limit rather than a solved problem:
 * detecting columns is a larger job than this slice, and interleaving is at least predictable.
 */
fun inReadingOrder(blocks: List<TextBlock>): List<TextBlock> {
    if (blocks.size <= 1) return blocks
    val rows = ArrayList<MutableList<TextBlock>>()
    blocks.sortedBy { it.top }.forEach { block ->
        val row = rows.lastOrNull()?.takeIf { current ->
            current.any { it.sharesRowWith(block) }
        }
        if (row != null) row += block else rows += mutableListOf(block)
    }
    return rows.flatMap { row -> row.sortedBy { it.left } }
}

private fun TextBlock.sharesRowWith(other: TextBlock): Boolean {
    val overlap = (minOf(bottom, other.bottom) - maxOf(top, other.top)).coerceAtLeast(0)
    return overlap.toDouble() / minOf(height, other.height) > ROW_OVERLAP
}

/** How much two blocks must overlap vertically to count as the same row. */
const val ROW_OVERLAP: Double = 0.5

/** The blocks as one string, one block per line. Ordering is [inReadingOrder]'s business. */
fun assemble(blocks: List<TextBlock>): String =
    blocks.map { it.text.trim() }.filter { it.isNotEmpty() }.joinToString("\n")

/**
 * Joins the per-page texts of a PDF into one capture.
 *
 * A blank line between pages, and pages that recognised nothing dropped rather than left as
 * empty gaps. The separator matters downstream: `:parser` reads a blank line as a boundary,
 * and running the last line of one page into the first of the next is how a date acquires a
 * neighbour it never had on the page.
 */
fun joinPages(pages: List<String>): String =
    pages.map(String::trim).filter(String::isNotEmpty).joinToString("\n\n")
