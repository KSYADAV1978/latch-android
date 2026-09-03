package com.latch.desktop.store

import com.latch.desktop.ui.deliveryText
import com.latch.desktop.ui.endpointLine
import com.latch.webhook.WebhookDelivery
import com.latch.webhook.decodeDelivery
import com.latch.webhook.encodeDelivery
import com.latch.webhook.WebhookResult
import java.io.File
import java.time.Instant
import java.time.ZoneId
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** FR-1004's endpoint at rest (NFR-203) and FR-1004b's passive report. */
class WebhookSecretsTest {

    private val directory: File = File.createTempFile("latch-webhook", "").let {
        it.delete(); it.mkdirs(); it
    }
    private val file = File(directory, "secrets.dat")

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    private fun store() = WebhookSecrets(SecretFile(file, reversingSecrets()))

    private val endpoint = "https://hooks.example.invalid/services/T000/B000/XXXXsecretXXXX"

    @Test
    fun `the endpoint is stored encrypted and never appears in the file in the clear`() {
        // NFR-203 names it a secret because such URLs commonly carry a bearer token in the
        // path. The condition that would make this fail is a store that treated it as an
        // ordinary preference.
        store().setEndpoint(endpoint)
        val onDisk = file.readText()
        // Not the bare word "secret", which the file's own version header carries.
        assertFalse("XXXXsecretXXXX" in onDisk, onDisk.take(200))
        assertFalse("hooks.example.invalid" in onDisk)
        assertTrue(WebhookSecrets.KEY_ENDPOINT in onDisk, "the key is not a secret and stays legible")
        assertEquals(endpoint, store().endpoint())
    }

    @Test
    fun `an absent or blank endpoint reads as none`() {
        assertNull(store().endpoint())
        store().setEndpoint("   ")
        assertNull(store().endpoint(), "a blank endpoint is not an endpoint")
    }

    @Test
    fun `clearing it leaves nothing behind`() {
        val store = store()
        store.setEndpoint(endpoint)
        store.clearEndpoint()
        assertNull(store.endpoint())
        assertFalse("webhook.endpoint" in file.readText())
    }

    @Test
    fun `the endpoint is stored apart from the settings record`() {
        // The phone's division followed rather than re-decided: a settings record read by
        // anything without the key must disclose nothing about where a user's captures go.
        assertFalse(WebhookSecrets.KEY_ENDPOINT == DesktopSettings.KEY)
        val secrets = SecretFile(file, reversingSecrets())
        WebhookSecrets(secrets).setEndpoint(endpoint)
        DesktopSettingsStore(secrets).write(DesktopSettings())
        assertFalse(endpoint in secrets.get(DesktopSettings.KEY).orEmpty())
    }

    // ---- FR-1004b's passive report -------------------------------------------------------------

    @Test
    fun `a delivery record survives a round trip`() {
        val delivery = WebhookDelivery(
            Instant.parse("2026-09-03T09:15:00Z"),
            WebhookResult.ENDPOINT_REFUSED,
            404,
        )
        val store = store()
        store.recordDelivery(delivery)
        assertEquals(delivery, store().lastDelivery())
    }

    @Test
    fun `a delivery record with no status round trips too`() {
        val delivery = WebhookDelivery(Instant.parse("2026-09-03T09:15:00Z"), WebhookResult.UNREACHABLE)
        store().recordDelivery(delivery)
        assertEquals(delivery, store().lastDelivery())
        assertNull(store().lastDelivery()?.status)
    }

    @Test
    fun `the delivery record carries no endpoint`() {
        // FR-1004b's report is shown on the screen that masks the endpoint (NFR-203), so a
        // record holding the address would be one render away from unmasking it.
        val encoded = encodeDelivery(
            WebhookDelivery(Instant.parse("2026-09-03T09:15:00Z"), WebhookResult.DELIVERED, 200)
        )
        assertFalse("http" in encoded, encoded)
    }

    @Test
    fun `an unreadable delivery record reads as nothing rather than as a wrong answer`() {
        assertNull(decodeDelivery("{"))
        assertNull(decodeDelivery("{\"result\":\"DELIVERED\"}"))
        assertNull(decodeDelivery("{\"at\":\"2026-09-03T09:15:00Z\",\"result\":\"TELEPATHY\"}"))
    }

    @Test
    fun `no delivery yet says nothing at all`() {
        assertNull(store().lastDelivery())
        assertNull(deliveryText(null))
    }

    @Test
    fun `every outcome reads differently, and a success is reported as well as a failure`() {
        // A webhook that silently works and one that silently does nothing look identical, and
        // the second is the state a user actually needs to notice — so both are said.
        val at = Instant.parse("2026-09-03T09:15:00Z")
        val zone = ZoneId.of("Asia/Kolkata")
        val lines = WebhookResult.entries.map {
            assertNotNull(deliveryText(WebhookDelivery(at, it, if (it == WebhookResult.ENDPOINT_REFUSED) 404 else null), zone))
        }
        assertEquals(lines.size, lines.toSet().size, "two outcomes read the same")
        assertTrue(lines.all { "14:45" in it }, lines.toString())
        assertTrue(
            lines.any { "404" in it },
            "the status the endpoint gave is what tells a wrong path from a wrong token",
        )
    }

    // ---- NFR-203's mask on the screen -----------------------------------------------------------

    @Test
    fun `the endpoint line masks what is stored and says so when nothing is`() {
        assertEquals("Not configured", endpointLine(null))
        val line = endpointLine(endpoint)
        assertTrue(line.startsWith("https://hooks.example.invalid"))
        assertFalse("secret" in line)
    }
}
