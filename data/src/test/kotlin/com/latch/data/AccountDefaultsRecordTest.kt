package com.latch.data

import com.latch.core.model.RoutingMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The stored record is the one place where persisted defaults can be silently corrupted:
 * a bad decode does not throw, it produces the wrong destination. Encryption is not under
 * test here — [KeystoreCipher] needs a device — only the format inside it.
 */
class AccountDefaultsRecordTest {

    private val defaults = AccountDefaults(
        accountId = "104729581037465920184",
        email = "you@example.com",
        routingMode = RoutingMode.LATCH_CALENDAR,
        destinationCalendarId = "latch-1",
        destinationCalendarName = "Latch",
        destinationCalendarColour = "#d50000",
        taskListId = "default",
    )

    @Test
    fun `round trips`() {
        assertEquals(defaults, decodeDefaults(encodeDefaults(defaults)))
    }

    @Test
    fun `round trips the other routing mode`() {
        val optionA = defaults.copy(
            routingMode = RoutingMode.EXISTING_CALENDARS,
            destinationCalendarId = "primary",
        )
        assertEquals(optionA, decodeDefaults(encodeDefaults(optionA)))
    }

    @Test
    fun `a version 1 record is unreadable, which is the whole migration`() {
        // What a v1 record looked like: no destination name, no colour. It decodes to null,
        // read() deletes it as unreadable, and setup runs once more (FR-101). That is the
        // behaviour the leading version field was added for, and it is why there is no
        // migration code — a choice that stops being acceptable once there are users.
        val separator = Char(0x1F).toString()
        val version1 = listOf(
            "1",
            defaults.accountId,
            defaults.email,
            defaults.routingMode.name,
            defaults.destinationCalendarId,
            defaults.taskListId,
        ).joinToString(separator)

        assertNull(decodeDefaults(version1))
    }

    @Test
    fun `the destination's name and colour survive the round trip`() {
        // FR-904's chip is drawn from these without a network call, so if they do not
        // round-trip the chip is blank on every capture.
        val decoded = assertNotNull(decodeDefaults(encodeDefaults(defaults)))
        assertEquals("Latch", decoded.destinationCalendarName)
        assertEquals("#d50000", decoded.destinationCalendarColour)
    }

    @Test
    fun `a destination with no colour round trips as empty`() {
        // Google does not always give a colour, and the patch that would have supplied one
        // for a new Latch calendar is best-effort. Empty must survive rather than shift the
        // fields, which an omitted value would.
        val noColour = defaults.copy(destinationCalendarColour = "")
        assertEquals(noColour, decodeDefaults(encodeDefaults(noColour)))
    }

    @Test
    fun `rejects a record from a version that did not exist yet`() {
        // The version field is replaced by position, not by searching the text for the old
        // number. Searching worked only while the version happened to be "1" and nothing
        // else in the record began with it — at version 2 it rewrites the account id
        // instead and produces a perfectly valid record.
        val separator = Char(0x1F).toString()
        val fromTheFuture = encodeDefaults(defaults)
            .split(separator)
            .toMutableList()
            .also { it[0] = "99" }
            .joinToString(separator)

        assertNull(decodeDefaults(fromTheFuture))
    }

    @Test
    fun `rejects a truncated record rather than shifting the fields`() {
        val short = encodeDefaults(defaults).substringBeforeLast(Char(0x1F))
        assertNull(decodeDefaults(short))
    }

    @Test
    fun `rejects a routing mode this version does not know`() {
        val unknown = encodeDefaults(defaults).replace("LATCH_CALENDAR", "SOME_LATER_MODE")
        assertNull(decodeDefaults(unknown))
    }

    @Test
    fun `rejects anything that is not a record at all`() {
        assertNull(decodeDefaults(""))
        assertNull(decodeDefaults("you@example.com"))
    }
}
