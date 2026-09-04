package com.latch.cards

import com.latch.core.model.CardDraft
import com.latch.core.model.CardEmail
import com.latch.core.model.CardPhone

/**
 * FR-1204: vCard 2.1, 3.0 and 4.0, and MECARD, parsed deterministically.
 *
 * **Why this is its own module and pure.** A QR payload is a string with a right answer, so every
 * case belongs in a corpus that runs without a device — the argument `:parser` already makes. And
 * FR-800's lesson is that the deterministic half is settled before anything reaches an account.
 *
 * **Unknown properties are dropped, never guessed at.** A card carrying `X-SKYPE` or `BDAY`
 * contributes nothing rather than being squeezed into a field that nearly fits. This is design
 * principle 1's reasoning outside its own subject: a *wrong* telephone number is the same class of
 * harm as an invented date, and worse in one respect — nothing about it looks wrong until somebody
 * dials it.
 *
 * **A payload that does not parse is reported unreadable, not partially accepted.** Half a contact
 * looks exactly like a whole one on a preview screen.
 */
sealed interface CardParse {
    data class Parsed(val draft: CardDraft) : CardParse

    /** Nothing usable. [reason] is for the app to phrase; NFR-402 keeps wording out of here. */
    data class Unreadable(val reason: CardUnreadable) : CardParse
}

enum class CardUnreadable {
    /** Not a contact grammar at all — a URL, plain text, a Wi-Fi payload. */
    NOT_A_CARD,

    /** A contact grammar whose content yielded no usable field. */
    NO_FIELDS,
}

/**
 * Read a decoded QR payload.
 *
 * [payload] is the raw decoded text, exactly as the decoder produced it — unfolding, unescaping
 * and charset handling all happen here, because they are part of the grammar rather than of the
 * decoder.
 */
fun parseCard(payload: String): CardParse {
    val text = payload.trim()
    val draft = when {
        text.startsWith("BEGIN:VCARD", ignoreCase = true) -> parseVCard(text)
        text.startsWith("MECARD:", ignoreCase = true) -> parseMeCard(text)
        else -> return CardParse.Unreadable(CardUnreadable.NOT_A_CARD)
    }
    return if (draft.isEmpty) CardParse.Unreadable(CardUnreadable.NO_FIELDS)
    else CardParse.Parsed(draft)
}

// ---- vCard ---------------------------------------------------------------------------------

private fun parseVCard(text: String): CardDraft {
    var draft = CardDraft()
    var structuredName: Pair<String?, String?>? = null

    for (line in unfold(text)) {
        val property = property(line) ?: continue
        val value = property.value
        if (value.isBlank()) continue

        when (property.name) {
            "FN" -> draft = draft.copy(displayName = unescape(value).takeIf { it.isNotBlank() })

            "N" -> {
                // `N` is Family;Given;Additional;Prefix;Suffix. Missing components are normal.
                val parts = splitStructured(value)
                structuredName = parts.getOrNull(1)?.ifBlank { null } to parts.getOrNull(0)?.ifBlank { null }
            }

            "ORG" -> {
                // ORG is Company;Department;… — the first component is the organisation, and a
                // department appended to it would read as a company nobody works for.
                draft = draft.copy(organisation = splitStructured(value).firstOrNull()?.ifBlank { null })
            }

            "TITLE" -> draft = draft.copy(jobTitle = unescape(value))

            "TEL" -> draft = draft.copy(
                phones = draft.phones + CardPhone(unescape(value), property.type()),
            )

            "EMAIL" -> draft = draft.copy(
                emails = draft.emails + CardEmail(unescape(value), property.type()),
            )

            "ADR" -> {
                // ADR is PO;Extended;Street;Locality;Region;Postcode;Country. Joined with commas
                // and empty components dropped: a card that omits the PO box should not produce
                // an address beginning with a comma.
                val joined = splitStructured(value).filter { it.isNotBlank() }.joinToString(", ")
                if (joined.isNotBlank()) draft = draft.copy(addresses = draft.addresses + joined)
            }

            "URL" -> draft = draft.copy(urls = draft.urls + unescape(value))

            "NOTE" -> draft = draft.copy(note = unescape(value))

            // Everything else — BDAY, PHOTO, X-*, UID, REV, VERSION — is dropped on purpose.
            else -> Unit
        }
    }

    // `FN` is mandatory in vCard 3.0 and 4.0 and routinely absent in 2.1, so the structured name
    // is the fallback rather than the primary: where a card gives both, `FN` is what its author
    // chose to be called.
    val (given, family) = structuredName ?: (null to null)
    val display = draft.displayName ?: listOfNotNull(given, family)
        .joinToString(" ")
        .ifBlank { null }
    return draft.copy(displayName = display, givenName = given, familyName = family)
}

/**
 * One property line, split into name, parameters and value.
 *
 * The grouping prefix (`item1.TEL`) is stripped: Apple and several exporters emit it, it carries
 * no information this parser uses, and leaving it on turns every property into an unknown one.
 */
private class Property(val name: String, val parameters: List<String>, val value: String) {

    /**
     * The `TYPE` this property declares, or null.
     *
     * Both spellings are accepted — `TYPE=CELL` (3.0/4.0) and a bare `CELL` (2.1) — because real
     * cards use both and a card is not obliged to say which version it is. Parameters that are
     * plainly not types are skipped, so `CHARSET=UTF-8` does not become a phone label.
     */
    fun type(): String? {
        val typed = parameters.firstOrNull { it.startsWith("TYPE=", ignoreCase = true) }
        if (typed != null) return typed.substringAfter('=').takeIf { it.isNotBlank() }
        return parameters.firstOrNull { it.none { ch -> ch == '=' } && it.isNotBlank() }
    }
}

private fun property(line: String): Property? {
    val colon = line.indexOf(':')
    if (colon <= 0) return null
    val head = line.substring(0, colon)
    var value = line.substring(colon + 1)

    val pieces = head.split(';')
    // `item1.TEL` → `TEL`. A dot inside a parameter would be unusual; this only touches the name.
    val name = pieces.first().substringAfterLast('.').trim().uppercase()
    val parameters = pieces.drop(1).map { it.trim() }

    // vCard 2.1 permits quoted-printable, and cards produced by older exporters use it for any
    // non-ASCII character — so a Devanagari or accented name arrives as `=E0=A4` without it.
    if (parameters.any { it.equals("ENCODING=QUOTED-PRINTABLE", ignoreCase = true) ||
            it.equals("QUOTED-PRINTABLE", ignoreCase = true) }
    ) {
        value = decodeQuotedPrintable(value)
    }
    // **Not unescaped here.** `splitStructured` has to see `\;` as an escape rather than as a
    // separator, and unescaping first turns it into a real semicolon that then splits the value —
    // so `ORG:Smith\; Jones Ltd` becomes a company called "Smith" with a department nobody works
    // in. Unescaping belongs at the point of use, after any structured split.
    return Property(name, parameters, value.trim())
}

/**
 * Undo RFC 6350 line folding.
 *
 * A folded line is a CRLF followed by one space or tab, and the continuation begins at the
 * character after it. Folding is not optional in the wild: a long `ADR` or `NOTE` from any
 * conforming exporter is folded, and reading the fragments as separate properties loses the
 * property entirely rather than truncating it — which is the failure that looks like a card with
 * no address.
 */
internal fun unfold(text: String): List<String> {
    val out = mutableListOf<String>()
    for (raw in text.replace("\r\n", "\n").replace('\r', '\n').split('\n')) {
        if ((raw.startsWith(" ") || raw.startsWith("\t")) && out.isNotEmpty()) {
            out[out.lastIndex] = out.last() + raw.substring(1)
        } else {
            out += raw
        }
    }
    return out.filter { it.isNotBlank() }
}

/**
 * Split a structured value on unescaped semicolons.
 *
 * `;` separates components and `\;` is a literal one — so a company called "Smith; Jones" is one
 * component and splitting naively would invent a department.
 */
internal fun splitStructured(value: String): List<String> {
    val parts = mutableListOf<String>()
    val current = StringBuilder()
    var escaped = false
    for (ch in value) {
        when {
            escaped -> { current.append(ch); escaped = false }
            ch == '\\' -> { current.append(ch); escaped = true }
            ch == ';' -> { parts += unescape(current.toString()).trim(); current.setLength(0) }
            else -> current.append(ch)
        }
    }
    parts += unescape(current.toString()).trim()
    return parts
}

/** `\n` `\,` `\;` `\\` as RFC 6350 defines them. */
internal fun unescape(value: String): String {
    if ('\\' !in value) return value
    val out = StringBuilder(value.length)
    var index = 0
    while (index < value.length) {
        val ch = value[index]
        if (ch == '\\' && index + 1 < value.length) {
            when (val next = value[index + 1]) {
                'n', 'N' -> out.append('\n')
                '\\', ',', ';', ':' -> out.append(next)
                else -> { out.append(ch); out.append(next) }
            }
            index += 2
        } else {
            out.append(ch)
            index++
        }
    }
    return out.toString()
}

/** vCard 2.1 quoted-printable, including its soft line breaks. */
internal fun decodeQuotedPrintable(value: String): String {
    val bytes = ArrayList<Byte>(value.length)
    var index = 0
    while (index < value.length) {
        val ch = value[index]
        when {
            ch == '=' && index + 2 < value.length -> {
                val hex = value.substring(index + 1, index + 3)
                val byte = hex.toIntOrNull(16)
                if (byte == null) { bytes += ch.code.toByte(); index++ }
                else { bytes += byte.toByte(); index += 3 }
            }
            // A trailing `=` is a soft break: the line continues and the break is not content.
            ch == '=' -> index++
            else -> { bytes += ch.code.toByte(); index++ }
        }
    }
    return String(bytes.toByteArray(), Charsets.UTF_8)
}

// ---- MECARD --------------------------------------------------------------------------------

/**
 * MECARD, which is not vCard and is not a subset of it.
 *
 * `MECARD:N:Doe,John;TEL:0123;EMAIL:a@b.com;;` — fields separated by `;`, name components by `,`,
 * and the whole thing terminated by `;;`. It is what most Japanese and many Indian QR generators
 * emit, so it is not an edge case.
 */
private fun parseMeCard(text: String): CardDraft {
    var draft = CardDraft()
    val body = text.substring("MECARD:".length).removeSuffix(";;").removeSuffix(";")

    for (field in splitStructured(body)) {
        val colon = field.indexOf(':')
        if (colon <= 0) continue
        val name = field.substring(0, colon).trim().uppercase()
        val value = unescape(field.substring(colon + 1)).trim()
        if (value.isBlank()) continue

        when (name) {
            "N" -> {
                // Family,Given — the reverse of how it reads, and the reason this is not simply
                // passed through as a display name.
                val parts = value.split(',').map { it.trim() }
                draft = draft.copy(
                    familyName = parts.getOrNull(0)?.ifBlank { null },
                    givenName = parts.getOrNull(1)?.ifBlank { null },
                )
            }

            "TEL", "TELAV" -> draft = draft.copy(phones = draft.phones + CardPhone(value))
            "EMAIL" -> draft = draft.copy(emails = draft.emails + CardEmail(value))
            "ORG" -> draft = draft.copy(organisation = value)
            "TITLE" -> draft = draft.copy(jobTitle = value)
            "ADR" -> draft = draft.copy(addresses = draft.addresses + value.replace(',', ' ').trim())
            "URL" -> draft = draft.copy(urls = draft.urls + value)
            "NOTE" -> draft = draft.copy(note = value)
            else -> Unit
        }
    }

    val display = listOfNotNull(draft.givenName, draft.familyName).joinToString(" ").ifBlank { null }
    return draft.copy(displayName = display)
}
