package com.latch.android.help

import com.latch.android.R

/**
 * FR-204's in-app help topic: why Latch is sometimes absent from the text-selection toolbar.
 *
 * **Two reasons, deliberately not merged into one.** §8.3 names both and they lead somewhere
 * different for the person reading. An application that uses standard text selection but has not
 * declared `<queries>` for `ACTION_PROCESS_TEXT` *could* declare it — naming that reason is worth
 * something to a user who can ask its authors. An application that never selects a range of text
 * at all cannot be fixed by anybody: no package-visibility declaration and no Android version
 * changes it, and saying so is what stops the user trying again next month.
 *
 * **The list is short because it is observed rather than composed** (SRS 1.150). WhatsApp is on
 * it because §8.3 has named it since this specification was written. Nothing else is, and a
 * plausible fifth entry would be a claim about somebody else's software that nobody here has
 * checked — the corpus trap (NFR-502, FR-1222) one requirement over.
 */
enum class SelectionFailure {
    /**
     * The application selects whole messages rather than ranges of text, so there is no text
     * selection for the system toolbar to hang an action on. Outside anyone's control.
     */
    NO_TEXT_SELECTION,

    /**
     * The application targets Android 11 or later and has not declared a `<queries>` element for
     * `ACTION_PROCESS_TEXT`, so Android hides every third-party text action from it — Latch's
     * included, and Latch cannot declare this on another app's behalf.
     */
    NO_PACKAGE_VISIBILITY,
}

/**
 * One row of the topic. The name is a string resource rather than a literal because NFR-402 puts
 * every user-facing string in `strings.xml`; the pairing of name to reason is Kotlin, because
 * that pairing is the decision and a resource file cannot hold one.
 */
data class NonCooperatingApp(val nameRes: Int, val reason: SelectionFailure)

/**
 * FR-204: "Known non-cooperating apps shall be listed in an in-app help topic."
 *
 * **Add to this only from observation.** The evidence is a device: select text in the application
 * and look for Latch in the toolbar. `docs/DOGFOODING.md` asks for it.
 */
val KNOWN_NON_COOPERATING: List<NonCooperatingApp> = listOf(
    NonCooperatingApp(R.string.help_selection_app_whatsapp, SelectionFailure.NO_TEXT_SELECTION),
)

/** The sentence that explains a listed application's reason. */
fun explanationFor(reason: SelectionFailure): Int = when (reason) {
    SelectionFailure.NO_TEXT_SELECTION -> R.string.help_selection_reason_no_selection
    SelectionFailure.NO_PACKAGE_VISIBILITY -> R.string.help_selection_reason_no_queries
}
