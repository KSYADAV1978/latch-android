package com.latch.wire

import com.latch.core.model.CardDraft
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * FR-1207 and FR-1209: §7.2's discipline applied to a contact.
 *
 * **In `:wire` rather than in a client, for §7.2's own reason.** Whatever decides a byte Google
 * receives lives here, so two clients cannot derive different keys from one card. The contacts
 * pillar has one client today; §7.2 records that a second client is where an identity derivation
 * drifts, and the cost of putting this in the right module now is nothing.
 *
 * **Write-once, like everything else in §7.2** (SRS 1.18). The record is provenance: it says what
 * was captured and when, not what the contact currently holds. An FR-1231 update changes fields
 * and never rewrites this.
 */
data class CardMetadata(
    /** FR-1208: a digest of the normalised card payload. The same card is the same hash. */
    val sourceHash: String,
    /**
     * FR-1209: digests of the normalised email addresses on the card, in the order the card gave
     * them. **Empty where the card carries no email**, which is a meaningful state and not a
     * missing value — see [contactIdentityKeys].
     */
    val identityKeys: List<String>,
    val capturedAt: Instant,
    /** Which capture surface produced it. FR-1211: never the image, which is not kept at all. */
    val captureLayer: String,
)

const val KEY_CARD_VERSION = "latch.card.version"
const val KEY_CARD_SOURCE_HASH = "latch.card.source_hash"
const val KEY_CARD_IDENTITY = "latch.card.identity"
const val KEY_CARD_CAPTURED_AT = "latch.card.captured_at"
const val KEY_CARD_LAYER = "latch.card.layer"

/**
 * The schema version, leading, exactly as the local record formats do.
 *
 * A version this build cannot read means the record is left alone rather than reinterpreted —
 * because unlike a local preference, a contact is in somebody's account and cannot be re-written
 * on a guess.
 */
const val CARD_METADATA_VERSION = "1"

/**
 * FR-1208's hash: the card as it was, normalised.
 *
 * Deliberately the **payload** and not the parsed draft. Two encoders can produce different field
 * orders for one person, but the same physical card scanned twice produces the same payload — and
 * §7.2 already records what happens when a hash depends on something that can wobble.
 */
fun cardSourceHashOf(payload: String): String = sourceHashOf(payload)

/**
 * FR-1209: **email, normalised. Never a telephone number.**
 *
 * **The reasoning is asymmetric harm.** A duplicate contact is visible, obvious and deleted in one
 * gesture. A *wrong merge* silently overwrites one person's details with another's, and the user
 * finds out by telephoning the wrong person. Telephone numbers make wrong merges easy: a
 * switchboard number is shared by everyone at a company, so two colleagues' cards carry an
 * identical `TEL` and identity-by-phone would fold them into one contact.
 *
 * **Normalisation is case and whitespace only.** No provider-specific rules — not dot-stripping,
 * not `+tag` removal. Those are correct for one provider and wrong for the rest, and a rule that
 * is right for Gmail and wrong for a company's own mail server merges two colleagues at that
 * company, which is the failure this function exists to avoid.
 *
 * **An empty list is a real answer.** A card with no email has *no automatic identity at all* and
 * is always written as a new contact — at worst a duplicate, which is the safe direction. Two
 * cards that both lack an email must never match each other, and [sharesContactIdentity] enforces
 * that rather than leaving it to a caller to remember.
 */
fun contactIdentityKeys(draft: CardDraft): List<String> =
    draft.emails
        .map { normaliseEmail(it.address) }
        .filter { it.isNotBlank() }
        .distinct()
        .map { identityKeyOf(it) }

/** Case-folded and trimmed, in the root locale for `normaliseForHash`'s reason. */
fun normaliseEmail(raw: String): String = raw.trim().lowercase(Locale.ROOT)

/** A digest, not the address: an email is a third party's personal data (FR-1211's reasoning). */
fun identityKeyOf(normalisedEmail: String): String = itemKeyOf(normalisedEmail)

/**
 * Whether two cards name the same person, on FR-1209's terms.
 *
 * **Two empty lists do not match.** That is the whole point: no email means no identity, and
 * treating "neither has one" as agreement would merge every emailless card into a single contact.
 */
fun sharesContactIdentity(left: List<String>, right: List<String>): Boolean =
    left.isNotEmpty() && right.isNotEmpty() && left.any { it in right }

// ---- the wire record -----------------------------------------------------------------------

/**
 * FR-1207: the People API's `clientData`, as key/value pairs.
 *
 * Several identities become several entries under one key. The API permits duplicate keys
 * explicitly, and the probe of 4 Sep 2026 stored 500 entries intact (SRS 1.91), so a card with
 * three addresses is nowhere near any boundary.
 */
fun CardMetadata.toClientData(): List<Pair<String, String>> = buildList {
    add(KEY_CARD_VERSION to CARD_METADATA_VERSION)
    add(KEY_CARD_SOURCE_HASH to sourceHash)
    identityKeys.forEach { add(KEY_CARD_IDENTITY to it) }
    add(KEY_CARD_CAPTURED_AT to CARD_INSTANT.format(capturedAt))
    add(KEY_CARD_LAYER to captureLayer)
}

/**
 * Read it back, or null where this build should not interpret what it found.
 *
 * A record with no version, or one this build does not know, returns null — and the caller leaves
 * the contact alone rather than treating it as unmanaged. The alternative is guessing about an
 * item in somebody's account.
 */
fun cardMetadataFromClientData(pairs: List<Pair<String, String>>): CardMetadata? {
    val single = pairs.toMap()
    if (single[KEY_CARD_VERSION] != CARD_METADATA_VERSION) return null
    val hash = single[KEY_CARD_SOURCE_HASH]?.takeIf { it.isNotBlank() } ?: return null
    val capturedAt = single[KEY_CARD_CAPTURED_AT]
        ?.let { runCatching { Instant.parse(it) }.getOrNull() }
        ?: return null
    return CardMetadata(
        sourceHash = hash,
        identityKeys = pairs.filter { it.first == KEY_CARD_IDENTITY }.map { it.second },
        capturedAt = capturedAt,
        captureLayer = single[KEY_CARD_LAYER].orEmpty(),
    )
}

/** UTC, seconds, `Z` — §7.2's own format, so two clients cannot disagree about an instant. */
private val CARD_INSTANT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(java.time.ZoneOffset.UTC)
