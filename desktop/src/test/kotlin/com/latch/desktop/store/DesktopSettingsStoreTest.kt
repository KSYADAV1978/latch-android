package com.latch.desktop.store

import com.latch.core.model.LatchSettings
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopSettingsStoreTest {

    private val directory: File = File.createTempFile("latch-settings", "").let {
        it.delete(); it.mkdirs(); it
    }
    private val file = File(directory, "secrets.dat")

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    private fun store() = DesktopSettingsStore(SecretFile(file, reversingSecrets()))

    @Test
    fun `settings survive a write and a fresh read of the file`() {
        store().write(DesktopSettings(hotkey = "Ctrl+Alt+J"))
        // A **second** store over the same file, so this is the disk and not the cache.
        assertEquals("Ctrl+Alt+J", store().read().hotkey)
    }

    @Test
    fun `the record is encrypted like every other secret on this machine`() {
        store().write(DesktopSettings(shared = LatchSettings(timeZone = "Asia/Kolkata")))
        val onDisk = file.readText()
        assertFalse("Asia/Kolkata" in onDisk, onDisk.take(200))
        assertTrue(DesktopSettings.KEY in onDisk, "the key is not a secret and stays legible")
    }

    @Test
    fun `an absent record reads as the shipped defaults`() {
        assertEquals(DesktopSettings(), store().read())
    }

    @Test
    fun `the read is cached, because it happens on the capture path`() {
        // NFR-101 budgets a capture 800 ms and the DPAPI bridge costs most of a second, so a
        // settings read per capture would be the largest single cost in it. The fixture makes
        // the second read observable: the file is deleted underneath, and a store that went
        // back to disk would answer with the defaults.
        val store = store()
        store.write(DesktopSettings(hotkey = "Ctrl+Alt+J"))
        assertEquals("Ctrl+Alt+J", store.read().hotkey)

        file.delete()
        assertEquals("Ctrl+Alt+J", store.read().hotkey)
    }

    @Test
    fun `a write invalidates the cache rather than leaving it behind`() {
        val store = store()
        assertEquals("Ctrl+Shift+K", store.read().hotkey)
        store.write(DesktopSettings(hotkey = "Ctrl+Alt+J"))
        assertEquals("Ctrl+Alt+J", store.read().hotkey)
    }

    @Test
    fun `NFR-205 clear forgets the record and the cache with it`() {
        val store = store()
        store.write(DesktopSettings(hotkey = "Ctrl+Alt+J"))
        store.clear()
        assertEquals(DesktopSettings(), store.read())
    }
}
