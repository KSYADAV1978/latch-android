package com.latch.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
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
class MlKitOcrReader(private val context: Context) : OcrReader {

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
        val bitmap = decodeBitmap(uri)
            ?: return@withContext OcrResult.Failed(OcrFailure.UNREADABLE_SOURCE)
        val rotation = rotationDegreesFor(exifOrientation(uri))

        val text = try {
            recognise(bitmap, rotation)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return@withContext OcrResult.Failed(OcrFailure.RECOGNITION_FAILED)
        } finally {
            bitmap.recycle()
        }

        text.trim().takeIf(String::isNotEmpty)
            ?.let { OcrResult.Text(it) }
            ?: OcrResult.Failed(OcrFailure.NO_TEXT_FOUND)
    }

    override suspend fun readPdf(uri: Uri): OcrResult = withContext(Dispatchers.Default) {
        val descriptor = openDescriptor(uri)
            ?: return@withContext OcrResult.Failed(OcrFailure.UNREADABLE_SOURCE)

        val read = try {
            descriptor.use { fd -> PdfRenderer(fd).use { renderAndRecognise(it) } }
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
    private suspend fun renderAndRecognise(renderer: PdfRenderer): PagesRead {
        val total = renderer.pageCount
        val toRead = pagesToRead(total)
        val texts = ArrayList<String>(toRead)

        for (index in 0 until toRead) {
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
    private suspend fun recognise(bitmap: Bitmap, rotationDegrees: Int): String = coroutineScope {
        val image = InputImage.fromBitmap(bitmap, rotationDegrees)
        val latinPass = async { blocksOf(latin(), image) }
        val devanagariPass = async { blocksOf(devanagari(), image) }
        assemble(inReadingOrder(mergeByScript(latinPass.await(), devanagariPass.await())))
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
                            TextBlock(block.text, box.left, box.top, box.right, box.bottom)
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

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
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
