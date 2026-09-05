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
 * **Every rule below was rewritten against five real cards** (SRS 1.109). The version before them
 * scored one name in five. What the cards changed is recorded at each rule, because the first
 * version's mistakes were all reasonable-sounding and all wrong.
 *
 * Deterministic, and takes everything it needs (`:parser`'s rule): no clock, no locale read at
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

fun classifyCard(lines: List<String>): CardClassification {
    val cleaned = lines.map { it.trim() }.filter { it.isNotBlank() }
    if (cleaned.isEmpty()) return CardClassification(CardDraft())

    val emails = mutableListOf<CardEmail>()
    val phones = mutableListOf<CardPhone>()
    val urls = mutableListOf<String>()
    val leftover = mutableListOf<String>()

    for (line in cleaned) {
        // **One line can carry several values, and a real card does** — `E name@x.in W www.x.in`
        // was a whole line on a real card, and the first version stopped at the email and never
        // saw the address beside it. So each line is mined for everything it holds rather than
        // classified as one thing.
        var consumed = false

        EMAIL.findAll(line).forEach { emails += CardEmail(tidyEmail(it.value)); consumed = true }
        URL.findAll(line).forEach { match ->
            // A URL check that ran before the email would have matched the domain inside an
            // address; running after and excluding anything already inside an email is what
            // keeps both on a line that carries both.
            if (emails.none { match.value in it.address }) {
                urls += match.value
                consumed = true
            }
        }
        PHONE.findAll(line).forEach { match ->
            // **All the numbers on the line, not the first.** A real card merged two under OCR
            // into `TO11-43628068 M8009864200`, and taking the first meant a fragment of one and
            // the loss of the other.
            val number = tidyNumber(match.value)
            if (number.count { it.isDigit() } >= MIN_PHONE_DIGITS) {
                phones += CardPhone(number, phoneTypeNear(line, match.range.first))
                consumed = true
            }
        }

        if (!consumed) leftover += line
    }

    val name = leftover.firstOrNull(::looksLikePersonName)
    val rest = leftover.filterNot { it == name }

    val title = rest.firstOrNull(::looksLikeJobTitle)
    val afterTitle = rest.filterNot { it == title }

    val organisation = organisationAmong(afterTitle)
    val withoutOrg = afterTitle.filterNot { it == organisation }

    val address = addressAmong(withoutOrg)
    val unplaced = withoutOrg.filterNot { it in address }

    return CardClassification(
        draft = CardDraft(
            displayName = name,
            organisation = organisation,
            jobTitle = title,
            phones = phones,
            emails = emails,
            addresses = if (address.isEmpty()) emptyList() else listOf(address.joinToString(", ")),
            urls = urls,
        ),
        unplaced = unplaced,
    )
}

/**
 * Is this line a person's name?
 *
 * **Two words at least, and that single condition is what five real cards bought.** The first
 * version took "the first line that is not obviously something else", which is not a rule but a
 * coin toss: it produced `TALS`, `Deloitte.` and `informa` — a logo fragment, a logotype and a
 * lowercase wordmark — as the names of three real people, while `Devendra Kulkarni`, `Sujoy
 * Mitra` and `Vikas Rao` sat in the unplaced list.
 *
 * **The logotype is not identifiable by case** — those three were upper, title and lower — and
 * guessing on case would have been wrong twice. What was true of all three is that they are one
 * word, and true of all five real names is that they are two. A card bearing a mononym exists and
 * will be missed; it will reach the user as an unplaced line, which is the safe direction.
 */
internal fun looksLikePersonName(line: String): Boolean {
    if (line.any { it.isDigit() } || '@' in line) return false
    if (looksLikeJobTitle(line) || hasOrganisationSuffix(line)) return false
    // A separator means a role or a strapline: "Director | Business Development", "In-Charge, X".
    if (line.any { it in SEPARATORS }) return false
    val words = line.split(WHITESPACE).filter { it.isNotBlank() }
    if (words.size !in 2..4) return false
    return words.all { word -> word.all { it.isLetter() || it in NAME_PUNCTUATION } }
}

/**
 * The label beside a number.
 *
 * **Single letters count, and that came from real cards too**: `M +919871421144` and `m +91 …`
 * and `t +91 …` are the commonest Indian convention, and the first version only understood `m:`
 * with a colon. The letter is read from the text *before this number on the line*, so a line
 * carrying two labelled numbers gives each its own.
 */
internal fun phoneTypeNear(line: String, numberStart: Int): String? {
    val before = line.take(numberStart).lowercase()
    // Nearest label wins: on `T 011-… M 8009…` the mobile's label is the one closest to it.
    val candidates = listOf(
        "MOBILE" to MOBILE_WORDS, "FAX" to FAX_WORDS, "WORK" to WORK_WORDS,
    ).flatMap { (type, words) -> words.mapNotNull { w -> before.lastIndexOf(w).takeIf { it >= 0 }?.let { type to it } } }
    return candidates.maxByOrNull { it.second }?.first
}

internal fun tidyNumber(raw: String): String = raw.trim().replace(NUMBER_NOISE, " ")
    .replace(WHITESPACE, " ").trim()

/**
 * **A space before the `@` is OCR's, not the card's.**
 *
 * A real card produced `sourav.dinda @vedanta.co.in`, and the first version's pattern required no
 * space — so the address was lost entirely, and with it FR-1209's identity for that contact. Wide
 * letter-spacing on printed cards does this often enough that it is the ordinary case.
 */
internal fun tidyEmail(raw: String): String = raw.replace(" ", "")

internal fun looksLikeJobTitle(line: String): Boolean {
    val lower = line.lowercase()
    return TITLE_WORDS.any { lower == it || lower.contains(" $it") || lower.startsWith("$it") }
}

internal fun hasOrganisationSuffix(line: String): Boolean {
    val lower = line.lowercase().trimEnd('.').trim()
    return ORG_SUFFIXES.any { lower.endsWith(" $it") || lower.endsWith(".$it") || lower == it }
}

/**
 * The company, where one can be told apart from a claim about one.
 *
 * **A company name must contain a word that is not corporate boilerplate.** A real card carried
 * "A Mini Ratna Company" and "Certified Company" beside its actual name, and the first version
 * placed the first of them — a *status*, printed on the card, ending in a word that looks like a
 * suffix. Requiring one distinguishing word rejects both and keeps "Informa Markets India Private
 * Limited", whose "Informa" and "Markets" are nobody's boilerplate.
 *
 * **Where several survive that test, none is placed.** Two candidates mean the card is telling us
 * something this code cannot read, and FR-1223 would rather show the user both than pick.
 */
internal fun organisationAmong(lines: List<String>): String? {
    val candidates = lines.filter { hasOrganisationSuffix(it) && hasDistinguishingWord(it) }
    return candidates.singleOrNull()
}

private fun hasDistinguishingWord(line: String): Boolean =
    line.lowercase()
        .split(WORD_BREAK)
        .map { it.trim() }
        .any { it.length > 2 && it !in BOILERPLATE }

/**
 * The address, which the first version did not attempt at all.
 *
 * Anchored on a postcode, because that is the one token an address reliably has and a tagline
 * never does. Lines immediately above it that carry a comma are taken with it, which is how a
 * street address wraps; anything else is left for the user.
 */
internal fun addressAmong(lines: List<String>): List<String> {
    val anchor = lines.indexOfFirst { POSTCODE.containsMatchIn(it) }
    if (anchor < 0) return emptyList()
    var first = anchor
    while (first > 0 && ',' in lines[first - 1] && !hasOrganisationSuffix(lines[first - 1])) first--
    return lines.subList(first, anchor + 1)
}

private val EMAIL = Regex("[A-Za-z0-9._%+-]+\\s?@\\s?[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
private val URL = Regex("(https?://|www\\.)[A-Za-z0-9./?=_%+-]+", RegexOption.IGNORE_CASE)

/**
 * Seven digits at least, and the floor is the whole of the rule's safety: without it "12 Nehru
 * Road, 411001" yields a number, and a wrong number on a contact is not visibly wrong.
 */
private val PHONE = Regex("\\+?[0-9][0-9 ()\\-.]{5,}[0-9]")
private const val MIN_PHONE_DIGITS = 8

/** Five or six digits with letters on the line: an Indian PIN, a UK outcode, a US ZIP. */
private val POSTCODE = Regex("(?<!\\d)\\d{5,6}(?!\\d)")

private val NUMBER_NOISE = Regex("[()\\-.]+")
private val WHITESPACE = Regex("\\s+")

/** Words as a card writes them: spaces, commas, ampersands and full stops all separate. */
private val WORD_BREAK = Regex("[\\s,&.]+")
private val SEPARATORS = setOf('|', ',', ':', '/')
private val NAME_PUNCTUATION = setOf('.', '-', '\'')

// Single letters are matched with a following space or colon, so "m " counts and "member" does
// not. Longer words are matched anywhere, since a card writes "Mobile" and "Mob." alike.
private val MOBILE_WORDS = listOf("mobile", "cell", "mob", "m:", "m ")
private val FAX_WORDS = listOf("fax", "f:", "f ")
private val WORK_WORDS = listOf("office", "tel", "phone", "work", "direct", "t:", "t ", "o:", "d:")

private val TITLE_WORDS = listOf(
    "director", "manager", "head", "partner", "proprietor", "founder", "ceo", "cto", "cfo",
    "president", "engineer", "consultant", "analyst", "officer", "executive", "principal",
    "administrator", "coordinator", "supervisor", "specialist", "advisor", "adviser",
    // Added from real cards: "In-Charge, ALIMCO New Delhi" was a role this could not see.
    "in-charge", "incharge", "chief", "chairman", "secretary", "lead", "associate",
)

private val ORG_SUFFIXES = listOf(
    "ltd", "limited", "llp", "llc", "inc", "plc", "gmbh", "pvt", "private limited", "co",
    "company", "corp", "corporation", "industries", "enterprises", "associates", "partners",
    "solutions", "services", "systems", "technologies", "textiles", "logistics",
)

/**
 * Words that appear in a claim about a company rather than in its name.
 *
 * The list exists because a real card printed "A Mini Ratna Company" and "Certified Company" and
 * the first version took one of them as the employer.
 */
private val BOILERPLATE = setOf(
    "the", "and", "for", "ltd", "limited", "llp", "llc", "inc", "plc", "pvt", "private",
    "company", "corp", "corporation", "certified", "mini", "ratna", "government", "enterprise",
    "enterprises", "iso", "group", "india", "indian",
)
