package com.latch.core.model

/**
 * FR-905: under Option A, routing rules are definable by source application and by recipe.
 * FR-907 may *offer* to create one of these; it never creates one automatically.
 */
data class RoutingRule(
    val id: String,
    val matchType: MatchType,
    val matchValue: String,
    val calendarId: String,
    val priority: Int,
    /**
     * The destination's name and colour, stored rather than fetched.
     *
     * The same reasoning `AccountDefaults` records for its own copy: FR-904 wants the
     * destination on screen as a chip in its own colour **before** the user confirms, and
     * NFR-101 gives the whole capture path 800 ms. A `calendarList` round-trip per capture
     * would spend most of that budget on a value that changes about never. Taken from a
     * calendar the user picked in Settings, which is also what FR-906 means by never routing
     * somewhere they have not seen.
     */
    val calendarName: String = "",
    /** `#rrggbb`, or empty where Google gave none — an unparseable colour renders grey. */
    val calendarColour: String = "",
)

enum class MatchType { SOURCE_APP, RECIPE, KEYWORD }

/** FR-103: the two setup destinations. Option B is the pre-selected default. */
enum class RoutingMode {
    /** Option B — a dedicated Latch calendar receives everything. */
    LATCH_CALENDAR,

    /** Option A — captures are routed to the user's existing calendars per §5.10. */
    EXISTING_CALENDARS,
}
