package com.latch.desktop.ui

import com.latch.core.model.ItemType
import com.latch.desktop.capture.DesktopCapture
import com.latch.desktop.capture.EmptyCapture
import com.latch.parser.DateParser
import com.latch.parser.ParseContext
import java.awt.Dimension
import java.awt.Point
import java.awt.Rectangle
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PopupModelTest {

    private val now = LocalDateTime.of(2026, 9, 2, 12, 0)
    private val today: LocalDate = now.toLocalDate()
    private val context = ParseContext(now = now)

    private fun model(
        text: String,
        ocr: Boolean = false,
        selected: Set<Int>? = null,
        titles: Map<Int, String> = emptyMap(),
        types: Map<Int, ItemType> = emptyMap(),
    ): PopupModel {
        val capture = DesktopCapture(text = text, ocrUsed = ocr)
        val result = DateParser.parse(text, context)
        return popupModel(
            capture, result, selected ?: result.candidates.indices.toSet(), today, titles, types,
        )
    }

    @Test
    fun `a single dated capture shows one ticked event row`() {
        val popup = model("Kickoff 8 September 2027 at 9am")
        assertEquals(1, popup.rows.size)
        val row = popup.rows.single()
        assertEquals(DesktopStrings.BADGE_EVENT, row.badge)
        // The month's abbreviation is the JVM's for the machine's locale — "Sep" on some,
        // "Sept" on others — so what is asserted is the day, the year and the time, which is
        // what the line is for. Pinning the abbreviation would pin the platform's CLDR data.
        assertTrue(row.whenLine.startsWith("8 Sep"), row.whenLine)
        assertTrue(row.whenLine.endsWith("2027, 09:00"), row.whenLine)
        assertTrue(row.checked)
        assertTrue(popup.canSave)
        assertNull(popup.blocker)
    }

    @Test
    fun `FR-511 gives every date its own row, all ticked to begin with`() {
        val popup = model("PTM on 14 September 2026. Fees due 20/09/2027. Trip 1 October 2027.")
        assertTrue(popup.rows.size >= 3, "expected a row per date, got " + popup.rows.size)
        assertTrue(popup.rows.all { it.checked })
        assertTrue(popup.summary.contains(popup.rows.size.toString()), popup.summary)
    }

    @Test
    fun `unticking everything blocks the save and says why`() {
        // The condition that makes this fail: a Save that stays enabled and writes nothing.
        val popup = model("Kickoff 8 September 2027 at 9am", selected = emptySet())
        assertFalse(popup.canSave)
        assertEquals(DesktopStrings.NOTHING_TICKED, popup.blocker)
    }

    @Test
    fun `a capture with no date becomes an undated to-do rather than an invented date`() {
        // AC-03's text, and the answer here differs from Android's on purpose.
        //
        // On the phone this routes to the FR-700 Capture Inbox and nothing reaches Google.
        // **There is no Inbox on the desktop yet**, so the choice was between losing the
        // capture and writing what Google Tasks is perfectly willing to hold: a to-do with no
        // due date. Design principle 1 settles that — the capture is not lost — and it is
        // FR-512's interim reading, which SRS 1.43 retired on Android the moment the Inbox
        // landed and which stands here until the same thing is built.
        //
        // What must hold either way is the half design principle 1 is stricter about: no date
        // was found, so no date is shown.
        val popup = model("Ask about the uniform order")
        val row = popup.rows.single()
        assertEquals(DesktopStrings.BADGE_TASK, row.badge)
        assertEquals(DesktopStrings.NO_DAY_YET, row.whenLine)
        assertTrue(popup.canSave, "an undated to-do is a legitimate item, not a blocked one")
        assertTrue(
            popup.rows.none { it.whenLine.any(Char::isDigit) },
            "a row showed a date for a capture that has none: " + popup.rows.map { it.whenLine },
        )
    }

    @Test
    fun `FR-510 a past date is marked, and its type cannot be changed`() {
        // FR-510 outranks FR-507: a past date becomes an undated to-do, so offering to make it
        // an event would offer something the requirement forbids.
        val popup = model("the order dated 12 March")
        val row = popup.rows.firstOrNull()
        assertNotNull(row, "expected a candidate for a past date")
        assertFalse(row.canOverride, "a past date must not be switchable to an event")
        assertEquals(DesktopStrings.PAST_DATE, row.note)
    }

    @Test
    fun `FR-507 an override changes the badge without touching the date`() {
        val plain = model("Kickoff 8 September 2027 at 9am")
        val flipped = model("Kickoff 8 September 2027 at 9am", types = mapOf(0 to ItemType.TASK))
        assertEquals(DesktopStrings.BADGE_EVENT, plain.rows.single().badge)
        assertEquals(DesktopStrings.BADGE_TASK, flipped.rows.single().badge)
        assertEquals(plain.rows.single().whenLine, flipped.rows.single().whenLine)
    }

    @Test
    fun `FR-509b an edited title is what the row shows`() {
        val popup = model("Kickoff 8 September 2027 at 9am", titles = mapOf(0 to "MTG to review PMGATI"))
        assertEquals("MTG to review PMGATI", popup.rows.single().title)
        assertEquals("MTG to review PMGATI", popup.title)
    }

    @Test
    fun `FR-502 a range reads as a span rather than as its first day`() {
        val popup = model("Trip from 20 September to 24 September 2027")
        val line = popup.rows.single().whenLine
        assertTrue(line.startsWith("20 Sep"), line)
        assertTrue(line.contains("–"), "a range must read as a span: " + line)
        assertTrue(line.trimEnd().endsWith("24 Sept 2027") || line.trimEnd().endsWith("24 Sep 2027"), line)
    }

    @Test
    fun `a time with no day says so rather than showing a date it invented`() {
        // Design principle 1 at the level of a label: no date found means no date shown.
        val popup = model("call at 4pm")
        val row = popup.rows.firstOrNull()
        if (row != null) {
            assertEquals(DesktopStrings.NO_DAY_YET, row.whenLine)
            assertEquals(DesktopStrings.PICK_A_DAY, row.note)
        }
    }

    @Test
    fun `every empty-capture reason has something a person could act on`() {
        EmptyCapture.entries.forEach { reason ->
            val message = messageFor(reason)
            assertTrue(message.length > 20, reason.name + " says only: " + message)
            assertFalse(message.contains("_"), reason.name + " leaked an enum name: " + message)
        }
    }

    @Test
    fun `the machine with no language pack is told where to add one`() {
        // The asymmetry with Android reaching the user, which is the whole reason
        // NO_RECOGNISER is its own case.
        assertTrue(messageFor(EmptyCapture.NO_RECOGNISER).contains("Language"), messageFor(EmptyCapture.NO_RECOGNISER))
    }

    // ---- placement ----------------------------------------------------------------------------

    @Test
    fun `the popup is placed near the cursor`() {
        val at = nearCursor(Dimension(400, 300), Point(500, 400), Rectangle(0, 0, 1920, 1040))
        assertEquals(Point(512, 412), at)
    }

    @Test
    fun `a cursor near the edge does not push the popup off the screen`() {
        // The desktop version of the defect the Android sheet had at a large font scale: a
        // window half off the bottom is a window whose Save button cannot be clicked.
        val screen = Rectangle(0, 0, 1920, 1040)
        val size = Dimension(400, 300)
        val at = nearCursor(size, Point(1910, 1035), screen)
        assertTrue(at.x + size.width <= screen.width, "ran off the right: " + at)
        assertTrue(at.y + size.height <= screen.height, "ran off the bottom: " + at)
    }

    @Test
    fun `a second monitor to the left keeps the popup on that monitor`() {
        val screen = Rectangle(-1920, 0, 1920, 1080)
        val at = nearCursor(Dimension(400, 300), Point(-1900, 40), screen)
        assertTrue(at.x >= screen.x, "left the monitor: " + at)
        assertEquals(-1888, at.x)
    }

    @Test
    fun `a popup larger than the screen is pinned to the origin rather than to a negative`() {
        val at = nearCursor(Dimension(2000, 2000), Point(10, 10), Rectangle(0, 0, 1920, 1040))
        assertEquals(Point(0, 0), at)
    }
}

class TrayMenuTest {

    @Test
    fun `the menu leads with capture and names the shortcut`() {
        val entries = trayMenu(TrayModel("Ctrl+Shift+K", signedInAs = null, configured = true, pending = 0))
        assertEquals(TrayAction.CAPTURE, entries.first().id)
        assertTrue(entries.first().label.contains("Ctrl+Shift+K"), entries.first().label)
    }

    @Test
    fun `with no client configured the menu says so rather than offering a sign-in that cannot work`() {
        val entries = trayMenu(TrayModel("Ctrl+Shift+K", signedInAs = null, configured = false, pending = 0))
        val signIn = entries.single { it.id == TrayAction.SIGN_IN }
        assertFalse(signIn.enabled)
        assertTrue(signIn.label.contains("configured"), signIn.label)
    }

    @Test
    fun `signed in, the menu offers to sign out and names the account`() {
        val entries = trayMenu(TrayModel("Ctrl+Shift+K", signedInAs = "a@example.com", configured = true, pending = 0))
        val out = entries.single { it.id == TrayAction.SIGN_OUT }
        assertTrue(out.label.contains("a@example.com"), out.label)
        assertTrue(entries.none { it.id == TrayAction.SIGN_IN })
    }

    @Test
    fun `a queue with nothing in it is not mentioned at all`() {
        // FR-704's instinct, one client over: a count of zero is a thing to say nothing about.
        val quiet = trayMenu(TrayModel("Ctrl+Shift+K", "a@example.com", configured = true, pending = 0))
        assertTrue(quiet.none { it.label.contains("waiting") }, quiet.joinToString { it.label })

        val busy = trayMenu(TrayModel("Ctrl+Shift+K", "a@example.com", configured = true, pending = 3))
        assertTrue(busy.any { it.label.contains("3 waiting") }, busy.joinToString { it.label })
        assertTrue(busy.single { it.id == TrayAction.RETRY }.enabled, "the count must be tappable")
    }

    @Test
    fun `a stuck entry is named apart from one that is merely waiting`() {
        // The one queue state the user has to act on. An entry retries have stopped for is
        // never deleted — it holds a capture that exists nowhere else — so the only way it
        // ever moves is if they are told it is there.
        val stuck = trayMenu(TrayModel("Ctrl+Shift+K", "a@e.com", true, pending = 0, givenUp = 2))
        assertTrue(stuck.any { it.label.contains("2 stuck") }, stuck.joinToString { it.label })
        assertTrue(stuck.any { it.id == TrayAction.RETRY })

        val both = trayMenu(TrayModel("Ctrl+Shift+K", "a@e.com", true, pending = 1, givenUp = 2))
        assertTrue(both.single { it.id == TrayAction.RETRY }.label.contains("1 waiting"))
        assertTrue(both.single { it.id == TrayAction.RETRY }.label.contains("2 stuck"))
    }

    @Test
    fun `quit is always the last thing offered`() {
        listOf(0, 3).forEach { pending ->
            val entries = trayMenu(TrayModel("Ctrl+Shift+K", "a@example.com", true, pending))
            assertEquals(TrayAction.QUIT, entries.last().id)
        }
    }

    @Test
    fun `the icon is drawn at whatever size the tray asks for`() {
        // Windows asks for different sizes on a mixed-DPI desktop, which is why it is drawn
        // rather than shipped as one bitmap.
        listOf(16, 20, 24, 32, 48).forEach { size ->
            val image = latchIcon(size)
            assertEquals(size, image.width)
            assertEquals(size, image.height)
        }
    }
}
