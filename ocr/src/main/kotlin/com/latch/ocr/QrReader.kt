package com.latch.ocr

import android.graphics.Bitmap
import android.net.Uri
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.NotFoundException
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

/**
 * FR-1203: the QR codes in an image, decoded on device.
 *
 * **`:app` sees this interface and never a ZXing type**, which is the containment `OcrReader`
 * already gives ML Kit and the reason that module's build file says "the blast radius stops
 * here". Replacing the decoder later is then a change to one file rather than to every call site.
 */
interface QrReader {
    /** Every QR payload found in the image, in no guaranteed order. */
    suspend fun readCodes(uri: Uri): QrResult
}

/**
 * What a decode found.
 *
 * **Several payloads is a real answer and not an error.** FR-1203 requires the *user* to choose
 * where an image carries more than one code — a poster with two cards on it, or a card beside a
 * Wi-Fi code — and picking one here would be the app deciding something the requirement gives to
 * the user.
 */
data class QrResult(
    val payloads: List<String> = emptyList(),
    val failure: QrFailure? = null,
) {
    val found: Boolean get() = payloads.isNotEmpty()
}

enum class QrFailure {
    /** The image could not be opened or decoded into pixels at all. */
    UNREADABLE_SOURCE,

    /** A perfectly good image with no QR code in it. */
    NO_CODE,
}

/**
 * ZXing, kept behind [QrReader].
 *
 * **Core only.** `zxing-android-embedded` is a different artifact carrying a camera Activity, and
 * this app already has an image — FR-1201 narrows Phase A to a shared one. What is needed is a
 * decoder, and core is +16 KB with no native code, against roughly +5.7 MB per device for ML
 * Kit's bundled barcode model.
 */
class ZxingQrReader(private val images: ImageSource) : QrReader {

    override suspend fun readCodes(uri: Uri): QrResult {
        val bitmap = images.decodeBitmap(uri) ?: return QrResult(failure = QrFailure.UNREADABLE_SOURCE)
        return try {
            decode(bitmap)?.let { QrResult(payloads = listOf(it)) }
                ?: QrResult(failure = QrFailure.NO_CODE)
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * One decode attempt.
     *
     * `TRY_HARDER` is on: a photographed card is rotated, creased and unevenly lit, which is the
     * ordinary case rather than the difficult one. The cost is time on an image with no code in
     * it, which is a path that ends in a message rather than in a capture.
     */
    private fun decode(bitmap: Bitmap): String? {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val binary = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(width, height, pixels)))
        val hints = mapOf(DecodeHintType.TRY_HARDER to true)
        return try {
            QRCodeReader().decode(binary, hints).text?.takeIf { it.isNotBlank() }
        } catch (notFound: NotFoundException) {
            // An image with no QR code is not a failure worth an exception upward: FR-1202 has
            // the user choosing the card path deliberately, so "no code here" is an answer.
            null
        } catch (malformed: Exception) {
            // ChecksumException, FormatException — a code that is present and damaged. Same
            // answer to the caller: nothing usable was read.
            null
        }
    }
}

/**
 * How the decoder gets pixels, so the ZXing half can be tested without an image pipeline.
 *
 * `BitmapFactory` is a throwing stub under JVM unit tests, and SRS records what that cost once
 * already: `decodeBitmap` returned null for every image through three commits with 328 tests
 * green, because an elvis guarded the decode result rather than the stream. Keeping the source
 * behind an interface is what lets the instrumented suite substitute a real asset.
 */
fun interface ImageSource {
    fun decodeBitmap(uri: Uri): Bitmap?
}
