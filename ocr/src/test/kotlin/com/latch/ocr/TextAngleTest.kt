package com.latch.ocr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * FR-1228's second half: the angle the writing actually runs at.
 *
 * **What this is for is reading *order*, not character recognition.** ML Kit reads turned text
 * perfectly well; what it cannot do is tell `inReadingOrder` which line comes first, because that
 * groups rows by vertical overlap of axis-aligned boxes — and every box on a turned card overlaps
 * every other. SRS 1.134 watched exactly that: three photographs of one card, three different
 * orders, and the contact nearly ended up called "Corporate Affairs".
 */
class TextAngleTest {

    private fun block(text: String, width: Int, angle: Double) =
        TextBlock(text, left = 0, top = 0, right = width, bottom = 30, angle = angle)

    @Test
    fun `level writing needs no correction`() {
        val level = listOf(block("Arvind Bhandari", 400, 0.0), block("Senior Manager", 380, 0.2))
        assertFalse(worthLevelling(dominantTextAngle(level)))
    }

    @Test
    fun `a card laid on its side is measured`() {
        val turned = List(4) { block("a line of the card", 400, 90.0) }
        assertEquals(90.0, dominantTextAngle(turned))
        assertTrue(worthLevelling(dominantTextAngle(turned)))
    }

    @Test
    fun `a modest tilt is measured rather than rounded away`() {
        val tilted = listOf(
            block("Arvind Bhandari", 400, 11.0),
            block("Senior General Manager", 500, 12.0),
            block("Bharat Limited", 380, 13.0),
        )
        assertEquals(12.0, dominantTextAngle(tilted))
        assertTrue(worthLevelling(dominantTextAngle(tilted)))
    }

    @Test
    fun `one crooked block cannot drag the answer`() {
        // **The reason this is a median and not a mean.** A logo caught as a character, or a stray
        // mark on the desk, comes back at some arbitrary angle. An average would let it tilt the
        // whole image; a median cannot see it at all.
        val mostlyLevel = listOf(
            block("Arvind Bhandari", 400, 0.0),
            block("Senior General Manager", 500, 0.5),
            block("Bharat Limited", 380, 0.0),
            block("logo fragment", 300, 47.0),
        )
        assertFalse(
            worthLevelling(dominantTextAngle(mostlyLevel)),
            "one crooked block turned an upright card",
        )
    }

    @Test
    fun `a box too narrow to have a baseline is ignored`() {
        // A two-character box has almost no baseline, so its angle is mostly noise. Without this
        // the median would be decided by whichever punctuation ML Kit boxed separately.
        val noisyShorts = listOf(
            block("Arvind Bhandari", 400, 0.0),
            block("m", 12, 63.0),
            block("e", 12, -71.0),
        )
        assertEquals(0.0, dominantTextAngle(noisyShorts))
    }

    @Test
    fun `nothing to measure is left alone`() {
        assertEquals(0.0, dominantTextAngle(emptyList()))
        assertFalse(worthLevelling(dominantTextAngle(emptyList())))
        // Every block too narrow is the same case, and must not become a rotation by accident.
        assertEquals(0.0, dominantTextAngle(listOf(block("m", 10, 80.0))))
    }

    @Test
    fun `the threshold leaves a slight tilt alone`() {
        // Rotating costs an allocation and a resample. `inReadingOrder` tolerates a degree or two
        // perfectly well; it is the larger turns that collapse every line into one row.
        assertFalse(worthLevelling(1.5))
        assertTrue(worthLevelling(2.0))
        assertTrue(worthLevelling(-2.0), "a turn the other way must count the same")
    }
}
