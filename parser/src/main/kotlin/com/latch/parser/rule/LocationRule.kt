package com.latch.parser.rule

import com.latch.parser.Confidence
import com.latch.parser.Field

/**
 * FR-502: location, where present.
 *
 * This one rule reads the *original* text rather than the normalised copy, because
 * capitalisation is most of the signal: "at Apollo Hospital" is a place, "at 11 am" is not.
 * Requiring an initial capital is also what keeps it from colliding with [TimeRule].
 */
internal object LocationRule {

    private val AT_PLACE = Regex(
        """\b(?:at|venue:?|location:?)\s+([A-Z][\w'&.-]*(?:\s+[A-Z][\w'&.-]*){0,3})""",
    )

    /** Words that follow "at" and are capitalised only because they start a sentence. */
    private val NOT_PLACES = setOf("The", "This", "That", "It", "We", "I")

    fun find(original: String): Field<String>? {
        val match = AT_PLACE.find(original) ?: return null
        val place = match.groupValues[1].trim()
        if (place in NOT_PLACES) return null
        return Field(value = place, confidence = Confidence.LOW, span = match.groups[1]!!.range)
    }
}
