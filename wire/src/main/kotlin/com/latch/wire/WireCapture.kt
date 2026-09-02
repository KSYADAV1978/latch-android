package com.latch.wire

/**
 * The part of a capture that decides what Google receives.
 *
 * Named for the module rather than for the capture because `:app` already has a
 * `WireCapture` meaning something else entirely — whether an image has finished being
 * recognised — and two types with one name across a dependency edge is how an import quietly
 * resolves to the wrong one.
 *
 * **An interface rather than a data class, and that is deliberate.** Each client of §4.1 carries
 * its own capture type with its own platform in it — Android's holds a `PageCoverage` from an
 * Android library, and a desktop one will hold whatever the clipboard gave it. None of that
 * reaches a hash. What does is these three fields, so this is the contract `:wire` asks for and
 * every client already satisfies by implementing it on the type it already has.
 *
 * Passing the three as separate arguments would have done the same job and been worse: the
 * derivation reads `preferredTitle` and `ocrUsed` several calls deep, and a client that passed
 * them in the wrong order would compile.
 */
interface WireCapture {
    /** What was captured, verbatim. §7.2 hashes a normalisation of this and nothing else. */
    val text: String

    /** FR-206's subject line, where the sending application supplied one. */
    val preferredTitle: String?

    /** FR-215: [text] was recognised from an image or a document rather than read as text. */
    val ocrUsed: Boolean
}
