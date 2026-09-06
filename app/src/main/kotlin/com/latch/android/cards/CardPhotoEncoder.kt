package com.latch.android.cards

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.media.ExifInterface
import android.net.Uri
import com.latch.ocr.PixelRect
import com.latch.ocr.worthLevelling
import java.io.ByteArrayOutputStream

/**
 * FR-1226's Android half: a card photograph becomes a square JPEG.
 *
 * **Deliberately thin.** Everything decided lives in `CardImage.kt`, where a JVM test can reach
 * it; what is here is decode, draw, encode — the three calls that cannot be tested without a
 * device, and which this project has been bitten by before. `BitmapFactory` is a throwing stub
 * under unit tests and its `decodeStream` returns null *by contract* under `inJustDecodeBounds`,
 * which shipped a dead image path for a whole slice. The instrumented suite is where this belongs.
 */
class CardPhotoEncoder(private val context: Context) {

    /**
     * FR-1229: the photograph as the recogniser saw it, small enough to sit on the sheet.
     *
     * **Levelled by [textAngle], which is the point.** The camera application's own confirm screen
     * showed a card upside down and further away than it had been framed, and nothing the app
     * displayed could say which of those two pictures had reached the reader — so a preview that
     * merely repeated the file would reassure without verifying. Turning it by the angle the
     * reader actually applied makes the thumbnail a statement about the pipeline rather than
     * about the camera.
     *
     * **It is not retained** (FR-1211). This is a bitmap in memory for as long as the sheet is on
     * screen, from a file that is deleted when the capture closes.
     */
    fun preview(
        uri: Uri,
        textAngle: Double,
        region: PixelRect? = null,
        side: Int = PREVIEW_SIDE,
    ): Bitmap? = asSeen(uri, textAngle, region, side)

    /**
     * The photograph as the reader saw it: cropped to the region it read, levelled by its angle.
     *
     * **One function, because there are two places that must not disagree** (SRS 1.148). FR-1229's
     * thumbnail is what the user checks; FR-1226's contact picture is what the account keeps, and
     * they were composed by different code that made different choices — the picture applied the
     * EXIF turn and no crop, the thumbnail the crop and no EXIF. A user who approved a cropped,
     * levelled card got an uncropped one in their contacts. That is SRS 1.142's defect a second
     * time and in the more expensive direction, since a contact photograph outlives the sheet.
     */
    private fun asSeen(
        uri: Uri,
        textAngle: Double,
        region: PixelRect?,
        side: Int,
    ): Bitmap? = runCatching {
        // **Cropped to what the reader read, where it read a crop** (SRS 1.143). Showing the whole
        // frame showed something the recogniser only partly used — and at thumbnail size a card
        // occupying two fifths of a frame is a stamp nobody can check their fields against. The
        // crop puts the card across the whole thumbnail, and a crop that went wrong shows itself
        // rather than hiding in a log.
        val decoded = (region?.let { decodeRegionAtMost(uri, it, side) } ?: decodeAtMost(uri, side))
            ?: return null
        // **The EXIF turn is deliberately not applied, and the first version applied it** (SRS
        // 1.142). The angle is measured in the file's own frame, so it already contains whatever
        // EXIF contributed — turning by both put the preview 180 degrees out and the card sideways
        // on screen. That is `levelled`'s rule, and the preview is only honest if it is the same
        // rule: a thumbnail turned differently from the image the reader saw would be a picture of
        // something that never happened.
        if (!worthLevelling(textAngle)) return decoded
        Bitmap.createBitmap(
            decoded, 0, 0, decoded.width, decoded.height,
            Matrix().apply { postRotate(-textAngle.toFloat()) },
            true,
        )
    }.getOrNull()

    /** One rectangle of the file, no larger than a thumbnail needs. See [preview]. */
    @Suppress("DEPRECATION")
    private fun decodeRegionAtMost(uri: Uri, region: PixelRect, side: Int): Bitmap? = try {
        var sample = 1
        while (region.width / (sample * 2) >= side && region.height / (sample * 2) >= side) {
            sample *= 2
        }
        openStream(uri)?.use { stream ->
            val decoder = android.graphics.BitmapRegionDecoder.newInstance(stream, false)
            try {
                decoder?.decodeRegion(
                    android.graphics.Rect(region.left, region.top, region.right, region.bottom),
                    BitmapFactory.Options().apply {
                        inSampleSize = sample
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    },
                )
            } finally {
                decoder?.recycle()
            }
        }
    } catch (unreadable: Exception) {
        // A format the region decoder will not open. The whole frame is still a fair preview.
        null
    } catch (outOfMemory: OutOfMemoryError) {
        null
    }

    /** Decoded no larger than a thumbnail needs; the bounds pass allocates nothing. */
    private fun decodeAtMost(uri: Uri, side: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val header = openStream(uri) ?: return null
        header.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= side && bounds.outHeight / (sample * 2) >= side) {
            sample *= 2
        }
        return openStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            })
        }
    }

    /**
     * The photograph at [uri], fitted and encoded, or null where it cannot be read.
     *
     * Null rather than an exception: FR-1226 says a photograph that fails must not fail the
     * contact, and the caller turns this into [CardPhotoUpload.FAILED] beside a successful save.
     */
    fun squareJpeg(
        uri: Uri,
        textAngle: Double = 0.0,
        region: PixelRect? = null,
    ): ByteArray? = runCatching {
        // **What the sheet showed, at the size a contact photograph needs** (SRS 1.148). This
        // read the file and applied the EXIF turn until the user pointed out that the picture in
        // their account was uncropped while the thumbnail they had approved was not. Falling back
        // to the old path where the reading gives nothing to go on keeps a photograph rather than
        // dropping one, which is FR-1226's own rule about failure.
        val rotated = asSeen(uri, textAngle, region, CONTACT_PHOTO_SIDE)
            ?: decodeScaled(uri)?.let { applyExifRotation(uri, it) }
            ?: return null

        val placement = letterboxPlacement(rotated.width, rotated.height) ?: return null
        val canvasBitmap = Bitmap.createBitmap(
            CONTACT_PHOTO_SIDE,
            CONTACT_PHOTO_SIDE,
            Bitmap.Config.ARGB_8888,
        )
        Canvas(canvasBitmap).apply {
            // White and not transparent. A JPEG has no alpha, so a transparent margin encodes as
            // black — a card in a black circle, which reads as a rendering fault rather than as a
            // card that did not fill the frame.
            drawColor(Color.WHITE)
            drawBitmap(
                rotated,
                null,
                Rect(
                    placement.left,
                    placement.top,
                    placement.left + placement.width,
                    placement.top + placement.height,
                ),
                Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG),
            )
        }

        ByteArrayOutputStream().use { out ->
            canvasBitmap.compress(Bitmap.CompressFormat.JPEG, CONTACT_PHOTO_QUALITY, out)
            out.toByteArray()
        }
    }.getOrNull()

    /**
     * Decoded no larger than it needs to be.
     *
     * A card photograph off a modern phone is twelve megapixels and the canvas is 720 square, so
     * decoding at full size would allocate tens of megabytes to throw away — on the capture path,
     * where NFR-101 is already tight.
     *
     * **The null check guards the stream and never the decode result** (SRS: `:ocr`'s own note).
     * `decodeStream` returns null by contract under `inJustDecodeBounds`, so writing this as
     * `openStream(uri)?.use { decodeStream(...) } ?: return null` binds the elvis to the decode
     * and makes the function return null for every image ever passed to it. That is not
     * hypothetical: it shipped in `:ocr` and took a device to find.
     */
    private fun decodeScaled(uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val header = openStream(uri) ?: return null
        header.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (
            bounds.outWidth / (sample * 2) >= CONTACT_PHOTO_SIDE &&
            bounds.outHeight / (sample * 2) >= CONTACT_PHOTO_SIDE
        ) {
            sample *= 2
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return openStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    }

    /**
     * EXIF orientation, applied.
     *
     * The same tag `:ocr` reads, and for a related reason recorded there: a photograph taken in
     * portrait is stored landscape with a tag, and an unread tag here would put a sideways card
     * on somebody's contact — visibly wrong in a way the user would blame on Latch.
     */
    private fun applyExifRotation(uri: Uri, bitmap: Bitmap): Bitmap {
        val orientation = runCatching {
            openStream(uri)?.use {
                ExifInterface(it).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            }
        }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL

        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> return bitmap
        }
        return Bitmap.createBitmap(
            bitmap,
            0,
            0,
            bitmap.width,
            bitmap.height,
            Matrix().apply { postRotate(degrees) },
            true,
        )
    }

    private fun openStream(uri: Uri) = runCatching {
        context.contentResolver.openInputStream(uri)
    }.getOrNull()
}

/** FR-1229: big enough to judge framing and orientation, small enough to decode on the sheet. */
const val PREVIEW_SIDE: Int = 480

/**
 * FR-1229: one photograph as the reader saw it, and which photograph it was.
 *
 * **The number is the file's position and not the preview's** (SRS 1.145). A side the recogniser
 * found nothing in produces no preview, and numbering the previews rather than the photographs
 * would then label the second card "Photo 1" — which is the confusion this pairs the two against.
 */
data class CardPreview(
    val side: Int,
    val image: Bitmap,
    /**
     * The turn and the crop this thumbnail was made with (SRS 1.148).
     *
     * **Carried on the preview rather than looked up again**, so FR-1226's contact picture is
     * composed from the same three values as the thumbnail the user approved and cannot drift
     * from it. Looking them up a second time is exactly how the two came to disagree.
     */
    val textAngle: Double = 0.0,
    val region: PixelRect? = null,
)
