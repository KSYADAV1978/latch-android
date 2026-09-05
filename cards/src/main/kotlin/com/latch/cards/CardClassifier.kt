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
    // The repair comes first, before any rule sees a line: a single stray mark in the middle of
    // a name is enough to make the whole line fail `looksLikePersonName`, and what follows from
    // that is not a missing name but the *wrong* one. See `repairScriptBleed`.
    val cleaned = lines.map { repairScriptBleed(it.trim()) }.filter { it.isNotBlank() }
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
    if (line.any { it in SEPARATORS && it != ',' }) return false

    // **A comma may carry a post-nominal**, which is how a great many Indian cards are printed:
    // `DEVENDRA PRATAP YADAV, IOFS` — and IAS, IPS and the rest (SRS 1.114). Everything after
    // the first comma must look like one, so "In-Charge, ALIMCO New Delhi" and "Head Taxation,
    // Aluminium Sector" stay out — though both are caught as job titles before reaching here.
    val core = line.substringBefore(',').trim()
    val suffix = line.substringAfter(',', "").trim()
    if (suffix.isNotEmpty() && !isPostNominal(suffix)) return false

    val words = core.split(WHITESPACE).filter { it.isNotBlank() }
    if (words.size !in 2..4) return false
    if (!words.all { word -> word.all { it.isLetter() || it in NAME_PUNCTUATION } }) return false

    // **Every word is capitalised, or is a name particle** (SRS 1.111). Two words was not enough:
    // a real card carried the strapline "Trade with Trust", which is three words of letters and
    // was taken as somebody's name while *Madhavan Iyer* sat unplaced two lines below. What
    // separates them is the lowercase "with" — a person capitalises every part of their name and
    // a slogan does not.
    return words.all { word -> word.first().isUpperCase() || word.lowercase() in NAME_PARTICLES }
}

/**
 * A qualification printed after a name: `IOFS`, `IAS`, `PhD`, `FRCS`.
 *
 * Short and capitalised, with no spaces — which is what keeps a department or a second half of a
 * job title from qualifying. The name keeps its suffix when written, because it is how the person
 * presents themselves on their own card.
 */
internal fun isPostNominal(text: String): Boolean =
    text.length in 2..6 && text.all { it.isLetter() } && text == text.uppercase()

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

    // **A single letter hard against the number**, as in `M8009864200` — a real card wrote both
    // numbers that way after OCR merged the line. Accepted only where the letter itself follows a
    // space or begins the line, so the `O` in `TO11` (which is a lost zero, not a label) is not
    // mistaken for one.
    val letter = before.lastOrNull()
    if (letter != null && letter in SINGLE_LETTER_LABELS) {
        val beforeLetter = before.dropLast(1).lastOrNull()
        if (beforeLetter == null || beforeLetter == ' ') {
            return when (letter) {
                'm' -> "MOBILE"
                'f' -> "FAX"
                else -> "WORK"
            }
        }
    }
    // Nearest label wins: on `T 011-… M 8009…` the mobile's label is the one closest to it.
    val candidates = listOf(
        "MOBILE" to MOBILE_WORDS, "FAX" to FAX_WORDS, "WORK" to WORK_WORDS,
    ).flatMap { (type, words) -> words.mapNotNull { w -> before.lastIndexOf(w).takeIf { it >= 0 }?.let { type to it } } }
    return candidates.maxByOrNull { it.second }?.first
}

/**
 * **A country code separated from its `+` is still a country code.**
 *
 * A card printed `+ 91 11 4000 8600` and the pattern's `\+?` could not reach across the space, so
 * the number arrived as `91 11 4000 8600`. That loss is quiet and it is not cosmetic: a `+` is
 * what makes a number dialable from another country, and its absence is invisible on the preview
 * — the digits are all there and all correct. The space is only ever allowed **after a `+` that
 * is actually present**, so a number with no country code cannot swallow the space before it and
 * shift the label search `phoneTypeNear` runs.
 */
internal fun tidyNumber(raw: String): String = raw.trim().replace(NUMBER_NOISE, " ")
    .replace(WHITESPACE, " ").trim().replace(PLUS_GAP, "+")

/**
 * **A Devanagari combining mark cannot attach to a Latin letter, so one that has is the
 * recogniser's and not the card's** (SRS 1.126).
 *
 * This app bundles two recognisers, Latin and Devanagari, and ML Kit merges their results. The
 * Devanagari one occasionally wins a block it should not: `CLAUDE.md` records `October` arriving
 * as `০ctobe` in August, which is how the Latin artifact came to be added. It still happens. On a
 * real card the name `Ramesh Kumar Bhagat` came back as `Ramesh Kumar Bhaga` + `U+0902 DEVANAGARI
 * SIGN ANUSVARA` + `a`.
 *
 * **What that one character cost was not the name but the wrong name.** `looksLikePersonName`
 * requires every character of every word to be a letter, and a non-spacing mark is not one — so
 * the line was refused, fell to FR-1223's notes, and the *next* line was promoted in its place.
 * The contact Google would have received was called **"Corporate Affairs"**: not obviously broken,
 * entirely plausible, and unverifiable afterwards because the card is gone. That is the exact harm
 * FR-1224 exists for, arriving from one codepoint.
 *
 * **It invents nothing, which is what makes it allowed.** SRS 1.117 set the rule for these
 * repairs: fire only on input that is already invalid. Unicode does not permit a Devanagari
 * combining mark on a Latin base — the sequence cannot occur in correct text of any language — so
 * dropping the mark cannot overwrite anything real, and every base letter is left exactly as it
 * was. Contrast the danda in `+ 91 1। 4000 8600` on the same card, which is deliberately **left
 * alone**: turning it into a `1` would invent a digit, and merely deleting it would produce a
 * *more* plausible wrong number than the truncation the user can currently see.
 *
 * **Confined to the Devanagari block on purpose.** Latin uses combining marks legitimately — `e`
 * followed by `U+0301` is `é` — so a rule about combining marks in general would corrupt an
 * accented name. Devanagari on a Latin base is the case that cannot be anything but bleed, and
 * NFR-403's Devanagari cards are untouched: there the base character is Devanagari too.
 */
internal fun repairScriptBleed(line: String): String {
    if (line.none { it.code in DEVANAGARI }) return line
    val out = StringBuilder(line.length)
    for (ch in line) {
        val isDevanagariMark = ch.code in DEVANAGARI &&
            ch.category in setOf(CharCategory.NON_SPACING_MARK, CharCategory.COMBINING_SPACING_MARK)
        if (isDevanagariMark && out.lastOrNull()?.isLatinLetter() == true) continue
        out.append(ch)
    }
    return out.toString()
}

private fun Char.isLatinLetter(): Boolean = this in 'a'..'z' || this in 'A'..'Z'

private val DEVANAGARI = 0x0900..0x097F

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
    // **A postcode alone is not enough, and a real card proved it**: `IS/S0 9001 & IS/S0 14001`
    // carries "14001", and the first version read a certification as the anchor of an address —
    // placing the ISO line and leaving the real address unplaced. An address line carries a
    // **comma**; a certification does not. That one condition separates them.
    val anchor = lines.indexOfFirst { POSTCODE.containsMatchIn(it) && ',' in it }
    if (anchor < 0) return emptyList()

    // Neighbours on **both** sides, because OCR block order is not the order the address is
    // printed in: on the card that found this, the street line came *after* the city line.
    var first = anchor
    while (first > 0 && isAddressNeighbour(lines[first - 1])) first--
    var last = anchor
    while (last < lines.lastIndex && isAddressNeighbour(lines[last + 1])) last++
    return lines.subList(first, last + 1)
}

private fun isAddressNeighbour(line: String): Boolean =
    ',' in line && !hasOrganisationSuffix(line) && !looksLikeJobTitle(line)

/**
 * **The second half of the space rule: OCR breaks a domain as readily as it breaks an `@`.**
 *
 * A real card printed `rameshkumar.bhagat@jsw.test` and it was recognised as `jsw. in` — a space
 * after the dot rather than before the `@` — so the pattern matched nothing and the address was
 * dropped whole, exactly as `tidyEmail`'s card lost one before it.
 *
 * The spaced branch is **narrower than the ordinary one and deliberately so**, because it is the
 * branch that could invent an address. It fires only where the alternative is certain loss: an
 * address ending in a bare dot is not a valid address, so nothing correct is being overwritten,
 * and it takes only a short *lowercase* run after the space. That is what keeps prose out —
 * `write to john@acme. Regards` offers `Regards`, which is capitalised and refused.
 *
 * **The trailing lookahead is not decoration**, and the test that demanded it was written before
 * the code that satisfies it. Without it the short run matched a *prefix* of a longer word, so
 * `sales@example. contact anytime` invented `sales@example.cont` — a domain that is nobody's,
 * built out of a word that was not a domain at all. The run has to be the whole token.
 */
private val EMAIL =
    Regex("[A-Za-z0-9._%+-]+\\s?@\\s?[A-Za-z0-9.-]+\\.(?:[A-Za-z]{2,}|\\s[a-z]{2,4}(?![A-Za-z]))")
private val URL = Regex("(https?://|www\\.)[A-Za-z0-9./?=_%+-]+", RegexOption.IGNORE_CASE)

/**
 * Seven digits at least, and the floor is the whole of the rule's safety: without it "12 Nehru
 * Road, 411001" yields a number, and a wrong number on a contact is not visibly wrong.
 */
private val PHONE = Regex("(?:\\+\\s?)?[0-9][0-9 ()\\-.]{5,}[0-9]")
private const val MIN_PHONE_DIGITS = 8

/**
 * A postcode: five or six digits, **or two groups of three with a space between them**.
 *
 * The second form is how an Indian PIN is usually printed — `600 004`, `110 011` — and a real
 * card's address was missed entirely for want of it (SRS 1.115), both its lines going to the
 * notes because nothing anchored them. Three-and-three is narrow enough not to catch a telephone
 * number, which runs in fours and is consumed before this is asked in any case.
 */
private val POSTCODE = Regex("(?<!\\d)(\\d{5,6}|\\d{3}\\s\\d{3})(?!\\d)")

private val NUMBER_NOISE = Regex("[()\\-.]+")
private val PLUS_GAP = Regex("^\\+\\s+")
private val WHITESPACE = Regex("\\s+")

/** Words as a card writes them: spaces, commas, ampersands and full stops all separate. */
private val WORD_BREAK = Regex("[\\s,&.]+")

/** Labels a card writes as one letter: mobile, telephone, fax, office, direct. */
private val SINGLE_LETTER_LABELS = setOf('m', 't', 'f', 'o', 'd')
private val SEPARATORS = setOf('|', ',', ':', '/')
private val NAME_PUNCTUATION = setOf('.', '-', '\'')

/**
 * Lowercase words that legitimately appear inside a person's name.
 *
 * Deliberately a list rather than a rule about word length: "with", "and" and "for" are just as
 * short as "van" and "de", and they are exactly what the capitalisation test exists to reject.
 */
private val NAME_PARTICLES = setOf(
    "de", "del", "della", "der", "van", "von", "da", "di", "du", "la", "le", "bin", "binte",
    "al", "el", "ibn", "ter", "ten", "dos", "das",
)

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
