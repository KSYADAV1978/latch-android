package com.latch.data

import com.latch.core.model.RoutingMode
import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun `rejects a record from a version that did not exist yet`() {
        assertNull(decodeDefaults(encodeDefaults(defaults).replaceFirst("1", "2")))
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
