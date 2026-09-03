package com.latch.desktop.store

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SecretFileTest {

    /** base64 of "CBA", which the reversing cipher decrypts to "ABC". */
    private val GOOD = "Q0JB"

    /** Valid base64 the fussy cipher refuses, standing in for a record another user wrote. */
    private val BROKEN = "QkFE"

    private val TAB = 9.toChar().toString()

    private val NEWLINE = 10.toChar().toString()


    private val directory: File = File.createTempFile("latch-secrets", "").let {
        it.delete()
        it.mkdirs()
        it
    }
    private val file = File(directory, "secrets.dat")

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    /**
     * A cipher that is not one, so the format is testable without a Windows.
     *
     * It reverses the bytes, which makes it its own inverse, and it speaks the real bridge's
     * protocol — base64 in, base64 out. A fake that answered in a shape the real one never
     * produces would pass while the real path was broken, which is the failure this project
     * has already met once in `findEventBySourceHash`.
     */
    private fun reversing() = reversingSecrets()

    // ---- the file format --------------------------------------------------------------------

    @Test
    fun `a file with no version header reads as empty rather than as records`() {
        // The condition that would make this fail: treating an unrecognised layout as this
        // one. Every record format in this project drops rather than guesses, and the cost of
        // guessing here is handing back a value under a name that meant something else.
        assertEquals(emptyMap(), parseSecretFile(listOf("token\tabc")))
        assertEquals(emptyMap(), parseSecretFile(listOf("latch-secrets/2", "token\tabc")))
        assertEquals(emptyMap(), parseSecretFile(emptyList()))
    }

    @Test
    fun `a malformed record is dropped and its neighbours survive`() {
        val records = parseSecretFile(
            listOf(
                SECRET_FILE_VERSION,
                "first\tAAA",
                "no-tab-at-all",
                "\tempty-key",
                "trailing-tab\t",
                "",
                "last\tBBB",
            )
        )
        assertEquals(mapOf("first" to "AAA", "last" to "BBB"), records)
    }

    @Test
    fun `a repeated key keeps the first rather than the last`() {
        val records = parseSecretFile(listOf(SECRET_FILE_VERSION, "k\tONE", "k\tTWO"))
        assertEquals(mapOf("k" to "ONE"), records)
    }

    @Test
    fun `ciphertext may contain anything except a newline`() {
        // Base64 has no tab and no newline, but the parser splits on the first tab so that a
        // future encoding that does contain one still reads.
        val records = parseSecretFile(listOf(SECRET_FILE_VERSION, "k\tAA\tBB=="))
        assertEquals(mapOf("k" to "AA\tBB=="), records)
    }

    // ---- the store --------------------------------------------------------------------------

    @Test
    fun `a secret round trips and never appears in the file in the clear`() {
        val store = SecretFile(file, reversing())
        store.put("refresh_token", "1//0gTopSecretValue")

        assertEquals("1//0gTopSecretValue", store.get("refresh_token"))
        val onDisk = file.readText()
        assertFalse("1//0gTopSecretValue" in onDisk, onDisk)
        assertTrue("refresh_token" in onDisk, "the key is not a secret and stays legible")
    }

    @Test
    fun `one unreadable record does not cost the others`() {
        // The reason each value is encrypted on its own rather than the file as a whole. The
        // fixture makes exactly the failure real: a cipher that refuses one specific value.
        val fussy = reversingSecrets(refuses = setOf(BROKEN))
        file.parentFile.mkdirs()
        file.writeText(
            listOf(SECRET_FILE_VERSION, "good" + TAB + GOOD, "broken" + TAB + BROKEN)
                .joinToString(NEWLINE)
        )

        val store = SecretFile(file, fussy)
        assertEquals("ABC", store.get("good"))
        assertNull(store.get("broken"))
        assertEquals(setOf("good", "broken"), store.keys())
    }

    @Test
    fun `an absent key and an absent file both read as null`() {
        val store = SecretFile(file, reversing())
        assertNull(store.get("nothing"))
        store.put("a", "1")
        assertNull(store.get("nothing"))
    }

    @Test
    fun `removing takes one record and leaves the rest`() {
        val store = SecretFile(file, reversing())
        store.put("a", "1")
        store.put("b", "2")
        store.remove("a")
        assertNull(store.get("a"))
        assertEquals("2", store.get("b"))
    }

    @Test
    fun `NFR-205 clear deletes the file rather than emptying it`() {
        val store = SecretFile(file, reversing())
        store.put("a", "1")
        assertTrue(file.isFile)
        store.clear()
        assertFalse(file.exists(), "an empty file still says this machine ran Latch")
    }

    @Test
    fun `a cipher that refuses to encrypt fails loudly rather than storing nothing`() {
        // Silently storing nothing would mean a sign-in that appeared to work and did not
        // survive a restart, which is worse than an error at the moment it happened.
        val refusing = refusingSecrets()
        assertFailsWith<IllegalStateException> { SecretFile(file, refusing).put("k", "v") }
    }

    @Test
    fun `an empty secret is a value and not a failure`() {
        assertEquals("", readReply(BridgeReply("OK")))
        assertEquals("", readReply(BridgeReply("OK ")))
    }

    @Test
    fun `a timeout and an error both read as null`() {
        assertNull(readReply(BridgeReply("OK fine", timedOut = true)))
        assertNull(readReply(BridgeReply("ERR the operating system refused to decrypt this")))
        assertNull(readReply(BridgeReply("")))
    }

    // ---- the batch protocol -------------------------------------------------------------------

    @Test
    fun `one refusal in a batch costs that value and not its neighbours`() {
        // The property the whole per-record arrangement exists for, now that the *transport* is
        // shared: batching one PowerShell launch across many values must not batch the failure.
        // The condition that would make this fail is a bridge that gave up on the whole reply
        // after a bad line, and the fixture creates it — the middle value is refused.
        val replies = readReplies(BridgeReply("OK QUJD" + NEWLINE + "ERR no" + NEWLINE + "OK REVG"), 3)
        assertEquals(listOf("QUJD", null, "REVG"), replies)
    }

    @Test
    fun `a reply with the wrong number of lines is all null rather than misaligned`() {
        // Matching two answers to three questions by position is how a secret comes back under
        // another secret's name. Refusing to guess is the only safe reading.
        assertEquals(listOf(null, null, null), readReplies(BridgeReply("OK A" + NEWLINE + "OK B"), 3))
        assertEquals(listOf(null), readReplies(BridgeReply("OK A" + NEWLINE + "OK B"), 1))
    }

    @Test
    fun `blank lines between replies are not answers`() {
        assertEquals(listOf("A", "B"), readReplies(BridgeReply("OK A" + NEWLINE + NEWLINE + "OK B"), 2))
    }

    @Test
    fun `an empty batch never crosses the process boundary`() {
        // The fixture would throw if it ran, which is what makes this an assertion about the
        // bridge not being launched rather than about its answer.
        val exploding = WindowsSecrets { _, _ -> error("the bridge was launched for nothing") }
        assertEquals(emptyList(), exploding.unprotectAll(emptyList()))
        assertEquals(emptyList(), exploding.protectAll(emptyList()))
    }

    @Test
    fun `many values round trip in one crossing`() {
        var calls = 0
        val counting = WindowsSecrets { mode, payloads ->
            calls++
            BridgeReply(
                payloads.joinToString(NEWLINE) {
                    if (mode == WindowsSecrets.Mode.UNPROTECT) "OK " + base64(unbase64(it).reversedArray())
                    else "OK " + base64(unbase64(it).reversedArray())
                }
            )
        }
        val values = listOf("one", "two", "three", "फ़ीस")
        val protectedValues = counting.protectAll(values)
        assertEquals(1, calls, "one launch, not one per value")
        assertEquals(values, counting.unprotectAll(protectedValues.map { it!! }))
        assertEquals(2, calls)
    }

    @Test
    fun `the real bridge answers a batch line for line`() {
        assumeTrue(WindowsSecrets.isAvailable(), "not Windows")
        val secrets = WindowsSecrets()
        val values = listOf("first", "", "थर्ड with unicode", "fourth")
        val ciphertexts = secrets.protectAll(values)
        assertEquals(values.size, ciphertexts.size)
        assertTrue(ciphertexts.all { it != null }, "DPAPI refused part of a batch")
        assertEquals(values, secrets.unprotectAll(ciphertexts.map { it!! }))
    }

    // ---- the real DPAPI ----------------------------------------------------------------------

    @Test
    fun `a secret survives a round trip through the operating system's own key`() {
        assumeTrue(WindowsSecrets.isAvailable(), "not Windows")
        val secrets = WindowsSecrets()
        val plaintext = "1//0gRefresh-Token_with unicode फ़ीस and a \"quote\""

        val ciphertext = secrets.protect(plaintext)
        assertTrue(ciphertext != null && ciphertext.isNotBlank(), "DPAPI produced nothing")
        assertFalse(plaintext in ciphertext!!, "the plaintext is visible in the ciphertext")
        assertEquals(plaintext, secrets.unprotect(ciphertext))
    }

    @Test
    fun `ciphertext that has been tampered with is refused rather than half-decrypted`() {
        assumeTrue(WindowsSecrets.isAvailable(), "not Windows")
        val secrets = WindowsSecrets()
        val ciphertext = secrets.protect("original")!!
        val tampered = ciphertext.dropLast(8) + "AAAAAAAA"
        assertNull(secrets.unprotect(tampered))
    }

    @Test
    fun `the whole store works against real DPAPI`() {
        assumeTrue(WindowsSecrets.isAvailable(), "not Windows")
        val store = SecretFile(file)
        store.put("refresh_token", "1//0g-a-real-looking-token")
        assertEquals("1//0g-a-real-looking-token", store.get("refresh_token"))
        assertFalse("1//0g-a-real-looking-token" in file.readText())
        store.clear()
    }
}
