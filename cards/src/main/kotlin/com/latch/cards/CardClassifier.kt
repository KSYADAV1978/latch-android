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
    val residues = mutableListOf<String>()

    for (line in cleaned) {
        // **One line can carry several values, and a real card does** — `E name@x.in W www.x.in`
        // was a whole line on a real card, and the first version stopped at the email and never
        // saw the address beside it. So each line is mined for everything it holds rather than
        // classified as one thing.
        var consumed = false
        val taken = mutableListOf<IntRange>()

        EMAIL.findAll(line).forEach {
            emails += CardEmail(tidyEmail(it.value)); consumed = true; taken += it.range
        }
        URL.findAll(line).forEach { match ->
            // A URL check that ran before the email would have matched the domain inside an
            // address; running after and excluding anything already inside an email is what
            // keeps both on a line that carries both.
            if (emails.none { match.value in it.address }) {
                urls += match.value
                consumed = true
                taken += match.range
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
                taken += match.range
            }
        }

        if (!consumed) {
            leftover += line
        } else {
            // **What was left of a line that was only partly consumed** (SRS 1.144). A real card
            // recognised as `maif: rameshkumar. Bhagt@jsw.test Phone : + 911 4000 8600` gave the
            // email pattern nowhere to start but `Bhagt@`, and `rameshkumar.` — the larger half of
            // somebody's address — was dropped without trace, because a line that matched
            // *something* was considered dealt with.
            //
            // **It is shown and never joined.** Joining `rameshkumar.` to the address that follows
            // is the repair FR-1204 forbids: `please contact John. Mary@acme.com` has the identical
            // shape and would yield an address belonging to nobody, which SRS 1.117's guard test
            // was written against. So the fragment goes where FR-1223 puts everything this cannot
            // place — in front of the user, beside a preview they can read the card off.
            residueOf(line, taken)?.let { residues += it }
        }
    }

    // **The name is chosen by the card's own evidence, not by which line came first**
    // (SRS 1.134). `firstOrNull` made the answer depend on reading order, and reading order is
    // computed from axis-aligned bounding boxes — so a card photographed with a few degrees of
    // skew regroups its rows and can put the department line above the name. Watched twice on
    // one card: `Corporate Affairs` and `Arvind Bhandari` swapped between two photographs taken a
    // minute apart, and the contact would have been called Corporate Affairs.
    val nameCandidates = leftover.filter(::looksLikePersonName)
    val name = nameCandidates.firstOrNull { namedInEmail(it, emails) } ?: nameCandidates.firstOrNull()
    val rest = leftover.filterNot { it == name }

    val title = rest.firstOrNull(::looksLikeJobTitle)
    val afterTitle = rest.filterNot { it == title }

    val organisation = organisationAmong(afterTitle)
        // **Only where the suffix rules found nothing**, so this can fill a gap and never
        // overwrite an answer. See [organisationCorroboratedBy].
        ?: organisationCorroboratedBy(afterTitle, emails, urls)
    val withoutOrg = afterTitle.filterNot { it == organisation }

    val address = addressAmong(withoutOrg)
    // **A residue joins `unplaced` and nothing else.** Offering it to the name, title, company and
    // address rules in turn is what the first attempt did, and `Tel. :` and `Mob` — the labels left
    // behind after two numbers were lifted off one line — were appended to a real card's postal
    // address. A residue is by definition the part of a line that could not be placed, so placing
    // it is the one thing it must not be given a chance at.
    val unplaced = withoutOrg.filterNot { it in address } + residues

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
 * What is left of a line after its values were taken out of it (SRS 1.144).
 *
 * **Only where something recognisable survives.** A residue of punctuation and a label — `:` or
 * `Phone` — is noise on a preview, and FR-1223's obligation is to show what could not be *placed*,
 * not every character that was not consumed. Three letters is the floor, which keeps a stray colon
 * out and lets a truncated address like `rameshkumar.` through.
 */
internal fun residueOf(line: String, taken: List<IntRange>): String? {
    if (taken.isEmpty()) return null
    val kept = StringBuilder(line.length)
    line.forEachIndexed { index, ch -> if (taken.none { index in it }) kept.append(ch) }
    val trimmed = kept.toString().replace(WHITESPACE, " ").trim().trim(':', '-', ',', '.', ' ')
    // **Judged on what is left once the labels are removed, and shown whole.** `Tel. :` and `Mob`
    // are all that survives an ordinary two-number line and say nothing a reader does not already
    // see; `maif: rameshkumar.` carries the larger half of an address the email pattern could not
    // reach. The label words decide whether to show it — they do not decide what is shown, because
    // what was printed on the card is what the reader has to compare against.
    val substance = trimmed.split(WORD_BREAK).filterNot(::isFieldLabel).joinToString(" ")
    return trimmed.takeIf { substance.count(Char::isLetter) >= MIN_RESIDUE_LETTERS }
}

/** A word that only names the field beside it — `Tel`, `Mob`, `Email` — carries nothing itself. */
private fun isFieldLabel(word: String): Boolean {
    val plain = word.lowercase().trim(':', '.', '-', ' ')
    return plain.isEmpty() || plain in FIELD_LABELS
}

private val FIELD_LABELS = setOf(
    "tel", "telephone", "phone", "ph", "mob", "mobile", "cell", "m", "t", "o", "d", "f",
    "fax", "office", "work", "direct", "email", "e", "mail", "e-mail", "web", "website",
    "www", "url", "address", "add", "addr",
)

/** See [residueOf]. */
private const val MIN_RESIDUE_LETTERS = 3

/**
 * Do this line's words appear in an email address on the same card?
 *
 * **The card carries the answer, and it does not depend on where anything sits.** A business
 * email is very often the person's name — `bhandari.arvind@dalmiabharat.test` — so a candidate whose
 * words are in the local part is the name, whatever order the recogniser returned the blocks in.
 *
 * **Two words at least**, and that floor is what keeps it honest: a single common word could be in
 * anybody's address by chance, while two of a line's words both appearing is not a coincidence a
 * department name produces. `Corporate Affairs` matches neither half of `bhandari.arvind`.
 *
 * **It is a preference and never a filter.** A card whose email is `info@` or whose address bears
 * no relation to the name falls through to the previous behaviour, so this can only improve an
 * answer and never remove one — which is why it needed no vocabulary of business words, the kind
 * of list this classifier has been careful not to acquire.
 */
internal fun namedInEmail(line: String, emails: List<CardEmail>): Boolean {
    if (emails.isEmpty()) return false
    val localParts = emails
        .map { it.address.substringBefore('@').lowercase() }
        .filter { it.isNotBlank() }
    if (localParts.isEmpty()) return false

    val words = line.split(WORD_BREAK)
        .map { it.lowercase().filter(Char::isLetter) }
        // Initials are in half the addresses ever written; they carry no evidence.
        .filter { it.length >= 3 }
    return words.count { word -> localParts.any { it.contains(word) } } >= 2
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
 * SIGN ANUSVARA` + `t`.
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
    if (candidates.size <= 1) return candidates.singleOrNull()
    // **Where every survivor is contained in the longest, they are one name the layout split**
    // (SRS 1.210). A card printing "HDFC BANK" as its logotype above "HDFC Bank Ltd." in the
    // address block offers two candidates and one company; so does "Dalmia" over "Bharat Limited"
    // beside "Dalmia Bharat Limited". The blanket refusal below was written for two *different*
    // companies and this is not that case - it was discarding a correct answer that had been
    // recognised perfectly.
    //
    // **Compared on letters and digits alone**, because the repetition is rarely exact: case,
    // punctuation and spacing differ between a logotype and the legal name beneath it.
    val longest = candidates.maxByOrNull { it.length } ?: return null
    val whole = longest.squashed()
    return if (candidates.all { whole.contains(it.squashed()) }) longest else null
}

/** A line holding a domain-shaped token is the contact block, whatever OCR did to its `@`. */
private val DOMAIN_SHAPED =
    Regex("""[A-Za-z0-9-]\.(com|in|net|org|edu|gov|io|biz)""", RegexOption.IGNORE_CASE)

/**
 * The company, where the card names it only as a logotype (SRS 1.210).
 *
 * **A logotype carries no suffix, so the suffix rules cannot see it** — and on the thirty-nine
 * cards scanned on 11 September the company was left blank **45% of the time**, more often than
 * any other field, with the answer usually sitting in the unplaced list as `ALIMCO`, `MCX`,
 * `ReLIANCe` or `KIOCL LIMITED`. The developer's own hand-corrections agree: five of their ten
 * edits were to the company.
 *
 * **The card corroborates itself.** One card belongs to one organisation, so the host of its email
 * address and of its website is evidence about the employer's name that no amount of line-shape
 * analysis can supply. `namedInEmail` already applies this reasoning to the *name*; this is the
 * same evidence one field over.
 *
 * **It fills and never overwrites**, which is what makes it safe enough to apply silently. It runs
 * only where [organisationAmong] returned null, so a card that named its company keeps that
 * answer, and the worst case here is a blank field staying blank.
 *
 * **The registrable part only.** `co`, `com`, `in`, `net`, `org` and the like are shared by
 * everybody and would match any line containing them; comparing against `gvpr` from
 * `gvpr.co.in` is the whole signal.
 *
 * **The longest match wins**, because a card printing both `ALIMCO` and
 * `Artificial Limbs Manufacturing Corporation of India` should yield the name and not the
 * acronym — and where only the acronym is printed, the acronym is the best answer available.
 */
internal fun organisationCorroboratedBy(
    lines: List<String>,
    emails: List<CardEmail>,
    urls: List<String>,
): String? {
    val hosts = emails.map { it.address.substringAfter('@') } + urls
    val stems = hosts
        .flatMap { it.lowercase().split('.', '/', '@') }
        .map { it.removePrefix("www") }
        .filter { it.length > 3 && it !in HOST_NOISE && it !in FREE_MAIL_HOSTS }
        .toSet()
    if (stems.isEmpty()) return null
    return lines
        .filter { line ->
            val squashed = line.squashed()
            squashed.length > 2 && stems.any { squashed.contains(it) || it.contains(squashed) }
        }
        // **The line carrying the address cannot corroborate it**, which is circular and was the
        // first thing this rule got wrong: a card whose email line reads
        // "e bansal.anuragadalmiabharat.com" contains the domain by construction, so it matched
        // itself and became the employer.
        //
        // **Testing for an `@` is not enough**, and the same card proved it: OCR read that card's
        // `@` as a letter, so the line carried the domain and looked like ordinary prose. What
        // identifies a contact line is the *domain-shaped token*, which survives the damage.
        .filterNot { it.contains("://") || it.contains(DOMAIN_SHAPED) }
        // A line that is already a phone number, an email or an address is not a company, and
        // `looksLikeJobTitle` keeps "Head of Corporate Affairs" from becoming an employer on a
        // card whose domain happens to contain "corporate".
        .filterNot { looksLikeJobTitle(it) || looksLikePersonName(it) }
        // **A name beats a claim about one, and the claim is the longer of the two.** The card
        // that forced this prints "Dalmia Bharat Limited" and "A Dalmia Bharat Group company";
        // both carry the domain and taking the longest took the claim. Ranking on how much of a
        // line is *not* corporate boilerplate separates them without a list of claim phrases -
        // "Limited" is one boilerplate word in three, "A ... Group company" is three in five.
        .minWithOrNull(
            compareBy<String> { line ->
                val words = line.lowercase().split(WORD_BREAK).filter { it.length > 2 }
                if (words.isEmpty()) 1.0 else words.count { it in BOILERPLATE }.toDouble() / words.size
            }.thenByDescending { it.length },
        )
}


/** Host parts that belong to everybody and so corroborate nothing. */
private val HOST_NOISE = setOf("com", "net", "org", "gov", "edu", "info", "mail", "co", "in")

/**
 * Hosts that say nothing about an employer, because anybody may have one.
 *
 * A card printing a personal `@rediffmail.com` address is common here, and matching a line
 * against `rediffmail` picked a logo fragment as the company. The domain corroborates an employer
 * only when the employer is who issued it.
 */
private val FREE_MAIL_HOSTS = setOf(
    "gmail", "hotmail", "yahoo", "rediffmail", "outlook", "live", "aol", "protonmail", "icloud",
)

/** Letters and digits, lower case: what two printings of one name have in common. */
private fun String.squashed(): String = lowercase().filter { it.isLetterOrDigit() }

private fun hasDistinguishingWord(line: String): Boolean =
    line.split(WORD_BREAK)
        .flatMap { it.splitWhereOcrLostASpace() }
        .map { it.trim().lowercase() }
        .any { it.length > 2 && it !in BOILERPLATE }

/**
 * Split a word where OCR ran two together, because **a case boundary is where the space was**.
 *
 * SRS 1.205: a second reading of the ALIMCO card returned `AMini Ratna Company` as a real person's
 * employer. Every word of `A Mini Ratna Company` is boilerplate, so the line is refused as the
 * claim it is - but `AMini` is in no list, is over the two-character floor, and so reads as the
 * distinguishing word [hasDistinguishingWord] demands. The same card passed or failed on one
 * space. Listing `amini` would have fixed that card and left `ACertified` and `AGovernment`
 * waiting; what is wrong is the comparison being sensitive to spacing at all.
 *
 * **It breaks only before an upper-case letter that follows a letter and precedes a lower-case
 * one**, so `VEDANTA` and `JSW` are left whole and only a genuine case *change* splits.
 *
 * **A wrong split is harmless in one direction only, which is why this is safe.** It can add a
 * word that is not boilerplate - keeping a company that would otherwise be dropped - and it
 * cannot remove one. `SWsteel` splits to `S` + `Wsteel`, which is not how anybody would read it
 * and costs nothing: `wsteel` is still distinguishing, so that corpus row is undisturbed.
 */
private fun String.splitWhereOcrLostASpace(): List<String> {
    if (length < 2) return listOf(this)
    val parts = mutableListOf<String>()
    var start = 0
    for (i in 1 until length) {
        val breaksHere = this[i].isUpperCase() &&
            this[i - 1].isLetter() &&
            i + 1 < length &&
            this[i + 1].isLowerCase()
        if (breaksHere) {
            parts += substring(start, i)
            start = i
        }
    }
    parts += substring(start)
    return parts
}

/**
 * The address, which the first version did not attempt at all.
 *
 * Anchored on a postcode, because that is the one token an address reliably has and a tagline
 * never does. Lines immediately above it that carry a comma are taken with it, which is how a
 * street address wraps; anything else is left for the user.
 */
internal fun addressAmong(lines: List<String>): List<String> {
    val anchor = lines.indexOfFirst { POSTCODE.containsMatchIn(it) && looksLikeAddressLine(it) }
    if (anchor < 0) return emptyList()

    // **Every address line, not a contiguous run** (SRS 1.211). Adjacency was the rule until a
    // card printed its address in two columns: the recogniser returned the flat and the building,
    // then the whole telephone block, then the street, the city and the state. Expanding outwards
    // from the postcode stopped at the first telephone number and kept three lines of seven.
    // Reading order is not printed order — SRS 1.134 recorded the same thing about the name — so
    // an address is gathered by what its lines *are* rather than by where they sit.
    //
    // **Kept in printed order** regardless of where they were found, because a reader needs the
    // flat before the street; the anchor decides only that there is an address here at all.
    //
    // **A comma alone is not enough to join from a distance**, which gathering exposed at once: a
    // government card's *"Empowerment of Persons with Disabilities (Divyangjan),"* carries a comma
    // and nothing else, and the walk had never reached it because it sits eight lines from the
    // postcode. So a line earns its place outright only by carrying a postcode or one of the words
    // an address is built from; a comma-only line joins if it sits **beside** one that did, which
    // is how *"West Bengal, India"* follows *"Block-EP & GP Sector-V"*. Weak evidence plus
    // adjacency, rather than either alone.
    val strong = lines.indices.filter { looksLikeAddressLine(lines[it]) && hasAddressVocabulary(lines[it]) }
    if (strong.isEmpty()) return emptyList()
    val taken = strong.toMutableSet()
    lines.indices.forEach { i ->
        if (i !in taken && looksLikeAddressLine(lines[i]) && ',' in lines[i] &&
            strong.any { kotlin.math.abs(it - i) <= COMMA_REACH }
        ) {
            taken += i
        }
    }
    // **One card can print three addresses, and one field can hold one** (SRS 1.211). A steel
    // company's card carries its works, its registered office and a mine; gathering took all
    // three and joined them into a single line that describes nowhere. So the lines are cut into
    // clusters wherever the gap between them exceeds [ADDRESS_GAP], and the cluster holding the
    // anchor wins - which is the office whose postcode was found first, and on these cards is the
    // one the person actually sits in.
    val ordered = taken.sorted()
    var cluster = mutableListOf(ordered.first())
    val clusters = mutableListOf(cluster)
    ordered.zipWithNext { previous, next ->
        if (next - previous > ADDRESS_GAP) {
            cluster = mutableListOf(next)
            clusters += cluster
        } else {
            cluster += next
        }
    }
    return (clusters.firstOrNull { anchor in it } ?: clusters.first()).map { lines[it] }
}

/**
 * Is this line part of a postal address?
 *
 * **The comma was the whole test and it cost 45% of the addresses on thirty-nine real cards**
 * (SRS 1.211). It entered as a guard: `IS/S0 9001 & IS/S0 14001` carries "14001", and a
 * certification was being read as the anchor of an address, where an address line carries a comma
 * and a certification does not. The guard is sound about certifications and wrong about India: an
 * address printed one element to a line — *13th & 14th Floor* / *Building-Omega* / *Salt Lake
 * Electronics Complex* — carries no commas at all, and one such card lost **seven** address lines
 * while another lost only its flat number, `B-201`.
 *
 * **So the certification is excluded by name and the address is recognised by its own vocabulary**,
 * which is what the comma was standing in for. A line belongs to an address if it carries a
 * postcode, a comma, or one of the words an Indian address is built from.
 *
 * **What it must never swallow is a contact line**, and that is the real hazard rather than the
 * certification: `TEL. :91-0141-4044237, 2363015 MOBILE:91-94140-78411` has a comma and a run of
 * five digits that reads as a postcode, so the comma rule would have taken it too. A telephone
 * number is refused before anything else is asked.
 */
internal fun looksLikeAddressLine(line: String): Boolean {
    if (line.isBlank()) return false
    if (line.contains('@') || line.contains(DOMAIN_SHAPED)) return false
    if (CERTIFICATION.containsMatchIn(line)) return false
    // A number with a label, or a bare run long enough to be one, is the contact block.
    if (PHONE.containsMatchIn(line) && !POSTCODE.containsMatchIn(line)) return false
    if (PHONE_LABEL.containsMatchIn(line)) return false
    if (hasOrganisationSuffix(line) || looksLikeJobTitle(line)) return false
    if (',' in line || POSTCODE.containsMatchIn(line)) return true
    // Split on anything that is not a letter, so `Sector-V`, `Block-EP` and `Road,` all offer
    // the word they are built on. `WORD_BREAK` keeps hyphens and would have missed every one.
    return line.lowercase().split(NOT_LETTERS).any { it in ADDRESS_WORDS }
}

/**
 * How far a comma-only line may sit from one with real address vocabulary and still join it.
 *
 * Two, because one intervening line is the ordinary case — a website or a telephone number
 * printed between the street and the state — and three began collecting the company block.
 */
private const val COMMA_REACH = 2

/**
 * How far apart two address lines may sit and still describe the same place.
 *
 * Six, because a card routinely prints its telephone block *inside* its address — one did exactly
 * that, four numbers and an email between the building and the street — and because the second
 * address on a multi-office card sits further away than that.
 */
private const val ADDRESS_GAP = 6

/** Does this line carry the evidence that earns a place in an address outright? */
private fun hasAddressVocabulary(line: String): Boolean =
    POSTCODE.containsMatchIn(line) || line.lowercase().split(NOT_LETTERS).any { it in ADDRESS_WORDS }

/** Anything that is not a letter, for reading the word a hyphenated fragment is built on. */
private val NOT_LETTERS = Regex("[^a-z]+")

/** The certification lines whose numbers read as postcodes. */
private val CERTIFICATION = Regex("""(?i)(IS[O0]|IS/|OHSAS|certified)""")

/** A labelled number is the contact block however much it looks like an address. */
private val PHONE_LABEL = Regex("""(?i)(tel|mob|mobile|phone|fax|dir|direct|extn|off)[.: ]""")

/**
 * The words an Indian address is built from, which is what the comma was standing in for.
 *
 * Deliberately *not* an exhaustive gazetteer: this decides whether a line **continues** an address
 * already anchored on a postcode, so a word that appears on half of them is enough, and a list
 * long enough to catch a company name would be worse than the comma it replaces.
 */
private val ADDRESS_WORDS = setOf(
    "floor", "road", "street", "marg", "nagar", "block", "sector", "complex", "building",
    "tower", "plot", "house", "lane", "colony", "chowk", "bhawan", "bhavan", "centre", "center",
    "park", "area", "estate", "wing", "opp", "near", "phase", "extension", "market", "mall",
    "enclave", "vihar", "puram", "gate", "circle", "square", "district", "layout", "cross",
)

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
    // **A trade body is not an edge case in this corpus's domain** (SRS 1.210). Scanning
    // thirty-nine real cards produced a cement manufacturers' association, two mineral
    // federations and a bank, and not one of them could name its own employer because the list
    // knew only the shapes a private company takes. These are the words the rest of an economy
    // ends its name with.
    "association", "federation", "confederation", "chamber", "council", "institute",
    "society", "foundation", "bank", "mills", "cements", "minerals", "steels",
    // **"trust" was tried and withdrawn the same minute**, which is the corpus paying for itself:
    // it made the strapline "Trade with Trust" into somebody's employer - the very card SRS 1.109
    // records that phrase from. A word that ends a slogan as readily as a name cannot be a suffix.
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
    // **Words that belong to a claim about a company rather than to its name** (SRS 1.210). A card
    // printing "Grant Thornton Bharat LLP" also prints "Member firm of Grant Thornton
    // International Ltd" at its foot, and the footer carries the domain just as well - so a rule
    // choosing between them needs to know that "member" and "firm" are the vocabulary of a
    // disclaimer. This is the same list that already refuses "A Mini Ratna Company".
    "member", "firm", "unit", "regd", "office",
)

/**
 * FR-1230: is there a contact in this text worth *offering* to save?
 *
 * **It decides what is offered and never what is written**, which is FR-1202's rule and the
 * reason this can be a heuristic at all. A wrong `true` costs a button the user ignores; the
 * sheet behind it is still FR-1205's, every field editable and nothing written without a save.
 *
 * **A name or an email address, and deliberately not a telephone number.** Measured against
 * NFR-502's 113 real captured strings (SRS 1.151): the wider rule — anything `cardSaveBlocker`
 * would accept — fired on **eleven** of them, and every one fired on the phone rule. `12-09-2026`
 * and `shipping 2027.10.14` are seven digits, and no pattern distinguishes a numeric date from a
 * number somebody dials. Latch would have offered to save a renewal date as a person. This rule
 * fires on **none** of the 113, and on the corpus's own real email signature it finds the name,
 * the job title, the number and the address.
 *
 * That refusal is FR-1227's, arrived at from the other side: there a mobile number alone was
 * refused as an identity because a quarter of cards have none; here a number alone is refused as
 * *evidence* because a date has the same shape.
 */
fun holdsContact(classification: CardClassification): Boolean {
    val draft = classification.draft
    return !draft.displayName.isNullOrBlank() ||
        !draft.givenName.isNullOrBlank() ||
        !draft.familyName.isNullOrBlank() ||
        draft.emails.isNotEmpty()
}
