package com.latch.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.media.ExifInterface
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.IOException
import java.io.InputStream
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * [OcrReader] over ML Kit Text Recognition v2, bundled (FR-215), and the platform's
 * `PdfRenderer` (FR-207).
 *
 * The one place in this project that names an ML Kit type. Everything above it sees
 * [OcrResult].
 *
 * **The recogniser is held, not created per call.** Loading the native pipeline costs real
 * time and NFR-101 allows 2.5 s for a whole capture, so one instance should serve a process:
 * `:app` holds this on `LatchApplication`, beside `CaptureSaver` and for the same reason.
 * [close] releases it.
 */
class MlKitOcrReader(
    private val context: Context,
    /**
     * How big an image was and how much of it survived the decode (SRS 1.133).
     *
     * **A sink rather than a call to `Log`**, exactly as `CaptureSaver`'s decision line is: this
     * module's decisions are JVM-tested and one `android.util.Log` import would put a throwing
     * stub in their path. `LatchApplication` supplies the logcat one, under the same debug guard.
     *
     * It carries dimensions and a sample size, which are not content.
     */
    private val log: (String) -> Unit = {},
) : OcrReader {

    /**
     * FR-215's two scripts, one recogniser each.
     *
     * The Devanagari model alone was tried first, its engine being a combined *Devanagari and
     * Latin* one. The device pass reversed that: it has the Bengali model loaded beside the
     * other two and substitutes Bengali codepoints into Latin words. See [OcrReader]'s KDoc.
     *
     * Both lazy, so constructing a reader costs nothing: `:app` builds one at process start
     * and the great majority of captures are text that never reach it.
     */
    private var latin: TextRecognizer? = null
    private var devanagari: TextRecognizer? = null

    private fun latin(): TextRecognizer =
        latin ?: TextRecognition
            .getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            .also { latin = it }

    private fun devanagari(): TextRecognizer =
        devanagari ?: TextRecognition
            .getClient(DevanagariTextRecognizerOptions.Builder().build())
            .also { devanagari = it }

override suspend fun readImage(uri: Uri): OcrResult = withContext(Dispatchers.Default) {
        val source = sourceSize(uri) ?: return@withContext OcrResult.Failed(OcrFailure.UNREADABLE_SOURCE)
        val sample = sampleSizeFor(source.width(), source.height())
        val bitmap = decodeBitmap(uri)
            ?: return@withContext OcrResult.Failed(OcrFailure.UNREADABLE_SOURCE)
        val rotation = rotationDegreesFor(exifOrientation(uri))
        var appliedAngle = 0.0
        var readRegion: PixelRect? = null

        val text = try {
            val blocks = recogniseBlocks(bitmap, rotation)
            appliedAngle = dominantTextAngle(blocks)
            val first = assemble(blocks)
            // FR-1228: read the card again at the resolution of its own text.
            val reread = rereadRegion(uri, blocks, bitmap, sample, rotation, source)
            val second = reread?.text.orEmpty()
            betterReading(first, second).also { kept ->
                if (kept === second) readRegion = reread?.region
                // **Which reading won, and it is not inferable from anything else.** A second pass
                // that fired and then lost looks identical in the log to one that fired and won,
                // and the difference is the whole question of whether FR-1228 is earning its cost.
                if (second.isNotEmpty()) {
                    log("reread kept=" + (if (kept === second) "second" else "first") +
                        " first=${first.length} second=${second.length}")
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return@withContext OcrResult.Failed(OcrFailure.RECOGNITION_FAILED)
        } finally {
            bitmap.recycle()
        }

        text.trim().takeIf(String::isNotEmpty)
            ?.let { OcrResult.Text(it, textAngle = appliedAngle, readRegion = readRegion) }
            ?: OcrResult.Failed(OcrFailure.NO_TEXT_FOUND)
    }

    /**
     * FR-1228: the second pass, or null where there is nothing to gain.
     *
     * **The whole point is that this decodes from the source rather than from [bitmap].** A crop of
     * the already-decoded bitmap would recover nothing — `inSampleSize` has discarded those pixels
     * — so the region is mapped back to file coordinates and read again with a
     * `BitmapRegionDecoder`. A card at half the frame then reads at the resolution of one filling
     * it; at a third, roughly double.
     *
     * **Everything that can go wrong here returns null and keeps the first reading.** The region
     * is derived from the first pass, so a first pass that found only a corner would send this to
     * re-read that corner — which is why the caller takes the *longer* of the two texts and why a
     * failure costs nothing rather than costing the capture.
     */
    private class Reread(val text: String, val region: PixelRect)

    private suspend fun rereadRegion(
        uri: Uri,
        blocks: List<TextBlock>,
        bitmap: Bitmap,
        sample: Int,
        rotation: Int,
        source: Rect,
    ): Reread? {
        val union = textUnion(blocks) ?: return null
        val uprightWidth = if (rotation % 180 == 0) bitmap.width else bitmap.height
        val uprightHeight = if (rotation % 180 == 0) bitmap.height else bitmap.width
        val padded = paddedRegion(union, uprightWidth, uprightHeight)
        val region = sourceRect(padded, sample, rotation, source.width(), source.height())
        if (region.width <= 0 || region.height <= 0) return null

        val regionSample = sampleSizeFor(region.width, region.height)
        if (!rereadWorthwhile(sample, regionSample)) {
            // **Said out loud, because "no reread line" had two meanings.** A pass that was never
            // considered and one the guard declined looked identical in the log, and the second
            // device run of FR-1228 could not tell which had happened.
            log("reread skipped sample=$sample region sample=$regionSample")
            return null
        }

        val cropped = decodeRegion(uri, region, regionSample) ?: return null
        // FR-1228's second half: the writing is levelled before it is read again, so that
        // `inReadingOrder` is handed an upright image rather than asked to group rows out of boxes
        // that are all turned. That is SRS 1.134's collapse fixed at its cause.
        val angle = dominantTextAngle(blocks)
        val levelled = levelled(cropped, angle)
        val turned = levelled !== cropped
        log(
            "reread ${region.width}x${region.height} sample=$sample->$regionSample" +
                " angle=${"%.1f".format(angle)}"
        )
        return try {
            // **No rotation is passed, turned or not.** The angle is measured in the file's own
            // frame, so either it was near zero — the writing already runs horizontally in the
            // file — or it has just been rotated away. Passing the EXIF turn here is what counted
            // it twice.
            val second = recogniseBlocks(levelled, 0)
            // **What angle the writing runs at *after* the levelling**, which is the one number
            // that says whether the rotation took effect. Near zero means it did; near the angle
            // it started at means it did not, or went the wrong way. Content-free: a number.
            log("reread after angle=${"%.1f".format(dominantTextAngle(second))}")
            Reread(assemble(second), region)
        } finally {
            levelled.recycle()
            if (turned) cropped.recycle()
        }
    }

    /**
     * FR-1228: the angle of a block's own baseline, from its corner points.
     *
     * **The corner points describe the text's quadrilateral, where `boundingBox` is the
     * axis-aligned rectangle around it** — on level writing the two agree and on turned writing
     * only the corners know. They come clockwise from the text's top-left, so the first edge is
     * the baseline direction.
     *
     * Zero where ML Kit gave none, which the median then ignores as a block with nothing to say.
     */
    private fun blockAngle(corners: Array<android.graphics.Point>?): Double {
        val points = corners ?: return 0.0
        if (points.size < 2) return 0.0
        val dx = (points[1].x - points[0].x).toDouble()
        val dy = (points[1].y - points[0].y).toDouble()
        if (dx == 0.0 && dy == 0.0) return 0.0
        return Math.toDegrees(kotlin.math.atan2(dy, dx))
    }

    /**
     * FR-1228: the region turned so its writing runs horizontally.
     *
     * **Only the text's own angle, and the EXIF rotation is deliberately absent** — which took a
     * device to establish (SRS 1.140). The first version composed the two, on the documented
     * premise that ML Kit reports its boxes in the *upright* frame, so that the EXIF turn still had
     * to be applied and the angle then subtracted. A measurement says otherwise: a card at
     * `angle=179.2` came back from the second pass at `angle=90.8`, a turn of 88 degrees where 179
     * was asked for, and 179.2 minus 90 is exactly the EXIF rotation being counted twice.
     *
     * **So the corner points are in the input bitmap's own frame**, and the angle they give already
     * contains whatever the EXIF turn contributed. Rotating by its negative therefore levels the
     * writing and squares the image in one step, which is why the second pass now hands ML Kit no
     * rotation at all.
     *
     * Returns the original where there is nothing to do, so the common path allocates nothing.
     */
    private fun levelled(bitmap: Bitmap, textAngle: Double): Bitmap {
        if (!worthLevelling(textAngle)) return bitmap
        return try {
            Bitmap.createBitmap(
                bitmap, 0, 0, bitmap.width, bitmap.height,
                Matrix().apply { postRotate(-textAngle.toFloat()) },
                true,
            )
        } catch (outOfMemory: OutOfMemoryError) {
            bitmap
        }
    }

    /** The file's own dimensions, read from the header. Allocates nothing. */
    private fun sourceSize(uri: Uri): Rect? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val header = openStream(uri) ?: return null
        header.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        return Rect(0, 0, bounds.outWidth, bounds.outHeight)
    }

    /**
     * FR-1228: one rectangle of the file, at [sample].
     *
     * `BitmapRegionDecoder` has been in the platform since API 10 and reads JPEG, so this needs no
     * dependency. `newInstance(InputStream, Boolean)` is deprecated at API 31 in favour of an
     * overload this project's minSdk of 26 does not have; the deprecated call is what works on
     * both, and is the same trade `getParcelableExtra` records in `CaptureIntents`.
     */
    @Suppress("DEPRECATION")
    private fun decodeRegion(uri: Uri, region: PixelRect, sample: Int): Bitmap? = try {
        openStream(uri)?.use { stream ->
            val decoder = BitmapRegionDecoder.newInstance(stream, false)
            try {
                decoder?.decodeRegion(
                    Rect(region.left, region.top, region.right, region.bottom),
                    BitmapFactory.Options().apply {
                        inSampleSize = sample
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    },
                )
            } finally {
                decoder?.recycle()
            }
        }
    } catch (outOfMemory: OutOfMemoryError) {
        null
    } catch (unreadable: Exception) {
        // A format `BitmapRegionDecoder` will not open — some WebP, anything exotic. The first
        // reading stands; FR-1228 is an improvement and never a precondition.
        null
    }

    override suspend fun readPdf(
        uri: Uri,
        onPage: (PageProgress) -> Unit,
    ): OcrResult = withContext(Dispatchers.Default) {
        val descriptor = openDescriptor(uri)
            ?: return@withContext OcrResult.Failed(OcrFailure.UNREADABLE_SOURCE)

        val read = try {
            descriptor.use { fd -> PdfRenderer(fd).use { renderAndRecognise(it, onPage) } }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (unreadable: IOException) {
            // Encrypted, truncated, or not a PDF at all. Not a recognition failure: nothing
            // was ever handed to the recogniser.
            return@withContext OcrResult.Failed(OcrFailure.UNREADABLE_SOURCE)
        } catch (unreadable: SecurityException) {
            // PdfRenderer's own signal for a password-protected document.
            return@withContext OcrResult.Failed(OcrFailure.UNREADABLE_SOURCE)
        } catch (failure: Exception) {
            return@withContext OcrResult.Failed(OcrFailure.RECOGNITION_FAILED)
        }

        val text = joinPages(read.texts)
        if (text.isEmpty()) {
            // A scanned PDF whose pages hold nothing recognisable. Reported as no text rather
            // than as coverage, because there is no text for a "first N of M" line to sit
            // beside and the honest answer is that nothing was read.
            return@withContext OcrResult.Failed(OcrFailure.NO_TEXT_FOUND)
        }
        OcrResult.Text(text, read.coverage)
    }

    private class PagesRead(val texts: List<String>, val coverage: PageCoverage)

    /**
     * Renders each page and recognises it, up to FR-207's cap.
     *
     * Pages are rendered one at a time and recycled immediately. Ten pages held at once at
     * the resolution [renderScaleFor] asks for would be a third of a gigabyte, so the shape of
     * this loop is a memory bound rather than a matter of style.
     */
    private suspend fun renderAndRecognise(
        renderer: PdfRenderer,
        onPage: (PageProgress) -> Unit,
    ): PagesRead {
        val total = renderer.pageCount
        val steps = pageProgressSteps(total)
        val toRead = steps.size
        val texts = ArrayList<String>(toRead)

        for (index in 0 until toRead) {
            // Before the page is read, not after: NFR-102's line describes what is happening
            // now, and a document that reported completion would sit on "0 of 10" for the
            // whole of the first page — which is the longest single wait there is.
            onPage(steps[index])
            renderer.openPage(index).use { page ->
                val scale = renderScaleFor(page.width, page.height)
                val bitmap = Bitmap.createBitmap(
                    (page.width * scale).toInt().coerceAtLeast(1),
                    (page.height * scale).toInt().coerceAtLeast(1),
                    Bitmap.Config.ARGB_8888,
                )
                try {
                    // A PDF page is transparent where nothing is drawn, and ML Kit reads a
                    // transparent bitmap as black on black. Without this, an ordinary page
                    // recognises as empty.
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    // A rendered page is upright by construction, so no rotation.
                    texts += recognise(bitmap, rotationDegrees = 0)
                } finally {
                    bitmap.recycle()
                }
            }
        }
        return PagesRead(texts, PageCoverage(read = toRead, total = total))
    }

    /**
     * FR-215's two scripts, both models over the same image, **concurrently**.
     *
     * ML Kit's `process` is asynchronous and each recogniser has its own native pipeline, so
     * launching both and awaiting them costs the slower pass rather than the sum. Sequentially
     * this would be two full inferences added end to end against NFR-101's 2.5 s, which is the
     * budget this whole path has to fit inside.
     *
     * The merge and the ordering are pure functions in `Ocr.kt`; this method's only job is to
     * get two block lists off the device and hand them over.
     */
    private suspend fun recognise(bitmap: Bitmap, rotationDegrees: Int): String =
        assemble(recogniseBlocks(bitmap, rotationDegrees))

    /**
     * As [recognise], but stopping one step short.
     *
     * **Split for FR-1228**, which needs where the text was and not only what it said: the union of
     * these boxes is the region worth reading again. `assemble` is one call away and every caller
     * that wants a string still gets one.
     */
    private suspend fun recogniseBlocks(
        bitmap: Bitmap,
        rotationDegrees: Int,
    ): List<TextBlock> = coroutineScope {
        val image = InputImage.fromBitmap(bitmap, rotationDegrees)
        val latinPass = async { blocksOf(latin(), image) }
        val devanagariPass = async { blocksOf(devanagari(), image) }
        val merged = mergeByScript(latinPass.await(), devanagariPass.await())
        // **This rests on a premise a device disproved** (SRS 1.140): ML Kit reports its boxes in
        // the *input bitmap's* frame, not the upright one, so the swap below is wrong. It is
        // harmless and is left alone deliberately — the chrome rule exists for a screenshot's
        // status bar, screenshots carry no EXIF rotation, and with a rotation of zero the two
        // branches agree. Changing it would touch a rule verified on a device on 31 August to fix
        // a case that cannot arise; it would matter the day this rule were asked about a
        // photograph, and that is what this comment is for.
        val uprightHeight = if (rotationDegrees % 180 == 0) bitmap.height else bitmap.width
        inReadingOrder(withoutTopChrome(merged, uprightHeight))
    }

    /**
     * ML Kit's `Task` API is callback-based. Bridging it is about ten lines of
     * `suspendCancellableCoroutine`, which is why `kotlinx-coroutines-play-services` is not
     * taken here — the same decision, for the same reason, that `GoogleAuthClient` records
     * for the OAuth grant.
     *
     * Blocks are converted to `:ocr`'s own [TextBlock] here and nowhere else, so no ML Kit
     * type escapes this file.
     */
    private suspend fun blocksOf(recognizer: TextRecognizer, image: InputImage): List<TextBlock> =
        suspendCancellableCoroutine { continuation ->
            recognizer.process(image)
                .addOnSuccessListener { text ->
                    continuation.resume(
                        text.textBlocks.mapNotNull { block ->
                            val box = block.boundingBox ?: return@mapNotNull null
                            TextBlock(
                                block.text, box.left, box.top, box.right, box.bottom,
                                angle = blockAngle(block.cornerPoints),
                            )
                        }
                    )
                }
                .addOnFailureListener { continuation.resumeWithException(it) }
        }

    /**
     * Decodes at a sample size bounded by total pixels — see [sampleSizeFor]. The bounds pass
     * reads the header only and allocates nothing, which is what makes the decision possible
     * before the allocation that could fail.
     */
    /**
     * The bitmap loader, exposed so FR-1203's QR decoder can share it (SRS 1.96).
     *
     * **Shared rather than copied.** The EXIF rotation this loader applies was a device finding —
     * an unread orientation tag hands a recogniser a sideways page and it returns nothing at all
     * (SRS 1.31) — and a second loader would be a second place to get that wrong, in a decoder
     * whose failure looks identical to "there was no code".
     */
    fun imageSource(): ImageSource = ImageSource { uri -> decodeBitmap(uri) }

    /**
     * How big the image was and how much of it survived the decode (SRS 1.133).
     *
     * **A sink rather than a call to `Log`**, exactly as `CaptureSaver`'s decision line is: this
     * module's pure functions are JVM-tested and one `android.util.Log` import would put a
     * throwing stub in their path. `LatchApplication` supplies the logcat one.
     *
     * It exists because the developer asked whether photographing a card from further away
     * changes what is read, and the answer turned out to depend on arithmetic nothing on the
     * phone was reporting: `inSampleSize` accepts only powers of two, so a 12 MP photograph is
     * **halved on each axis** to stay under a memory cap it then uses barely a third of. That
     * costs four times the pixels a card's text is read from. Dimensions are not content.
     */
    private fun decodeBitmap(uri: Uri): Bitmap? {
        // `decodeStream` returns **null by contract** when `inJustDecodeBounds` is set: the
        // answer comes back in `options`, not as a bitmap. So the null check here must guard
        // the *stream*, never the decode result — writing it as
        // `openStream(uri)?.use { decodeStream(...) } ?: return null` binds the elvis to the
        // decode and makes this function return null for every image ever passed to it. That
        // is not a hypothetical: it shipped, and it took a device to find, because
        // `BitmapFactory` is a throwing stub under JVM unit tests and the bounds pass is the
        // one call whose success looks exactly like failure.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val header = openStream(uri) ?: return null
        header.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val sample = sampleSizeFor(bounds.outWidth, bounds.outHeight)
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        log(
            "decode ${bounds.outWidth}x${bounds.outHeight} sample=$sample" +
                " -> ${bounds.outWidth / sample}x${bounds.outHeight / sample}" +
                " exif=${rotationDegreesFor(exifOrientation(uri))}"
        )
        return try {
            openStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        } catch (outOfMemory: OutOfMemoryError) {
            // A decode that misjudged its own bounds. Reported as unreadable rather than
            // taking the capture down with it: the user shared an image and deserves to be
            // told it could not be read, which is NFR-303's instinct a layer out from a write.
            null
        }
    }

    /**
     * The EXIF orientation tag, or normal where there is none.
     *
     * Read from a second stream, because `BitmapFactory` discards the tag. Screenshots carry
     * none; photographs of documents usually do, and an unread tag hands the recogniser a
     * sideways page — which returns nothing and looks like OCR simply not working.
     */
    private fun exifOrientation(uri: Uri): Int = try {
        openStream(uri)?.use {
            ExifInterface(it).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        } ?: ExifInterface.ORIENTATION_NORMAL
    } catch (unreadable: Exception) {
        // No EXIF segment, or a malformed one. Having no orientation is the common case and
        // is not an error.
        ExifInterface.ORIENTATION_NORMAL
    }

    private fun openStream(uri: Uri): InputStream? = try {
        context.contentResolver.openInputStream(uri)
    } catch (unreadable: Exception) {
        // A content URI whose grant has lapsed, or whose provider is gone. There is nothing
        // the caller could do differently; it is told the source is unreadable.
        null
    }

    private fun openDescriptor(uri: Uri): ParcelFileDescriptor? = try {
        context.contentResolver.openFileDescriptor(uri, "r")
    } catch (unreadable: Exception) {
        null
    }

    /** Idempotent, and does not construct a recogniser merely to close one. */
    override fun close() {
        latin?.close()
        latin = null
        devanagari?.close()
        devanagari = null
    }
}
