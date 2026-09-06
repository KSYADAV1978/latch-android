package com.latch.android.help

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * FR-204. **The failure this topic has to survive is being empty**, which is what a list of other
 * people's software becomes when nobody keeps it: the requirement's sentence is met by a screen
 * that exists and the user learns nothing. So the assertion is on the list rather than on the
 * screen — Compose is not JVM-reachable here, and what the screen renders is this.
 */
class TextSelectionHelpTest {

    @Test
    fun `the topic lists at least one application`() {
        assertTrue(KNOWN_NON_COOPERATING.isNotEmpty())
    }

    @Test
    fun `every listed application says why`() {
        // Exhaustiveness is the compiler's — `explanationFor` is a `when` over the enum with no
        // else branch — so what is left to assert is that the two reasons do not collapse onto
        // one sentence, which would silently lose the distinction the enum exists to draw.
        assertNotEquals(
            explanationFor(SelectionFailure.NO_TEXT_SELECTION),
            explanationFor(SelectionFailure.NO_PACKAGE_VISIBILITY),
        )
        KNOWN_NON_COOPERATING.forEach { app ->
            assertNotEquals(0, app.nameRes)
            assertNotEquals(0, explanationFor(app.reason))
        }
    }

    @Test
    fun `no application is listed twice`() {
        assertEquals(
            KNOWN_NON_COOPERATING.size,
            KNOWN_NON_COOPERATING.map { it.nameRes }.toSet().size,
        )
    }

    @Test
    fun `WhatsApp is listed for the reason SRS 8_3 gives`() {
        // §8.3: "Apps that select whole messages rather than text ranges (notably WhatsApp)".
        // Not a package-visibility case, and the difference is what the screen tells the user:
        // this one no app update will fix.
        val whatsApp = KNOWN_NON_COOPERATING.single { it.nameRes == com.latch.android.R.string.help_selection_app_whatsapp }
        assertEquals(SelectionFailure.NO_TEXT_SELECTION, whatsApp.reason)
    }
}
