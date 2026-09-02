package com.latch.desktop.capture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HotkeyTest {

    private fun parsed(text: String): HotkeySpec {
        val result = parseHotkey(text)
        assertIs<HotkeyParse.Parsed>(result, "expected '$text' to parse")
        return result.spec
    }

    @Test
    fun `FR-302's default parses to Ctrl plus Shift plus K`() {
        val spec = parsed(HotkeySpec.DEFAULT)
        assertEquals(HotkeySpec.MOD_CONTROL or HotkeySpec.MOD_SHIFT or HotkeySpec.MOD_NOREPEAT, spec.modifiers)
        assertEquals(0x4B, spec.virtualKey, "K is virtual key 0x4B")
        assertEquals("Ctrl+Shift+K", spec.display)
    }

    @Test
    fun `every hotkey asks Windows not to repeat it`() {
        // Without MOD_NOREPEAT, holding the combination for a moment opens a capture window
        // per repeat — each of which reads the clipboard, and each of which must be dismissed.
        listOf("Ctrl+Shift+K", "Alt+F9", "Win+Space", "Ctrl+Alt+Shift+1").forEach {
            assertTrue(parsed(it).modifiers and HotkeySpec.MOD_NOREPEAT != 0, it)
        }
    }

    @Test
    fun `a bare key is refused because it would break that key everywhere`() {
        // Windows registers `K` system-wide quite happily, and the letter K then stops working
        // in every other application on the machine.
        assertEquals(
            HotkeyParse.Rejected(HotkeyProblem.NO_MODIFIER, "K"),
            parseHotkey("K"),
        )
    }

    @Test
    fun `modifiers with no key, and two keys, are both refused by name`() {
        assertEquals(HotkeyParse.Rejected(HotkeyProblem.NO_KEY), parseHotkey("Ctrl+Shift"))
        assertEquals(
            HotkeyParse.Rejected(HotkeyProblem.TOO_MANY_KEYS, "K+J"),
            parseHotkey("Ctrl+K+J"),
        )
        assertEquals(HotkeyParse.Rejected(HotkeyProblem.EMPTY), parseHotkey("   "))
    }

    @Test
    fun `a key this build does not know is named rather than guessed at`() {
        assertEquals(
            HotkeyParse.Rejected(HotkeyProblem.UNKNOWN_KEY, "Scroll"),
            parseHotkey("Ctrl+Scroll"),
        )
        assertNull(virtualKeyOf("F25"), "there is no F25")
        assertNull(virtualKeyOf(""))
    }

    @Test
    fun `spelling and case do not matter, and the display is normalised`() {
        assertEquals("Ctrl+Shift+K", parsed("control + SHIFT + k").display)
        assertEquals("Ctrl+Alt+Shift+K", parsed("shift+alt+ctrl+K").display)
        assertEquals("Win+Space", parsed("meta+space").display)
    }

    @Test
    fun `function keys, digits and named keys map to their Win32 codes`() {
        assertEquals(0x70, parsed("Ctrl+F1").virtualKey)
        assertEquals(0x7B, parsed("Ctrl+F12").virtualKey)
        assertEquals(0x87, parsed("Ctrl+F24").virtualKey)
        assertEquals(0x31, parsed("Ctrl+1").virtualKey)
        assertEquals(0x20, parsed("Ctrl+Space").virtualKey)
        assertEquals(0x2E, parsed("Ctrl+Delete").virtualKey)
        assertEquals(0x22, parsed("Ctrl+PgDn").virtualKey)
    }

    // ---- the sidecar's protocol ---------------------------------------------------------------

    @Test
    fun `the sidecar's words are read, and a blank line is not an event`() {
        assertEquals(HotkeyEvent.Ready, readHotkeyLine("READY"))
        assertEquals(HotkeyEvent.Pressed, readHotkeyLine("PRESSED"))
        assertEquals(HotkeyEvent.Pressed, readHotkeyLine("PRESSED\r"))
        assertNull(readHotkeyLine(""))
        assertNull(readHotkeyLine("   "))
    }

    @Test
    fun `a combination another application already holds is its own answer`() {
        // 1409 is ERROR_HOTKEY_ALREADY_REGISTERED. It is not a defect and not worth retrying:
        // the user picks a different combination, and can only do that if told which case it is.
        assertEquals(
            HotkeyEvent.Refused(ERROR_HOTKEY_ALREADY_REGISTERED),
            readHotkeyLine("ERR 1409"),
        )
    }

    @Test
    fun `anything else the sidecar says is broken rather than silently ignored`() {
        assertEquals(HotkeyEvent.Broken("ERR not-a-number"), readHotkeyLine("ERR not-a-number"))
        assertIs<HotkeyEvent.Broken>(readHotkeyLine("Exception calling Register"))
    }

    @Test
    fun `the listener reports events from a sidecar that is not one`() {
        // The process is faked, so the reader thread, the protocol and the shutdown are all
        // exercised on any platform. What is not covered here is RegisterHotKey itself, which
        // needs a person to press keys — that is in the device backlog rather than pretended at.
        val script = "READY\nPRESSED\nPRESSED\n"
        val seen = mutableListOf<HotkeyEvent>()
        val done = java.util.concurrent.CountDownLatch(3)

        GlobalHotkey(parsed("Ctrl+Shift+K")) { FakeProcess(script) }.use { hotkey ->
            hotkey.start { event -> synchronized(seen) { seen += event }; done.countDown() }
            assertTrue(done.await(5, java.util.concurrent.TimeUnit.SECONDS), "events did not arrive")
        }
        assertEquals(listOf(HotkeyEvent.Ready, HotkeyEvent.Pressed, HotkeyEvent.Pressed), seen)
    }
}

class HotkeyLiveTest {

    /**
     * FR-302's registration, against the real `RegisterHotKey`.
     *
     * **What this does and does not settle.** It proves the whole chain up to the point a
     * human is required: `Add-Type` compiles the P/Invoke with the compiler inside Windows,
     * `RegisterHotKey` accepts the modifiers and virtual key this build computes, and the
     * sidecar reports READY over the pipe. What it cannot do is press the keys — that is a
     * person, and it is in the device backlog rather than pretended at here.
     *
     * A deliberately obscure combination is used so a run on a working machine does not fail
     * because something reasonable already holds Ctrl+Shift+K.
     */
    @Test
    fun `Windows accepts the registration this build computes`() {
        org.junit.jupiter.api.Assumptions.assumeTrue(
            System.getProperty("os.name").orEmpty().startsWith("Windows"), "not Windows"
        )
        val spec = (parseHotkey("Ctrl+Alt+Shift+F24") as HotkeyParse.Parsed).spec
        val events = java.util.concurrent.LinkedBlockingQueue<HotkeyEvent>()

        GlobalHotkey(spec).use { hotkey ->
            hotkey.start { events.offer(it) }
            val first = events.poll(30, java.util.concurrent.TimeUnit.SECONDS)
            assertEquals(
                HotkeyEvent.Ready, first,
                "expected the sidecar to register; got " + first,
            )
        }
    }

    @Test
    fun `a combination already held is reported rather than swallowed`() {
        org.junit.jupiter.api.Assumptions.assumeTrue(
            System.getProperty("os.name").orEmpty().startsWith("Windows"), "not Windows"
        )
        val spec = (parseHotkey("Ctrl+Alt+Shift+F23") as HotkeyParse.Parsed).spec
        val first = java.util.concurrent.LinkedBlockingQueue<HotkeyEvent>()
        val second = java.util.concurrent.LinkedBlockingQueue<HotkeyEvent>()

        GlobalHotkey(spec).use { holder ->
            holder.start { first.offer(it) }
            assertEquals(HotkeyEvent.Ready, first.poll(30, java.util.concurrent.TimeUnit.SECONDS))

            GlobalHotkey(spec).use { second_listener ->
                second_listener.start { second.offer(it) }
                assertEquals(
                    HotkeyEvent.Refused(ERROR_HOTKEY_ALREADY_REGISTERED),
                    second.poll(30, java.util.concurrent.TimeUnit.SECONDS),
                    "a second registration of the same combination should be refused with 1409",
                )
            }
        }
    }
}

/**
 * A process that reads back a script and then **stays open**, so the listener is testable off
 * Windows.
 *
 * The staying open is the part that matters. The first version ended its stream after the last
 * line, and the listener correctly reported that the sidecar had stopped — which is right for a
 * real one, whose stream ending means the registration is gone, and wrong for a fake standing
 * in for a process that sits waiting for the next keypress. A fake that behaves in a way the
 * real thing never does produces failures that are about the fake.
 */
private class FakeProcess(script: String) : Process() {
    private val stream = ScriptThenBlock(script.toByteArray())
    override fun getOutputStream() = java.io.OutputStream.nullOutputStream()
    override fun getInputStream(): java.io.InputStream = stream
    override fun getErrorStream() = java.io.InputStream.nullInputStream()
    override fun waitFor() = 0
    override fun exitValue() = 0
    override fun destroy() { stream.release() }
    override fun destroyForcibly(): Process { stream.release(); return this }
}

/** Hands back [bytes], then blocks until released — as a live sidecar's pipe does. */
private class ScriptThenBlock(private val bytes: ByteArray) : java.io.InputStream() {
    private var at = 0
    private val gate = java.util.concurrent.CountDownLatch(1)

    override fun read(): Int {
        if (at < bytes.size) return bytes[at++].toInt() and 0xFF
        gate.await()
        return -1
    }

    /**
     * Returns only what is there rather than waiting for a full buffer.
     *
     * `BufferedReader` fills greedily through `InputStream`'s default array read, which loops
     * on single bytes and would block at the gate before handing back the first line — so a
     * stream that is deliberately open-ended has to answer this itself.
     */
    override fun read(destination: ByteArray, offset: Int, length: Int): Int {
        if (at >= bytes.size) {
            gate.await()
            return -1
        }
        val count = minOf(length, bytes.size - at)
        System.arraycopy(bytes, at, destination, offset, count)
        at += count
        return count
    }

    override fun available(): Int = bytes.size - at

    fun release() = gate.countDown()
}
