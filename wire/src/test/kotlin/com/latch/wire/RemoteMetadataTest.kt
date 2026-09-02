package com.latch.wire

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * SRS §7.2. Two things are under test and they matter for different reasons.
 *
 * The **hash vectors** are the AC-07 contract: three independently written clients must
 * derive the same digest from the same message, and prose will not hold them together. The
 * vector file is the conformance suite, and this test is the Android client sitting it.
 *
 * The **codec** is the part that cannot be corrected later. Metadata is written into items
 * in a user's Google account, where FR-803, FR-804 and FR-807 read it back; an item written
 * with a missing or malformed key stays that way permanently.
 */
class RemoteMetadataTest {

    private val metadata = RemoteMetadata(
        sourceHash = "a".repeat(64),
        itemKey = "b".repeat(64),
        chainId = "3f2504e0-4f89-41d3-9a0c-0305e82c3301",
        capturedAt = Instant.parse("2026-08-26T14:03:22Z"),
        sourceApp = "android:com.google.android.gm",
        recipeId = "builtin.meeting_prep",
    )

    // ----- The AC-07 cross-client contract -----

    @Test
    fun `every golden vector reproduces its recorded digest`() {
        val failures = vectors().mapNotNull { (name, input, expected) ->
            val actual = sourceHashOf(input)
            if (actual == expected) null else "$name: expected $expected, got $actual"
        }
        // Collected rather than fast-failed, the way the parser corpus does it: one run
        // shows every divergence, not just the first.
        if (failures.isNotEmpty()) {
            fail("${failures.size} of ${vectors().size} hash vectors failed:\n" + failures.joinToString("\n"))
        }
    }

    @Test
    fun `the ways one message reaches two platforms differently all collapse together`() {
        val byName = vectors().associate { it.name to it.expected }
        val plain = byName.getValue("plain")
        listOf(
            "crlf_vs_lf", "lf_baseline", "trailing_space", "leading_space",
            "tabs_and_runs", "nbsp", "zero_width_joiner", "bom_prefix", "mixed_case",
        ).forEach { name ->
            assertEquals(plain, byName.getValue(name), "$name must hash the same as plain")
        }
        assertEquals(byName.getValue("nfc_cafe"), byName.getValue("nfd_cafe"))
        assertEquals(byName.getValue("empty"), byName.getValue("whitespace_only"))
    }

    @Test
    fun `genuinely different messages do not collide`() {
        val distinct = setOf("plain", "nfc_cafe", "emoji", "devanagari", "empty")
        val digests = vectors().filter { it.name in distinct }.map { it.expected }.toSet()
        assertEquals(distinct.size, digests.size)
    }

    @Test
    fun `the empty digest is the known SHA-256 of the empty string`() {
        // An external anchor: if this drifts, the hashing itself is wrong, not the
        // normalisation, and every vector above would drift with it undetected.
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            sourceHashOf(""),
        )
    }

    @Test
    fun `item key is a function of the title alone`() {
        // Deliberately excludes source_app, which has no producer yet: including it would
        // give the same meeting different keys either side of the day capture learns to
        // read it, and FR-804 would break exactly across that boundary.
        assertEquals(itemKeyOf("Team sync"), itemKeyOf("  TEAM   SYNC  "))
        assertEquals(sourceHashOf("Team sync"), itemKeyOf("Team sync"))
    }

    // ----- Events -----

    @Test
    fun `event properties round trip`() {
        assertEquals(metadata, remoteMetadataFromEventProperties(metadata.toEventProperties()))
    }

    @Test
    fun `optional keys are absent rather than empty`() {
        val bare = metadata.copy(sourceApp = null, recipeId = null)
        val properties = bare.toEventProperties()
        assertFalse(properties.containsKey(KEY_SOURCE_APP))
        assertFalse(properties.containsKey(KEY_RECIPE))
        assertEquals(bare, remoteMetadataFromEventProperties(properties))
    }

    @Test
    fun `an item from a newer client is recognised as ours but not interpreted`() {
        val future = metadata.toEventProperties() + (KEY_VERSION to "2")
        assertNull(remoteMetadataFromEventProperties(future))
        // Still ours, so nothing may modify or delete it on the assumption that it is not.
        assertTrue(carriesLatchMetadata(future))
    }

    @Test
    fun `properties that are not ours at all yield nothing`() {
        val foreign = mapOf("someOtherApp.id" to "123")
        assertNull(remoteMetadataFromEventProperties(foreign))
        assertFalse(carriesLatchMetadata(foreign))
    }

    @Test
    fun `a malformed digest is refused rather than acted on`() {
        val badHash = metadata.toEventProperties() + (KEY_SOURCE_HASH to "not-a-digest")
        assertNull(remoteMetadataFromEventProperties(badHash))

        val shortHash = metadata.toEventProperties() + (KEY_ITEM_KEY to "abc123")
        assertNull(remoteMetadataFromEventProperties(shortHash))

        val upperHash = metadata.toEventProperties() + (KEY_SOURCE_HASH to "A".repeat(64))
        assertNull(remoteMetadataFromEventProperties(upperHash))
    }

    @Test
    fun `a missing mandatory key yields nothing`() {
        listOf(KEY_SOURCE_HASH, KEY_ITEM_KEY, KEY_CHAIN_ID, KEY_CAPTURED_AT).forEach { key ->
            assertNull(
                remoteMetadataFromEventProperties(metadata.toEventProperties() - key),
                "removing $key should make the metadata unreadable",
            )
        }
    }

    @Test
    fun `captured at is whole seconds in UTC`() {
        val precise = metadata.copy(capturedAt = Instant.parse("2026-08-26T14:03:22.987654321Z"))
        assertEquals("2026-08-26T14:03:22Z", precise.toEventProperties().getValue(KEY_CAPTURED_AT))
    }

    @Test
    fun `every key and value fits what Google accepts`() {
        // 44 characters per key, 1024 per value, 300 properties. Enforced here rather than
        // remembered, because exceeding either is a rejected write at the worst moment.
        metadata.toEventProperties().forEach { (key, value) ->
            assertTrue(key.length <= 44, "key too long: $key")
            assertTrue(value.length <= 1024, "value too long for $key")
        }
        assertTrue(metadata.toEventProperties().size <= 300)
    }

    // ----- Tasks -----

    @Test
    fun `task notes round trip and keep the user's text`() {
        val userNotes = "Discussed in the thread from Priya.\nhttps://example.invalid/t/42"
        val notes = metadata.toTaskNotes(userNotes)

        assertEquals(metadata, remoteMetadataFromTaskNotes(notes))
        assertEquals(userNotes, taskNotesWithoutMetadata(notes))
    }

    @Test
    fun `task notes work when there is nothing else to say`() {
        val notes = metadata.toTaskNotes("")
        assertEquals(metadata, remoteMetadataFromTaskNotes(notes))
        assertEquals("", taskNotesWithoutMetadata(notes))
    }

    @Test
    fun `the user may edit their own notes above the metadata`() {
        val notes = metadata.toTaskNotes("original")
        val edited = notes.replace("original", "rewritten by the user\nover two lines")
        assertEquals(metadata, remoteMetadataFromTaskNotes(edited))
    }

    @Test
    fun `a corrupted metadata line degrades quietly`() {
        // Task notes are user-editable, so this is a matter of when, not if. The item
        // becomes unmanaged, which is honest; throwing here would take out the write path.
        val mangled = metadata.toTaskNotes("keep").replace(";at=", ";at")
        assertNull(remoteMetadataFromTaskNotes(mangled))

        assertNull(remoteMetadataFromTaskNotes("just some notes"))
        assertNull(remoteMetadataFromTaskNotes(""))
        assertNull(remoteMetadataFromTaskNotes("[latch]"))
        assertNull(remoteMetadataFromTaskNotes("[latch]v=1;sh=nonsense"))
    }

    @Test
    fun `the last metadata line wins`() {
        val superseded = metadata.copy(chainId = "00000000-0000-4000-8000-000000000000")
        val notes = superseded.toTaskNotes("notes") + "\n" +
            metadata.toTaskNotes("").trim()
        assertEquals(metadata.chainId, assertNotNull(remoteMetadataFromTaskNotes(notes)).chainId)
    }

    @Test
    fun `the metadata line stays inside the notes budget`() {
        // Google caps notes at 8192 characters and FR-805 wants the source text in there too.
        assertTrue(metadata.toTaskNotes("").length < 300, "metadata line is ${metadata.toTaskNotes("").length} chars")
    }

    // ----- the vector file -----

    private data class Vector(val name: String, val input: String, val expected: String)

    private fun vectors(): List<Vector> {
        val text = checkNotNull(javaClass.getResourceAsStream("/metadata/hash_vectors.tsv")) {
            "Missing hash_vectors.tsv"
        }.bufferedReader().use { it.readText() }

        return text.lineSequence()
            // Only the line ending, never the columns: the trailing_space case carries
            // significant spaces inside its input column. Git rewrites this file to CRLF on
            // checkout, and any client reading it on another platform faces the same.
            .map { it.removeSuffix("\r") }
            .filterNot { it.isBlank() || it.startsWith("#") }
            .map { line ->
                val columns = line.split("\t")
                require(columns.size == 3) { "Malformed vector row: $line" }
                Vector(columns[0], unescape(columns[1]), columns[2])
            }
            .toList()
    }

    /**
     * The vector file is pure ASCII on purpose — an editor normalising Unicode on save would
     * otherwise turn the NFD case into the NFC one and it would pass while testing nothing.
     * So the inputs arrive escaped and are decoded here.
     */
    private fun unescape(escaped: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < escaped.length) {
            if (escaped[i] != '\\') {
                out.append(escaped[i])
                i++
                continue
            }
            when (escaped[i + 1]) {
                'n' -> { out.append('\n'); i += 2 }
                't' -> { out.append('\t'); i += 2 }
                'r' -> { out.append('\r'); i += 2 }
                '\\' -> { out.append('\\'); i += 2 }
                'u' -> { out.append(escaped.substring(i + 2, i + 6).toInt(16).toChar()); i += 6 }
                'U' -> { out.appendCodePoint(escaped.substring(i + 2, i + 10).toInt(16)); i += 10 }
                else -> error("Unknown escape in vector: ${escaped[i + 1]}")
            }
        }
        return out.toString()
    }
}
