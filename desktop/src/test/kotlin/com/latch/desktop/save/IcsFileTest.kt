package com.latch.desktop.save

import com.latch.core.model.Item
import com.latch.core.model.ItemType
import java.io.File
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDateTime
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IcsFileTest {

    private val directory: File = File.createTempFile("latch-ics", "").let {
        it.delete(); it.mkdirs(); it
    }

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    private fun event(title: String = "Kickoff") = Item(
        id = "i1",
        captureId = "c1",
        chainId = "ch1",
        type = ItemType.EVENT,
        title = title,
        start = LocalDateTime.of(2027, 9, 8, 9, 0),
        end = LocalDateTime.of(2027, 9, 8, 10, 0),
        calendarId = "cal",
    )

    @Test
    fun `an export writes a calendar file`() {
        val file = IcsFile.write(listOf(event()), "ch1", "Asia/Kolkata", Instant.EPOCH, directory)
        assertTrue(file != null && file.isFile)
        val text = file!!.readText()
        assertTrue(text.startsWith("BEGIN:VCALENDAR"), text.take(40))
        assertTrue("END:VCALENDAR" in text)
    }

    @Test
    fun `the file is UTF-8 whatever the platform's default charset is`() {
        // A Windows JVM's default charset is not always UTF-8, and a file a calendar program
        // cannot decode is FR-1005's most likely failure: nothing opens it and nothing says
        // why. Writing bytes explicitly is what prevents it.
        val file = IcsFile.write(listOf(event("फ़ीस देय")), "ch1", "Asia/Kolkata", Instant.EPOCH, directory)!!
        val text = String(file.readBytes(), StandardCharsets.UTF_8)
        assertTrue("फ़ीस" in text, text)
    }

    @Test
    fun `an empty chain writes nothing rather than an empty calendar`() {
        assertNull(IcsFile.write(emptyList(), "ch1", "Asia/Kolkata", Instant.EPOCH, directory))
    }

    // ---- two exports must not collide ----------------------------------------------------

    @Test
    fun `exporting the same capture twice produces two files`() {
        // The same reason `exportItems` mints fresh UIDs, one level up: a user who exported,
        // edited and exported again has two things and should still have both. Overwriting is
        // the failure this guards.
        val first = File(directory, "Kickoff.ics").apply { writeText("first") }
        val second = uniqueIn(directory, "Kickoff.ics")
        assertNotEquals(first.name, second.name)
        assertFalse(second.exists())
        assertEquals("Kickoff-2.ics", second.name)
    }

    @Test
    fun `a third export finds the next free name`() {
        File(directory, "Kickoff.ics").writeText("a")
        File(directory, "Kickoff-2.ics").writeText("b")
        assertEquals("Kickoff-3.ics", uniqueIn(directory, "Kickoff.ics").name)
    }

    @Test
    fun `a name that is free is used as it stands`() {
        assertEquals("Kickoff.ics", uniqueIn(directory, "Kickoff.ics").name)
    }

    @Test
    fun `the destination is a folder a person would look in`() {
        // Deliberately not a temp directory, which is where the Android client puts it — there
        // the file is handed to another app through a FileProvider and the system clears the
        // directory anyway. On a desktop the user is given a file, and it must be somewhere
        // they can find it.
        val downloads = IcsFile.downloadsDirectory()
        assertTrue(downloads.isDirectory, downloads.path)
    }
}
