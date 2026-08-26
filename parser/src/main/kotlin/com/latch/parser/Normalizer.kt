package com.latch.parser

/**
 * Lower-cases and folds digit forms without changing the length of the string.
 *
 * Length preservation is the whole point: every rule reports the span it matched, and the
 * UI shows the user which part of their selection produced which field. If normalisation
 * collapsed whitespace those spans would no longer index into the original text.
 */
internal object Normalizer {

    private const val DEVANAGARI_ZERO = '०'
    private const val DEVANAGARI_NINE = '९'

    fun normalize(text: String): String = buildString(text.length) {
        for (ch in text) {
            append(
                when (ch) {
                    in DEVANAGARI_ZERO..DEVANAGARI_NINE -> '0' + (ch - DEVANAGARI_ZERO)
                    ' ' -> ' ' // non-breaking space, common in text pasted from the web
                    '–', '—' -> '-' // en dash, em dash — used as time-range separators
                    '‘', '’' -> '\''
                    else -> ch.lowercaseChar()
                },
            )
        }
    }
}
