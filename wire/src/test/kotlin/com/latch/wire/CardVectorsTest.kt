package com.latch.wire

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The cross-client conformance suite for FR-1207 and FR-1209.
 *
 * **This file is why `card_vectors.tsv` is worth having.** The vectors are generated
 * independently of the Kotlin that has to match them, so a change to `normaliseForHash` or to
 * `normaliseEmail` that looks harmless fails here rather than in somebody's contacts — which is
 * the same standing `metadata/hash_vectors.tsv` has for §7.2, one pillar over.
 *
 * A second client will be written against this file rather than against the prose, for the reason
 * §7.2 records: prose will not hold two codebases byte-identical and a digest will.
 */
class CardVectorsTest {

    private data class Vector(
        val name: String,
        val payload: String,
        val sourceHash: String,
        val normalisedEmail: String,
        val identityKey: String,
    )

    /**
     * Expand the file's escapes.
     *
     * `\r` before `\n`, because doing it the other way round turns the two-character sequence
     * `\r\n` into a real carriage return followed by the letter n.
     */
    private fun expand(field: String): String =
        field.replace("\\r", "\r").replace("\\n", "\n")

    private val vectors: List<Vector> by lazy {
        val stream = checkNotNull(javaClass.getResourceAsStream("/metadata/card_vectors.tsv")) {
            "card_vectors.tsv is missing; the conformance suite cannot be skipped by deleting it"
        }
        stream.bufferedReader().readLines()
            .filterNot { it.startsWith("#") || it.isBlank() }
            .map { line ->
                val f = line.split("\t")
                check(f.size == 5) { "malformed vector row: $line" }
                Vector(f[0], expand(f[1]), f[2], f[3], f[4])
            }
    }

    @Test
    fun `the suite is present and not silently empty`() {
        // A conformance file that fails to load would make every test below vacuously pass,
        // which is the failure mode a golden-vector suite has and a unit test does not.
        assertTrue(vectors.size >= 8, "only ${vectors.size} vectors loaded")
    }

    @Test
    fun `every vector's source hash is reproduced`() {
        vectors.forEach { v ->
            assertEquals(v.sourceHash, cardSourceHashOf(v.payload), "source_hash for ${v.name}")
        }
    }

    @Test
    fun `every vector's identity key is reproduced`() {
        vectors.forEach { v ->
            val expected = v.identityKey.takeIf { it.isNotBlank() }
            val actual = v.normalisedEmail.takeIf { it.isNotBlank() }?.let { identityKeyOf(it) }
            assertEquals(expected, actual, "identity_key for ${v.name}")
        }
    }

    @Test
    fun `email normalisation matches the file`() {
        vectors.filter { it.normalisedEmail.isNotBlank() }.forEach { v ->
            // The file stores the normalised form; feeding it back must be a no-op, which is
            // what makes the normalisation idempotent rather than merely consistent.
            assertEquals(v.normalisedEmail, normaliseEmail(v.normalisedEmail), v.name)
        }
    }

    @Test
    fun `CRLF and LF forms of one card agree`() {
        // Two decoders are entitled to differ here, and FR-1208 requires them to find each
        // other's items anyway.
        val lf = vectors.single { it.name == "plain_vcard" }
        val crlf = vectors.single { it.name == "crlf_vcard" }
        assertEquals(lf.sourceHash, crlf.sourceHash)
    }

    @Test
    fun `the dotted and undotted addresses stay different people`() {
        // The pair exists to stop a future client "helpfully" adding provider rules. Dot
        // stripping is right for one provider and merges colleagues everywhere else.
        val dotted = vectors.single { it.name == "dotted" }
        val undotted = vectors.single { it.name == "undotted" }
        assertTrue(dotted.identityKey != undotted.identityKey)
        assertTrue(
            !sharesContactIdentity(listOf(dotted.identityKey), listOf(undotted.identityKey)),
        )
    }

    @Test
    fun `a card with no email carries no identity in the file either`() {
        assertEquals("", vectors.single { it.name == "no_email" }.identityKey)
    }
}
