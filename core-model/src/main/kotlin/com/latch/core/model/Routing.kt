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
)

enum class MatchType { SOURCE_APP, RECIPE, KEYWORD }

/** FR-103: the two setup destinations. Option B is the pre-selected default. */
enum class RoutingMode {
    /** Option B — a dedicated Latch calendar receives everything. */
    LATCH_CALENDAR,

    /** Option A — captures are routed to the user's existing calendars per §5.10. */
    EXISTING_CALENDARS,
}
