package com.latch.parser

/**
 * FR-509: use the selection verbatim if under 60 characters; otherwise take the first clause
 * up to sentence punctuation, capped at 50 characters; strip leading salutations and trailing
 * sign-offs.
 *
 * Stripping runs first and the length test applies to what is left, so a short message
 * wrapped in "Dear parents, … Regards" still yields the sentence in the middle rather than
 * failing the 60-character test on the packaging.
 */
object TitleExtractor {

    private const val VERBATIM_LIMIT = 60
    private const val CLAUSE_LIMIT = 50

    private val SALUTATION = Regex(
        """^\s*(?:hi|hello|hey|dear|dear\s+all|respected|namaste|namaskar)\b[^,\n]{0,40}(?:[,:]|\n)\s*""",
        RegexOption.IGNORE_CASE,
    )

    private val SIGN_OFF = Regex(
        """(?:\n|\s)*\b(?:regards|best\s+regards|warm\s+regards|kind\s+regards|thanks\s*(?:&|and)?\s*regards|thanks|thank\s+you|sincerely|cheers|yours\s+truly)\b[\s\S]*$""",
        RegexOption.IGNORE_CASE,
    )

    private val CLAUSE_END = Regex("""[.!?;\n]""")

    fun extract(original: String): Field<String> {
        val stripped = original
            .replace(SALUTATION, "")
            .replace(SIGN_OFF, "")
            .trim()
            .replace(Regex("""\s+"""), " ")

        if (stripped.isEmpty()) {
            return Field(original.trim().take(CLAUSE_LIMIT), Confidence.LOW)
        }
        if (stripped.length < VERBATIM_LIMIT) {
            return Field(stripped, Confidence.HIGH)
        }

        val clauseEnd = CLAUSE_END.find(stripped)?.range?.first ?: stripped.length
        val clause = stripped.substring(0, clauseEnd).trim()
        return Field(truncateOnWordBoundary(clause, CLAUSE_LIMIT), Confidence.MEDIUM)
    }

    private fun truncateOnWordBoundary(text: String, limit: Int): String {
        if (text.length <= limit) return text
        val cut = text.take(limit)
        val lastSpace = cut.lastIndexOf(' ')
        return (if (lastSpace > limit / 2) cut.take(lastSpace) else cut).trimEnd(',', ' ', '-')
    }
}
