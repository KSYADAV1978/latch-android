package com.latch.android.capture

/**
 * What identifies *this* capture, for FR-807's undo offer and FR-1231's update offer.
 *
 * Both offers are held at application scope and keyed on this, so that an Activity recreation
 * keeps them while a genuinely new capture ends them (SRS 1.44, SRS 1.183). The key therefore has
 * to do two opposite things at once: be **stable** across a rotation, and **differ** between two
 * captures the user thinks of as separate.
 *
 * **A camera photograph's URI fails the second half, and that is SRS 1.206.** FR-1211 writes every
 * card photograph to `cache/camera/card-1.jpg` and sweeps the directory before the next capture,
 * so the file name — and with it the `FileProvider` URI — is **identical for every card anybody
 * photographs**. Two different cards in succession produced one key, so the second capture
 * restored the first one's outcome: the sheet opened on *"Saved to your contacts."* with `Close`,
 * the new card was never offered `Save contact`, and nothing was written for it while the screen
 * said otherwise.
 *
 * **The photograph's own stamp is what differs**, and it is stable in exactly the right way: a
 * rotation re-reads the same file and changes nothing, while a new capture has written a new one.
 * Adding a second side under FR-1225 leaves the first photograph untouched — the sweep runs at the
 * start of a capture, not per photograph — so a two-sided capture keeps one key throughout.
 *
 * **It is confined to the card path.** A shared image carries the sender's own URI, which already
 * distinguishes two captures, and widening this would make a key out of a file this app does not
 * own and cannot stat reliably.
 */
internal fun imageCaptureKey(uri: String, cardPath: Boolean, photoStamp: String?): String =
    if (!cardPath) "image:$uri" else "image:$uri:${photoStamp ?: NO_PHOTO}"

/**
 * Where the photograph could not be stated.
 *
 * **A constant, and never a fresh value per call.** Minting something unique here would make every
 * recreation a new key and silently defeat the offers this key exists to preserve — trading a
 * capture the user thinks is saved for an offer that vanishes on rotation, which is the same
 * class of harm one screen over.
 */
private const val NO_PHOTO = "no-photo"

/**
 * A photograph's identity in time: when it was written and how long it is.
 *
 * Two values rather than one because a camera can write two files inside a millisecond on a fast
 * device, and because a length alone says nothing — together they separate two photographs of two
 * different cards, which is all this has to do.
 */
internal fun photoStampOf(lastModified: Long, length: Long): String? =
    if (lastModified <= 0L && length <= 0L) null else "$lastModified/$length"
