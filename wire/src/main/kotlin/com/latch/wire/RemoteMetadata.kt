package com.latch.wire

import java.security.MessageDigest
import java.text.Normalizer
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * SRS §7.2, the metadata every item written to Google carries.
 *
 * **This is a cross-client wire contract, not an implementation detail.** §4.1 has three
 * clients writing to one Google account with no backend, so the remote item *is* the shared
 * state. AC-07 — capture the same message on phone and PC, second detected as a duplicate —
 * requires the Android and Windows clients to derive a byte-identical [sourceHash] from the
 * same text, each having been written independently. Everything here is therefore specified
 * in the SRS and pinned by the vectors in `data/src/test/resources/metadata/hash_vectors.tsv`,
 * which is the conformance suite the other clients must pass.
 *
 * **It also cannot be changed after the fact.** Items already in a user's account cannot be
 * rewritten, and FR-803 dedup, FR-804 reschedule detection and FR-807 undo all read these
 * keys back. An item written without a key is permanently unmanageable by the feature that
 * would have used it.
 */
data class RemoteMetadata(
    /** SHA-256 of the normalised **whole capture text** — not per item, so every item from one capture shares it (FR-803). */
    val sourceHash: String,
    /**
     * SHA-256 of the normalised **title**, deliberately excluding every date.
     *
     * FR-804 detects a rescheduling by "matching title and identifiers, different date", and
     * a rescheduled message has different source text — so [sourceHash] differs and can never
     * find it. This is the identity that survives a date change: same [itemKey] with a
     * different [sourceHash] is a reschedule; the same [sourceHash] is a duplicate.
     *
     * `source_app` is deliberately *not* an input. It has no producer yet, so including it
     * would give the same meeting different keys either side of the day that capture learns
     * to read it, and FR-804 would break exactly across that boundary.
     */
    val itemKey: String,
    /**
     * One save, one id — always present, including for a save of a single item.
     *
     * §2.4 defines a chain as a recipe expansion, but FR-807 undoes "all items created by
     * that save", and a multi-date capture (FR-511) is one save, several items and no recipe.
     * Keying on the save rather than the recipe is what gives undo something to group by in
     * every case, and it lives in the account rather than in local storage, so undo is not
     * confined to the device that did the save.
     */
    val chainId: String,
    /** When the user captured, not when the write happened — the two differ once FR-806 queues. */
    val capturedAt: Instant,
    /** Absent means unknown. Scheme-prefixed so three clients do not collide in FR-905 rules. */
    val sourceApp: String? = null,
    /** [com.latch.core.model.Recipe] id, e.g. `builtin.meeting_prep`. Absent means no recipe. */
    val recipeId: String? = null,
    val version: String = REMOTE_METADATA_VERSION,
)

/**
 * A reader that meets a version it does not know treats the item as Latch-created but
 * interprets nothing further, and neither modifies nor deletes it. Guessing at a newer
 * client's keys is how one client corrupts another's items.
 */
const val REMOTE_METADATA_VERSION: String = "1"

const val KEY_VERSION = "latch.version"
// §7.2's key names are public, not internal, because they are the contract: a second client
// of §4.1 reads and writes these exact strings, and a client that spelled one differently
// would write metadata the others cannot see. They stopped being an implementation detail of
// :data the moment the contract became a module.
const val KEY_SOURCE_HASH = "latch.source_hash"
const val KEY_ITEM_KEY = "latch.item_key"
const val KEY_CHAIN_ID = "latch.chain_id"
const val KEY_CAPTURED_AT = "latch.captured_at"
const val KEY_SOURCE_APP = "latch.source_app"
const val KEY_RECIPE = "latch.recipe"

// ---------------------------------------------------------------------------------------
// Hashing. The AC-07 contract.
// ---------------------------------------------------------------------------------------

/**
 * The normalisation two clients must agree on, character for character.
 *
 * Each step exists because of a way the same message arrives differently on two platforms:
 * a selection on Android (`EXTRA_PROCESS_TEXT`) and the same message copied on Windows will
 * routinely differ in line endings, trailing whitespace, or an invisible the sending app
 * left behind.
 *
 * Deliberately **not** `:parser`'s `Normalizer`, which is length-preserving so that match
 * spans keep indexing into the original — the opposite of what a hash wants.
 */
fun normaliseForHash(text: String): String {
    // 1. One canonical form for accented characters: café composed and decomposed must hash alike.
    val composed = Normalizer.normalize(text, Normalizer.Form.NFC)

    // 2. Invisibles carry no meaning and travel unpredictably through clipboards.
    val visible = composed.filterNot { it in INVISIBLE_CHARACTERS }

    // 3. Line endings, then every run of whitespace — Unicode separators included, because
    //    a non-breaking space is not matched by \s and is exactly what a web page yields.
    val unixEndings = visible.replace("\r\n", "\n").replace('\r', '\n')
    val collapsed = WHITESPACE_RUN.replace(unixEndings, " ")

    // 4. Root locale, never the default: Turkish lowercases I to a dotless ı, which would
    //    silently desync two clients on the same text.
    return collapsed.trim().lowercase(Locale.ROOT)
}

/** FR-803. Hashes the whole capture, so every item from one capture shares it. */
fun sourceHashOf(captureText: String): String = sha256Hex(normaliseForHash(captureText))

/** FR-804. Hashes the title alone. */
fun itemKeyOf(title: String): String = sha256Hex(normaliseForHash(title))

private val INVISIBLE_CHARACTERS = setOf(
    Char(0x200B), // zero-width space
    Char(0x200C), // zero-width non-joiner
    Char(0x200D), // zero-width joiner
    Char(0xFEFF), // byte-order mark
    Char(0x202A), // bidi: left-to-right embedding
    Char(0x202B), // bidi: right-to-left embedding
    Char(0x202C), // bidi: pop directional formatting
    Char(0x202D), // bidi: left-to-right override
    Char(0x202E), // bidi: right-to-left override
)

private val WHITESPACE_RUN = Regex("[\\s\\p{Z}]+")

private fun sha256Hex(input: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(input.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

// ---------------------------------------------------------------------------------------
// Events: extendedProperties.private
// ---------------------------------------------------------------------------------------

/**
 * Returned as a map rather than JSON so this stays free of `org.json` and testable as plain
 * Kotlin; `GoogleRest` serialises it.
 *
 * Comfortably inside Google's caps — the longest key is 17 of the 44 allowed, the longest
 * value 64 of 1024, and seven of the 300 properties an event may hold.
 */
fun RemoteMetadata.toEventProperties(): Map<String, String> = buildMap {
    put(KEY_VERSION, version)
    put(KEY_SOURCE_HASH, sourceHash)
    put(KEY_ITEM_KEY, itemKey)
    put(KEY_CHAIN_ID, chainId)
    put(KEY_CAPTURED_AT, formatCapturedAt(capturedAt))
    // Absent, not empty: an empty value is indistinguishable from a value that is genuinely
    // the empty string, and a reader cannot tell "unknown" from "known to be nothing".
    sourceApp?.let { put(KEY_SOURCE_APP, it) }
    recipeId?.let { put(KEY_RECIPE, it) }
}

/** Null when the properties are not ours, are a version we do not know, or are malformed. */
fun remoteMetadataFromEventProperties(properties: Map<String, String>): RemoteMetadata? =
    build(properties::get)

/**
 * Whether an item was created by Latch at all, regardless of whether this client can read
 * its version. Separate from [remoteMetadataFromEventProperties] because the two answers
 * differ for an item written by a newer client, and that item must be left alone rather
 * than treated as somebody else's.
 */
fun carriesLatchMetadata(properties: Map<String, String>): Boolean =
    properties.containsKey(KEY_VERSION)

// ---------------------------------------------------------------------------------------
// Tasks: a line appended to notes
// ---------------------------------------------------------------------------------------

/**
 * Tasks have no `extendedProperties` — `notes` is the only field, capped at 8192 characters,
 * and it is shown to the user and editable by them. So the metadata is one line, appended
 * after the FR-805 source text, kept as short as is readable.
 *
 * A user who edits their own notes can corrupt this. That is accepted and handled by
 * [remoteMetadataFromTaskNotes] returning null rather than throwing: the item becomes
 * unmanaged, which is the honest outcome, and is why events are the transport that matters.
 */
fun RemoteMetadata.toTaskNotes(userNotes: String): String {
    val fields = listOfNotNull(
        "v=$version",
        "sh=$sourceHash",
        "ik=$itemKey",
        "ch=$chainId",
        "at=${formatCapturedAt(capturedAt)}",
        sourceApp?.let { "ap=$it" },
        recipeId?.let { "rc=$it" },
    )
    val line = TASK_MARKER + fields.joinToString(FIELD_SEPARATOR)
    return if (userNotes.isBlank()) line else userNotes.trimEnd() + "\n\n" + line
}

/** The last marker line wins, so notes copied around keep the most recent metadata. */
fun remoteMetadataFromTaskNotes(notes: String): RemoteMetadata? {
    val line = notes.lineSequence().lastOrNull { it.trimStart().startsWith(TASK_MARKER) }
        ?: return null
    val fields = line.trimStart()
        .removePrefix(TASK_MARKER)
        .split(FIELD_SEPARATOR)
        .mapNotNull { field ->
            val split = field.indexOf('=')
            if (split <= 0) null else field.substring(0, split) to field.substring(split + 1)
        }
        .toMap()
    return build { canonicalKey -> fields[SHORT_KEYS[canonicalKey]] }
}

/** The user-visible half — everything above the metadata line (FR-805's source text and link). */
fun taskNotesWithoutMetadata(notes: String): String =
    notes.lineSequence()
        .filterNot { it.trimStart().startsWith(TASK_MARKER) }
        .joinToString("\n")
        .trim()

private const val TASK_MARKER = "[latch]"
private const val FIELD_SEPARATOR = ";"

/**
 * Short forms for the task line only. Events use the full `latch.*` keys, which are what
 * §7.2 names and what `privateExtendedProperty` queries match on; the abbreviations exist
 * because this line is shown to the user.
 *
 * No value may contain `;` or `=`. Hex digests, UUIDs, RFC 3339 timestamps, package names
 * and recipe ids all satisfy that, which is why the encoding needs no escaping.
 */
private val SHORT_KEYS = mapOf(
    KEY_VERSION to "v",
    KEY_SOURCE_HASH to "sh",
    KEY_ITEM_KEY to "ik",
    KEY_CHAIN_ID to "ch",
    KEY_CAPTURED_AT to "at",
    KEY_SOURCE_APP to "ap",
    KEY_RECIPE to "rc",
)

// ---------------------------------------------------------------------------------------
// Shared parsing
// ---------------------------------------------------------------------------------------

/**
 * One reader for both transports. Mandatory fields are validated rather than trusted: a
 * malformed digest or timestamp means the metadata is not ours to act on, and acting on it
 * would be worse than ignoring it.
 */
private fun build(get: (String) -> String?): RemoteMetadata? {
    if (get(KEY_VERSION) != REMOTE_METADATA_VERSION) return null

    val sourceHash = get(KEY_SOURCE_HASH)?.takeIf(::isDigest) ?: return null
    val itemKey = get(KEY_ITEM_KEY)?.takeIf(::isDigest) ?: return null
    val chainId = get(KEY_CHAIN_ID)?.takeIf { it.isNotBlank() } ?: return null
    val capturedAt = get(KEY_CAPTURED_AT)?.let(::parseCapturedAt) ?: return null

    return RemoteMetadata(
        sourceHash = sourceHash,
        itemKey = itemKey,
        chainId = chainId,
        capturedAt = capturedAt,
        sourceApp = get(KEY_SOURCE_APP)?.takeIf { it.isNotBlank() },
        recipeId = get(KEY_RECIPE)?.takeIf { it.isNotBlank() },
    )
}

private fun isDigest(value: String) =
    value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }

/** RFC 3339, UTC, whole seconds. Truncated on the way out so the format is fixed-width. */
private fun formatCapturedAt(instant: Instant): String =
    DateTimeFormatter.ISO_INSTANT.format(instant.truncatedTo(ChronoUnit.SECONDS))

private fun parseCapturedAt(value: String): Instant? = try {
    Instant.parse(value)
} catch (malformed: DateTimeParseException) {
    null
}
