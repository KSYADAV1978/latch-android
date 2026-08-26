package com.latch.data

import com.latch.core.model.CaptureSource

/**
 * FR-805 and FR-805a, composed in one place.
 *
 * FR-805 requires the source text and a link back to it in every item's description or notes.
 * FR-805a excludes the source text for captures from the notification listener, because a
 * Google item is persistent storage synchronised to every device on the account and NFR-206
 * forbids notification content reaching it.
 *
 * **The exclusion is structural, not a matter of care.** Call sites do not assemble this
 * block themselves and cannot forget the rule, in the same way that `SetupEffect.Commit`
 * being the only path to `calendars.insert` is what makes FR-105 hold. AC-22 — confirm a
 * notification capture, find its text nowhere in the created item — is a unit test over this
 * function.
 *
 * The **link** survives for every layer. It is provenance, not content: the source
 * application and the capture timestamp, which the item already carries under §7.2 anyway.
 */
fun sourceBlock(source: CaptureSource, sourceText: String, sourceLink: String? = null): String =
    buildList {
        if (source.storesSourceText) {
            sourceText.trim().takeIf { it.isNotEmpty() }?.let(::add)
        }
        sourceLink?.trim()?.takeIf { it.isNotEmpty() }?.let(::add)
    }.joinToString("\n\n")
