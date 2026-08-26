package com.latch.parser

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * NFR-502: the parser is exercised against a corpus of real-world strings held as data, so
 * that adding a case is adding a line to a `.tsv` rather than writing a test.
 *
 * Every case runs against one fixed clock. A parser that reads the wall clock cannot be
 * held to a corpus at all, which is why [ParseContext.now] is an input (see FR-503).
 */
class CorpusTest {

    private val context = ParseContext(
        now = LocalDateTime.of(2026, 8, 25, 9, 0), // a Tuesday
        zone = ZoneId.of("Asia/Kolkata"),
        dateOrder = DateOrder.DAY_FIRST,
    )

    @Test
    fun `every corpus case parses to its expected classification, date and time`() {
        val cases = loadCases()
        assertTrue(cases.isNotEmpty(), "No corpus cases were loaded — check the resource path")

        val failures = cases.mapNotNull { case ->
            val result = DateParser.parse(case.input, context)
            val candidate = result.primary
            val actualDate = candidate.date?.value
            val actualTime = candidate.time?.value

            val problems = buildList {
                if (candidate.classification != case.classification) {
                    add("classification ${candidate.classification}, expected ${case.classification}")
                }
                if (actualDate != case.date) add("date $actualDate, expected ${case.date}")
                if (actualTime != case.time) add("time $actualTime, expected ${case.time}")
            }
            if (problems.isEmpty()) null else "${case.origin}: \"${case.input}\" → ${problems.joinToString("; ")}"
        }

        if (failures.isNotEmpty()) {
            fail("${failures.size} of ${cases.size} corpus cases failed:\n" + failures.joinToString("\n"))
        }
    }

    @Test
    fun `corpus size is reported against the NFR-502 target`() {
        val size = loadCases().size
        println("Parser corpus: $size cases (NFR-502 requires 300 before sign-off)")
        assertTrue(size > 0)
    }

    private data class Case(
        val input: String,
        val classification: Classification,
        val date: LocalDate?,
        val time: LocalTime?,
        val origin: String,
    )

    private fun loadCases(): List<Case> = CORPUS_FILES.flatMap { file ->
        val resource = javaClass.getResourceAsStream("/corpus/$file")
            ?: fail("Missing corpus file: $file")
        resource.bufferedReader().readLines()
            .mapIndexedNotNull { index, line ->
                if (line.isBlank() || line.startsWith("#")) return@mapIndexedNotNull null
                val columns = line.split('\t')
                require(columns.size == 4) {
                    "$file line ${index + 1}: expected 4 tab-separated columns, found ${columns.size}"
                }
                Case(
                    input = columns[0],
                    classification = Classification.valueOf(columns[1].trim()),
                    date = columns[2].trim().takeIf { it != "-" }?.let(LocalDate::parse),
                    time = columns[3].trim().takeIf { it != "-" }?.let(LocalTime::parse),
                    origin = "$file:${index + 1}",
                )
            }
    }

    private companion object {
        val CORPUS_FILES = listOf(
            "dates-numeric.tsv",
            "dates-written.tsv",
            "relative-and-hinglish.tsv",
            "times-and-undated.tsv",
            "real-world.tsv",
        )
    }
}
