package com.latch.wire

import com.latch.core.model.LatchSettings
import com.latch.parser.Confidence
import com.latch.parser.DateOrder
import com.latch.parser.ParseContext
import java.time.Instant
import java.time.ZoneId

/**
 * FR-1001's settings, turned into everything the parser is allowed to know (FR-515).
 *
 * **Shared because a parse that two machines read differently is the whole of the drift this
 * module exists against.** SRS 1.59 asked `ItemKeyAcrossClientsTest` whether any field of
 * `ParseContext` could move a span and found that none can — but that is a fact about the
 * *fields*, and it says nothing about two clients filling them from the same settings record in
 * two different ways. FR-504's date order is the one most likely to differ between somebody's
 * phone and their desktop, and a client that forgot to read it would silently read `05/09` the
 * other way round. One function removes the question.
 *
 * **The zone falls back to the machine's, and a stored zone that no longer parses does too.** A
 * user who moved a settings record between machines, or who named a zone this JVM's tzdata does
 * not carry, gets their captures resolved locally rather than a failure they cannot act on —
 * and FR-1001's own default is null, meaning "follow the device", for exactly that reason.
 */
fun parseContextFor(settings: LatchSettings, capturedAt: Instant): ParseContext {
    val zone = settings.timeZone
        ?.let { runCatching { ZoneId.of(it) }.getOrNull() }
        ?: ZoneId.systemDefault()
    return ParseContext(
        now = capturedAt.atZone(zone).toLocalDateTime(),
        zone = zone,
        dateOrder = if (settings.dayFirstDates) DateOrder.DAY_FIRST else DateOrder.MONTH_FIRST,
        defaultEventDuration = settings.defaultEventDuration,
        confidenceThreshold = Confidence(settings.confidenceThreshold),
    )
}
