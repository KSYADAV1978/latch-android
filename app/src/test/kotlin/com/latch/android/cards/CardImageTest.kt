package com.latch.android.cards

import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * FR-1226's geometry.
 *
 * **The one thing worth asserting is that nothing is lost to the circle**, because that is the
 * whole reason the card is not simply letterboxed to the square's own edges. Every other property
 * here — centred, never upscaled, square canvas — is easy to get right and easy to check; the
 * circle is the one a reasonable implementation gets wrong.
 */
class CardImageTest {

    /** Does the whole placement lie inside the circle inscribed in the canvas? */
    private fun fitsTheCircle(p: Placement, canvas: Int = CONTACT_PHOTO_SIDE): Boolean {
        val centre = canvas / 2.0
        val radius = canvas / 2.0
        // The farthest points of a rectangle from the centre are its corners.
        return listOf(
            p.left to p.top,
            p.left + p.width to p.top,
            p.left to p.top + p.height,
            p.left + p.width to p.top + p.height,
        ).all { (x, y) ->
            // A pixel of slack: the placement is rounded to integers and the test is about the
            // rule, not about rounding.
            hypot(x - centre, y - centre) <= radius + 1.0
        }
    }

    @Test
    fun `a landscape card is not clipped by the circular crop`() {
        // The defect this exists to prevent. A 1.75:1 card scaled to the canvas WIDTH would put
        // its left and right ends outside the circle — and on a business card those are the
        // columns the telephone numbers are printed in.
        val placement = assertNotNull(letterboxPlacement(1750, 1000))
        assertTrue(fitsTheCircle(placement), "the card's corners fall outside Google's circle")
    }

    @Test
    fun `scaling to the full width would have been clipped`() {
        // Names the condition that makes the check meaningful, rather than asserting a rule
        // against a case that could not fail it. This is the arrangement the requirement rejects.
        val naiveWidth = CONTACT_PHOTO_SIDE
        val naiveHeight = CONTACT_PHOTO_SIDE * 1000 / 1750
        val naive = Placement(
            left = 0,
            top = (CONTACT_PHOTO_SIDE - naiveHeight) / 2,
            width = naiveWidth,
            height = naiveHeight,
        )
        assertTrue(!fitsTheCircle(naive), "the fixture cannot fail; the circle test proves nothing")
    }

    @Test
    fun `a tall card is not clipped either`() {
        // Portrait cards exist, and a rule written for landscape alone would fail them the other
        // way round.
        val placement = assertNotNull(letterboxPlacement(1000, 1750))
        assertTrue(fitsTheCircle(placement))
    }

    @Test
    fun `a square photograph is not clipped`() {
        val placement = assertNotNull(letterboxPlacement(1500, 1500))
        assertTrue(fitsTheCircle(placement))
    }

    @Test
    fun `an extreme panorama is still handled`() {
        // Somebody photographs a card at arm's length and crops it hard. The rule is arithmetic
        // and has no aspect ratio it stops working at, but a placement of zero width would.
        val placement = assertNotNull(letterboxPlacement(4000, 200))
        assertTrue(fitsTheCircle(placement))
        assertTrue(placement.width > 0 && placement.height > 0)
    }

    @Test
    fun `the card is centred to within a pixel`() {
        // **To within a pixel, and the tolerance is the world rather than slack.** The margins are
        // integer pixels and `canvas - width` is odd half the time, so one side is a pixel wider
        // than the other and no arithmetic avoids it. Asserting exact equality failed here on the
        // first run, and the honest fix was the assertion: a one-pixel difference on a 720-pixel
        // canvas is not a thing anybody can see, and forcing even widths to hide it would distort
        // the aspect ratio to flatter a test.
        val placement = assertNotNull(letterboxPlacement(1750, 1000))
        val right = CONTACT_PHOTO_SIDE - (placement.left + placement.width)
        val bottom = CONTACT_PHOTO_SIDE - (placement.top + placement.height)
        assertTrue(
            kotlin.math.abs(right - placement.left) <= 1,
            "margins ${placement.left} and $right are not a centred card",
        )
        assertTrue(
            kotlin.math.abs(bottom - placement.top) <= 1,
            "margins ${placement.top} and $bottom are not a centred card",
        )
    }

    @Test
    fun `the aspect ratio survives`() {
        // A card stretched to fit would be worse than one cropped: a squashed photograph looks
        // like the app damaged it, where a margin looks deliberate.
        val placement = assertNotNull(letterboxPlacement(1750, 1000))
        val ratio = placement.width.toDouble() / placement.height
        assertTrue(kotlin.math.abs(ratio - 1.75) < 0.02, "aspect ratio drifted to $ratio")
    }

    @Test
    fun `a small photograph is not blown up`() {
        // Upscaling would turn a small image into a blurred one, which reads as damage rather
        // than as a small photograph.
        val placement = assertNotNull(letterboxPlacement(200, 120))
        assertEquals(200, placement.width)
        assertEquals(120, placement.height)
        assertTrue(fitsTheCircle(placement))
    }

    @Test
    fun `a photograph with no pixels has no placement`() {
        assertNull(letterboxPlacement(0, 100))
        assertNull(letterboxPlacement(100, 0))
        assertNull(letterboxPlacement(100, 100, canvas = 0))
    }
}
