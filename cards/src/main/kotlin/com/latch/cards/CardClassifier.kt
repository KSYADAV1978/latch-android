package com.latch.cards

import com.latch.core.model.CardDraft
import com.latch.core.model.CardEmail
import com.latch.core.model.CardPhone

/**
 * FR-1221: recognised text from a photographed card, assigned to fields.
 *
 * **This is the half of the pillar that can only ever be *probably* right**, which is why Phase A
 * settled the write path first against a grammar that is right or wrong. A QR payload says which
 * field each value belongs to; a photographed card says nothing at all, and every rule here is a
 * guess about a layout somebody else chose.
 *
 * **So the reading is: guess narrowly, and show what is left.** A line this cannot place goes to
 * the user (FR-1223) rather than being forced into a field that nearly fits — which is FR-1204's
 * rule for unknown vCard properties, applied where there are no properties at all. A wrong
 * telephone number is the same class of harm as an invented date and is harder to notice.
 *
 * **Deterministic, and takes everything it needs** (`:parser`'s rule): no clock, no locale read at
 * call time, so a case that passes today passes in March.
 */
data class CardClassification(
    val draft: CardDraft,
    /**
     * FR-1223: lines this could not place, in reading order.
     *
     * **Never empty because the classifier gave up quietly.** A card whose job title was dropped
     * looks identical on a preview to a card that never had one, so what was not understood is
     * shown rather than discarded.
     */
    val unplaced: List<String> = emptyList(),
)

/**
 * Classify the recognised lines of one card.
 *
 * [lines] arrive in reading order — `:ocr` fixes that by block geometry (SRS 1.31), and this
 * depends on it: the name is near the top of a card far more often than not, and that is the only
 * positional assumption made here.
 */
fun classifyCard(lines: List<String>): CardClassification {
    val cleaned = lines.map { it.trim() }.filter { it.isNotBlank() }
    if (cleaned.isEmpty()) return CardClassification(CardDraft())

    val emails = mutableListOf<CardEmail>()
    val phones = mutableListOf<CardPhone>()
    val urls = mutableListOf<String>()
    val leftover = mutableListOf<String>()

    for (line in cleaned) {
        val email = EMAIL.find(line)?.value
        if (email != null) {
            emails += CardEmail(email)
            // The rest of the line is rarely anything: an email sits alone or behind a label.
            continue
        }

        val url = URL.find(line)?.value
        // An email contains no scheme and no "www", so this cannot swallow one — but a URL check
        // that ran first would have matched the domain inside an address.
        if (url != null && '@' !in line) {
            urls += url
            continue
        }

        val phone = PHONE.find(line)?.value?.let(::tidyNumber)
        if (phone != null) {
            phones += CardPhone(phone, phoneTypeOf(line))
            continue
        }

        leftover += line
    }

    // The name is the first line that is not obviously something else. "Obviously" is doing real
    // work: a card often opens with a company logotype, and there is no rule that reliably tells
    // a person from a firm — so this takes position and leaves the rest to the user.
    val name = leftover.firstOrNull()
    val rest = leftover.drop(1)

    // A job title is recognised from a small, closed vocabulary and nothing else. The alternative
    // — "the line after the name" — is wrong on every card that puts the company there, which is
    // most of them.
    val title = rest.firstOrNull { looksLikeJobTitle(it) }
    val afterTitle = rest.filterNot { it == title }

    // An organisation is likewise recognised only by a suffix a company actually uses.
    val organisation = afterTitle.firstOrNull { looksLikeOrganisation(it) }
    val unplaced = afterTitle.filterNot { it == organisation }

    return CardClassification(
        draft = CardDraft(
            displayName = name,
            organisation = organisation,
            jobTitle = title,
            phones = phones,
            emails = emails,
            urls = urls,
        ),
        unplaced = unplaced,
    )
}

/**
 * The label beside a number, where a card gives one.
 *
 * Read from the line rather than guessed from position: "M: +91…" and "Mobile +91…" are both
 * common, and a card that says nothing gets no type rather than an invented one.
 */
internal fun phoneTypeOf(line: String): String? {
    val lower = line.lowercase()
    return when {
        MOBILE_WORDS.any { it in lower } -> "MOBILE"
        FAX_WORDS.any { it in lower } -> "FAX"
        WORK_WORDS.any { it in lower } -> "WORK"
        else -> null
    }
}

/** Strip the label and the punctuation a card decorates a number with, keeping the number. */
internal fun tidyNumber(raw: String): String = raw.trim().replace(NUMBER_NOISE, " ").trim()

internal fun looksLikeJobTitle(line: String): Boolean {
    val lower = line.lowercase()
    return TITLE_WORDS.any { lower == it || lower.contains(" $it") || lower.startsWith("$it ") }
}

internal fun looksLikeOrganisation(line: String): Boolean {
    val lower = line.lowercase().trimEnd('.')
    return ORG_SUFFIXES.any { lower.endsWith(" $it") || lower.endsWith(".$it") }
}

// A deliberately plain pattern: a card is printed text, not a validator's test suite, and an
// address that this misses reaches the user as an unplaced line rather than being lost.
private val EMAIL = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")

private val URL = Regex("(https?://|www\\.)[A-Za-z0-9./?=_%+-]+", RegexOption.IGNORE_CASE)

/**
 * Seven digits at least, so a postcode or a building number is not read as a telephone.
 *
 * That floor is the whole of the rule's safety: without it "12 Nehru Road, 411001" yields a
 * number, and a wrong number on a contact is not visibly wrong.
 */
private val PHONE = Regex("\\+?[0-9][0-9 ()\\-.]{6,}[0-9]")

private val NUMBER_NOISE = Regex("[()\\-.]+")

private val MOBILE_WORDS = listOf("mobile", "cell", "mob", "m:", "m :")
private val FAX_WORDS = listOf("fax", "f:")
private val WORK_WORDS = listOf("office", "tel", "phone", "work", "t:", "o:")

private val TITLE_WORDS = listOf(
    "director", "manager", "head", "partner", "proprietor", "founder", "ceo", "cto", "cfo",
    "president", "engineer", "consultant", "analyst", "officer", "executive", "principal",
    "administrator", "coordinator", "supervisor", "specialist", "advisor", "adviser",
)

private val ORG_SUFFIXES = listOf(
    "ltd", "limited", "llp", "llc", "inc", "plc", "gmbh", "pvt", "private limited", "co",
    "company", "corp", "corporation", "industries", "enterprises", "associates", "partners",
    "solutions", "services", "systems", "technologies", "textiles", "logistics",
)
