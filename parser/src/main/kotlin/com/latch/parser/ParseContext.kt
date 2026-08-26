package com.latch.parser

import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Everything the parser is allowed to know about the world outside the captured string.
 *
 * [now] is passed in rather than read from a clock so that relative references ("kal",
 * "next Monday") are deterministic under test — AC-13 is untestable otherwise, and design
 * principle 1 ("never invent a date") cannot be enforced against a moving clock.
 */
data class ParseContext(
    val now: LocalDateTime,
    val zone: ZoneId = ZoneId.systemDefault(),
    /** FR-504. Defaults to DD/MM for Indian locales; user-visible and user-changeable. */
    val dateOrder: DateOrder = DateOrder.DAY_FIRST,
    /** FR-1001: default event duration, applied when a start time is found but no end. */
    val defaultEventDuration: Duration = Duration.ofHours(1),
    /** FR-512: below this, the item goes to the Capture Inbox rather than being saved. */
    val confidenceThreshold: Confidence = Confidence(0.6),
)

/** FR-504: how `05/09` is read. */
enum class DateOrder { DAY_FIRST, MONTH_FIRST }
