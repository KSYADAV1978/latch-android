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
     * The photograph at [uri], fitted and encoded, or null where it cannot be read.
     *
     * Null rather than an exception: FR-1226 says a photograph that fails must not fail the
     * contact, and the caller turns this into [CardPhotoUpload.FAILED] beside a successful save.
     */
    fun squareJpeg(uri: Uri): ByteArray? = runCatching {
        val source = decodeScaled(uri) ?: return null
        val rotated = applyExifRotation(uri, source)

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
