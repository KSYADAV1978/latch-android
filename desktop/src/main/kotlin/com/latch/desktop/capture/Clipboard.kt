package com.latch.desktop.capture

import java.awt.Robot
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.event.KeyEvent
import java.awt.image.BufferedImage
import java.awt.image.RenderedImage
import java.io.File
import javax.imageio.ImageIO

/** What the clipboard turned out to hold. */
sealed interface Clip {
    data class Text(val text: String) : Clip

    /** FR-303: a region snip, which is the supported path for anything not selectable. */
    data class Image(val image: BufferedImage) : Clip

    data object Empty : Clip
}

/**
 * FR-302's second half: synthesise a copy, then read what arrived.
 *
 * **The modifiers are released before Ctrl+C is sent, and that is not defensive tidying.** The
 * user is still physically holding Ctrl+Shift at the moment the hotkey fires, so a synthesised
 * Ctrl+C arrives at the foreground application as Ctrl+Shift+C — which in a browser opens the
 * developer tools and in several editors does something else entirely. Releasing Shift, Alt
 * and Win first is what makes the copy a copy.
 *
 * **The clipboard is not put back afterwards, and that is a recorded decision.** Restoring it
 * would be kinder to a user who had something valuable in there, and it was considered; it is
 * not done because a capture that fails to parse leaves the user wanting to paste what they
 * just selected, and because a restore racing the popup is a subtler bug than the loss it
 * prevents. The user pressed a key that copies; the selection being on the clipboard afterwards
 * is what any other copy would have done.
 */
class ClipboardCapture(
    private val clipboard: () -> java.awt.datatransfer.Clipboard = {
        Toolkit.getDefaultToolkit().systemClipboard
    },
    private val robot: () -> Robot = ::Robot,
    private val pause: (Long) -> Unit = Thread::sleep,
) {
    fun copySelection() {
        val machine = runCatching { robot() }.getOrElse { return }
        // Let go of what the user is still holding down. Order matters only in that Control is
        // pressed after, never before, these are released.
        listOf(KeyEvent.VK_SHIFT, KeyEvent.VK_ALT, KeyEvent.VK_META, KeyEvent.VK_WINDOWS).forEach {
            runCatching { machine.keyRelease(it) }
        }
        machine.keyPress(KeyEvent.VK_CONTROL)
        machine.keyPress(KeyEvent.VK_C)
        machine.keyRelease(KeyEvent.VK_C)
        machine.keyRelease(KeyEvent.VK_CONTROL)
        // The foreground application owns the clipboard and fills it asynchronously. Reading
        // immediately reads what was there before, which is the previous capture — the most
        // confusing failure this path has, because it looks like the app captured the wrong
        // thing rather than like it was too quick.
        pause(180)
    }

    fun read(): Clip {
        val board = runCatching { clipboard() }.getOrElse { return Clip.Empty }
        // The clipboard belongs to whatever last wrote to it and can be busy or gone between
        // the check and the read, so every access is guarded rather than tested first.
        runCatching {
            if (board.isDataFlavorAvailable(DataFlavor.stringFlavor)) {
                val text = board.getData(DataFlavor.stringFlavor) as? String
                if (!text.isNullOrBlank()) return Clip.Text(text)
            }
        }
        runCatching {
            if (board.isDataFlavorAvailable(DataFlavor.imageFlavor)) {
                (board.getData(DataFlavor.imageFlavor) as? java.awt.Image)?.let { image ->
                    return Clip.Image(toBuffered(image))
                }
            }
        }
        return Clip.Empty
    }
}

/**
 * Text wins over an image where the clipboard offers both.
 *
 * Applications routinely put both on: copying from a web page gives text, HTML and often a
 * rendering. Text is exact and free, and recognition of a rendering of the same words is
 * neither — so preferring the image would spend 600 ms to produce a worse version of something
 * already in hand. The order in [ClipboardCapture.read] is that rule, stated here because the
 * order of two `if`s is not a place anyone looks for a decision.
 */
internal fun toBuffered(image: java.awt.Image): BufferedImage {
    if (image is BufferedImage) return image
    val width = image.getWidth(null).coerceAtLeast(1)
    val height = image.getHeight(null).coerceAtLeast(1)
    val buffered = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    buffered.createGraphics().apply {
        drawImage(image, 0, 0, null)
        dispose()
    }
    return buffered
}

/**
 * Puts a clipboard image where FR-303's recogniser can read it.
 *
 * The recogniser takes a path because `Windows.Media.Ocr` decodes a file, and a temporary file
 * is how a clipboard bitmap becomes one. It is created with the restrictive permissions the
 * JDK gives a temp file, and **deleted as soon as recognition returns** — a capture is often a
 * screenshot of a private message, and leaving it in the temp directory would put on disk, in
 * the clear, exactly the content NFR-201 keeps off the network.
 */
fun writeForRecognition(image: RenderedImage, directory: File? = null): File {
    val file = File.createTempFile("latch-capture", ".png", directory)
    file.deleteOnExit()
    ImageIO.write(image, "png", file)
    return file
}
