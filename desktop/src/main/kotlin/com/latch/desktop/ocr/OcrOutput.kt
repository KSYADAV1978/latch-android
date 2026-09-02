package com.latch.desktop.ocr

/**
 * One recognised row, with the box its words occupy.
 *
 * The geometry is kept rather than thrown away because two requirements need it downstream:
 * FR-509a takes a title from the row a date sits on, and FR-805b excerpts the rows around the
 * matched dates. Both are stated in terms of rows, and a recogniser that returned only a blob
 * of text would leave neither expressible.
 */
data class OcrLine(
    val text: String,
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
) {
    val bottom: Double get() = y + height
}

/** Why recognition produced nothing. Named, not phrased — NFR-402 leaves the wording to the UI. */
enum class OcrFailure {
    /** The WinRT projection is unavailable. A Windows too old, or a stripped installation. */
    NO_WINRT,

    /**
     * No OCR language pack is installed.
     *
     * This is the asymmetry between the two clients, and it is the reason this is its own
     * reason rather than folded into [RECOGNISER_FAILED]. Android bundles ML Kit's Latin and
     * Devanagari models and pays 12.83 MB for them, so recognition is a property of the app;
     * `Windows.Media.Ocr` reads only what the machine has, so it is a property of the machine.
     * A user whose phone reads a Devanagari notice and whose desktop does not is entitled to
     * be told which, and told that Windows adds packs under Language settings.
     */
    NO_RECOGNISER,

    /** The file could not be opened or decoded. */
    UNREADABLE_SOURCE,

    /** The recogniser ran and threw. */
    RECOGNISER_FAILED,

    /** The bridge itself failed — PowerShell missing, killed, or answering nothing usable. */
    BRIDGE_FAILED,

    /** Recognition succeeded and found no text. An empty photograph, not an error. */
    NO_TEXT,
}

/** What the recogniser had to say. */
sealed interface OcrOutcome {
    data class Recognised(
        val text: String,
        val lines: List<OcrLine>,
        val engineLanguage: String,
        val imageWidth: Int,
        val imageHeight: Int,
    ) : OcrOutcome

    data class Failed(val reason: OcrFailure, val detail: String = "") : OcrOutcome
}

/**
 * Reads the bridge's TSV.
 *
 * Pure, and separate from the process that produces it, for the reason `CLAUDE.md` records as
 * having cost this project a day twice: a decision inside something that needs a `Context` — or
 * here, a Windows and a language pack — is a decision no test will reach. Every judgement about
 * what the recogniser said is made here, where a test hands it a string.
 */
fun parseRecogniserOutput(stdout: String): OcrOutcome {
    val rows = stdout.lineSequence().map { it.trimEnd('\r') }.filter { it.isNotBlank() }.toList()

    rows.firstOrNull { it.startsWith("ERROR\t") }?.let { row ->
        val parts = row.split('\t')
        val reason = runCatching { OcrFailure.valueOf(parts.getOrElse(1) { "" }) }
            .getOrDefault(OcrFailure.BRIDGE_FAILED)
        return OcrOutcome.Failed(reason, parts.getOrElse(2) { "" })
    }

    val engine = rows.firstOrNull { it.startsWith("ENGINE\t") }?.split('\t')
        ?: return OcrOutcome.Failed(OcrFailure.BRIDGE_FAILED, "no ENGINE record")

    val lines = rows.filter { it.startsWith("LINE\t") }.mapNotNull { row ->
        // Text is last and is taken verbatim, so a tab the recogniser read out of the image
        // cannot shift a column. The script replaces tabs in it for the same reason; this is
        // the second half of that agreement and is stated in both places on purpose.
        val parts = row.split('\t', limit = 6)
        if (parts.size < 6) return@mapNotNull null
        val numbers = (1..4).map { parts[it].toDoubleOrNull() ?: return@mapNotNull null }
        OcrLine(parts[5], numbers[0], numbers[1], numbers[2], numbers[3])
    }

    val ordered = inReadingOrder(lines)
    val text = ordered.joinToString("\n") { it.text }
    if (text.isBlank()) return OcrOutcome.Failed(OcrFailure.NO_TEXT)

    return OcrOutcome.Recognised(
        text = text,
        lines = ordered,
        engineLanguage = engine.getOrElse(1) { "" },
        imageWidth = engine.getOrElse(2) { "" }.toDoubleOrNull()?.toInt() ?: 0,
        imageHeight = engine.getOrElse(3) { "" }.toDoubleOrNull()?.toInt() ?: 0,
    )
}

/**
 * Rows top to bottom, and left to right within a row.
 *
 * **The recogniser's own order is not trusted, and that is deliberate rather than defensive.**
 * On Android, ML Kit returned an early chat message after a later one, which put FR-505's
 * earliest-mention tie-break and §7.2's `item_key` — both of which derive from character
 * positions in the assembled text — on top of a library internal that an upgrade could move
 * without saying so. `Windows.Media.Ocr` happens to answer in reading order today. Sorting on
 * the geometry costs nothing and means one client's ordering cannot drift from the other's
 * because a Windows update changed a traversal.
 *
 * Two lines belong to the same row where they overlap vertically by more than half the shorter
 * one's height. A fixed pixel tolerance would fail on both a screenshot and a photographed
 * page, which differ in scale by an order of magnitude; a ratio of the text's own height does
 * not care how big the text is.
 */
fun inReadingOrder(lines: List<OcrLine>): List<OcrLine> {
    if (lines.size < 2) return lines

    val rows = mutableListOf<MutableList<OcrLine>>()
    lines.sortedBy { it.y }.forEach { line ->
        val current = rows.lastOrNull()
        if (current != null && sharesRow(current, line)) current += line else rows += mutableListOf(line)
    }
    return rows.flatMap { row -> row.sortedBy { it.x } }
}

private fun sharesRow(row: List<OcrLine>, line: OcrLine): Boolean {
    val top = row.minOf { it.y }
    val bottom = row.maxOf { it.bottom }
    val overlap = minOf(bottom, line.bottom) - maxOf(top, line.y)
    val shorter = minOf(bottom - top, line.height)
    return shorter > 0 && overlap > shorter / 2
}
