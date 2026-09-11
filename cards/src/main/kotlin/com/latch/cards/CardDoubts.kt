package com.latch.cards

import com.latch.core.model.CardDraft

/**
 * Which protected field a doubt is about.
 *
 * **FR-1204 refuses to repair a name, a telephone number or an email address** (SRS 1.211), because
 * those are the fields a user acts on and a wrong one is worse than a blank. That refusal is right
 * and it is not enough on its own: on 11 September two contacts reached a real account carrying
 * `Saraf@jsw.in` and `a.sunitha@vedata.co.in`, both structurally valid, both belonging to nobody,
 * and both placed in silence. Refusing to guess stopped the classifier *inventing* a value and did
 * nothing about it *placing a damaged one*.
 *
 * **So a protected field that is suspect says so.** That is FR-1224's "check it before you save"
 * made specific rather than general: a caption over every card teaches nothing, and a note against
 * the one field that is probably wrong is what a user can act on.
 */
enum class CardDoubtField { NAME, EMAIL }

/**
 * One thing worth checking, and where there is one, what it might be instead.
 *
 * [alternative] is a suggestion and never a correction: nothing here writes it anywhere.
 */
data class CardDoubt(val field: CardDoubtField, val alternative: String? = null)

/** Every doubt this card raises, in the order its fields appear on the sheet. */
fun cardDoubts(draft: CardDraft, unplaced: List<String>): List<CardDoubt> =
    listOfNotNull(nameDoubt(draft, unplaced), emailDoubt(draft, unplaced))

/**
 * Is the name perhaps one of the lines that was not placed?
 *
 * **Measured before it was written, and the measurement is why this asks rather than acts.** Across
 * the 47 readings of 11 September, preferring the line corroborated by the email's local part finds
 * `A.SUNITHA` on a card whose name came out as the business unit `Sterlite Copper` - and on another
 * card it proposes replacing the **correct** `Anand Mukherji` with `FIMI`, because that card prints
 * the federation's shared mailbox `fimi@fedmin.com`. The two are indistinguishable in shape: one
 * token, covering the whole local part. **An email cannot tell a person's address from an
 * organisation's**, so this evidence is good enough to raise a question and not good enough to take
 * an answer - the same weakness that merged two contacts in SRS 1.208.
 *
 * It fires only where the chosen name has **no** corroboration and another line has some, so a card
 * whose name already matches its email says nothing.
 */
internal fun nameDoubt(draft: CardDraft, unplaced: List<String>): CardDoubt? {
    val chosen = draft.displayName ?: return null
    val locals = draft.emails.map { it.address.substringBefore('@') }.flatMap(::wordsOf).toSet()
    if (locals.isEmpty()) return null
    if (wordsOf(chosen).any { it in locals }) return null
    val better = unplaced.firstOrNull { line ->
        line.length <= LONGEST_PLAUSIBLE_NAME && wordsOf(line).any { it in locals }
    } ?: return null
    return CardDoubt(CardDoubtField.NAME, better)
}

/**
 * Is the email address probably damaged?
 *
 * **Three signatures, each taken from an address that actually reached the developer's account**,
 * and each chosen because it fires on damage rather than on difference.
 *
 * **A top-level domain nobody uses.** `vinod.sharma@adani.cam`, where `.com` lost a stroke. That
 * domain matches its company perfectly, so nothing else here would catch it.
 *
 * **A local part left behind.** `Email;: prashantkumar. Saraf@jsw.in` came out as `Saraf@jsw.in`
 * with `prashantkumar.` unplaced. SRS 1.117 refuses to *join* those halves and is right - the same
 * shape occurs in prose and joining would invent an address - but the half that was placed is
 * itself a valid address belonging to nobody, and the user is left to notice a residue.
 *
 * **A domain that is nearly the company's and not quite.** `a.sunitha@vedata.co.in` on a card whose
 * company is `VEDANTA LIMITED` and whose website is `vedantalimited.com`. **Nearness is the whole
 * test and a plain mismatch deliberately says nothing**: a personal address, a parent group's
 * domain or a consultant's own are entirely different from the employer and all perfectly correct,
 * so only a domain within an edit or two - which is what damage looks like and what a different
 * company never looks like - raises anything.
 */
internal fun emailDoubt(draft: CardDraft, unplaced: List<String>): CardDoubt? {
    val email = draft.emails.firstOrNull()?.address ?: return null
    val domain = email.substringAfter('@', "").lowercase()
    if (domain.isBlank()) return null

    if (domain.substringAfterLast('.') !in KNOWN_TLDS) return CardDoubt(CardDoubtField.EMAIL)

    val local = email.substringBefore('@').lowercase()
    val stem = domain.split('.').firstOrNull().orEmpty()
    val known = wordsOf(draft.organisation.orEmpty()) + draft.urls.flatMap(::hostWordsOf)
    if (unplaced.any { strandsALocalPart(it, local, known + stem) }) {
        return CardDoubt(CardDoubtField.EMAIL)
    }

    if (stem.length < 4 || known.isEmpty() || known.any { it == stem }) return null
    val nearMiss = known.any { editsBetween(stem, it) in 1..MOST_EDITS_THAT_IS_STILL_DAMAGE }
    return if (nearMiss) CardDoubt(CardDoubtField.EMAIL) else null
}

/**
 * A word ending in a full stop that the address does not begin with is half of somebody's.
 *
 * **Unless it is the card's own name, and the first card this ran on proved it.** `Deloitte.` is a
 * logotype whose full stop is part of the mark, and it has every property this looks for: eight
 * letters, a trailing dot, and an email address that does not begin with it. What tells it from
 * `prashantkumar.` is that `deloitte` is the card's **own domain** — a fragment matching the host or
 * the company is the brand printed at the top of the card, not a local part left behind.
 */
private fun strandsALocalPart(line: String, local: String, cardsOwnWords: List<String>): Boolean =
    line.split(WHITESPACE).any { token ->
        val body = token.removeSuffix(".").lowercase()
        token.endsWith('.') &&
            body.length >= SHORTEST_STRANDED_LOCAL_PART &&
            body.all { it.isLetter() || it == '.' } &&
            !local.startsWith(body) &&
            cardsOwnWords.none { it == body || it.contains(body) || body.contains(it) }
    }

private fun hostWordsOf(url: String): List<String> =
    url.lowercase().split('.', '/').filter { it.length > 3 && it != "www" }

private fun wordsOf(text: String): List<String> =
    text.lowercase().split(NOT_ALPHANUMERIC).filter { it.length > 2 }

/** Levenshtein, which is all that "nearly the same word" needs and is cheap at these lengths. */
internal fun editsBetween(a: String, b: String): Int {
    if (a == b) return 0
    var previous = IntArray(b.length + 1) { it }
    for (i in 1..a.length) {
        val current = IntArray(b.length + 1)
        current[0] = i
        for (j in 1..b.length) {
            val substitution = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
            current[j] = minOf(substitution, previous[j] + 1, current[j - 1] + 1)
        }
        previous = current
    }
    return previous[b.length]
}

private val WHITESPACE = Regex("\\s+")

private val NOT_ALPHANUMERIC = Regex("[^a-z0-9]+")

/**
 * How far a domain may sit from the company's name and still be read as damage.
 *
 * Two. `vedata` is one deletion from `vedanta`; three begins to reach genuinely different short
 * words, and a false note against a correct address is what teaches a user to ignore all of them.
 */
private const val MOST_EDITS_THAT_IS_STILL_DAMAGE = 2

/** Below this a stranded fragment is an abbreviation - `Ltd.`, `Pvt.` - and not a local part. */
private const val SHORTEST_STRANDED_LOCAL_PART = 6

/** A name is a few words; a line longer than this is a sentence and not a candidate. */
private const val LONGEST_PLAUSIBLE_NAME = 40

/**
 * The endings a real address uses.
 *
 * **Deliberately short.** This decides whether to *ask*, so missing a rare top-level domain costs a
 * note the user dismisses, where listing every one would have cost the check that caught `.cam`.
 */
private val KNOWN_TLDS = setOf(
    "com", "in", "net", "org", "edu", "gov", "io", "biz", "info", "co", "uk", "us", "ai", "dev",
    "app", "me", "asia", "int", "mil", "name", "pro", "tv", "online", "site", "tech", "store",
)
