package com.latch.desktop.store

import com.latch.google.json.JSONObject
import com.latch.webhook.WebhookDelivery
import com.latch.webhook.WebhookResult
import java.time.Instant

/**
 * FR-1004's endpoint and FR-1004b's passive report, on this machine.
 *
 * **The endpoint is a secret and is stored as one** (NFR-203), under its own key in the same
 * DPAPI file as the refresh token — not inside [DesktopSettings]. That is the phone's division
 * followed rather than re-decided: such URLs commonly carry a bearer token in the path, and
 * keeping it apart means a settings record discloses nothing about where a user's captures go.
 *
 * **The delivery report is not a secret and deliberately carries no endpoint.** FR-1004b says a
 * failure "shall be reported passively in the Settings screen", and that screen shows the
 * endpoint masked — so a record holding the address would be one render away from unmasking it.
 * What it holds is when, what happened, and the status the endpoint gave.
 */
class WebhookSecrets(private val secrets: SecretFile) {

    /** Null where none is configured. Blank is stored by [clearEndpoint] and reads as null. */
    fun endpoint(): String? = secrets.get(KEY_ENDPOINT)?.trim()?.takeIf { it.isNotEmpty() }

    fun setEndpoint(raw: String) = secrets.put(KEY_ENDPOINT, raw.trim())

    /**
     * FR-1004: clearing the endpoint leaves nowhere to send.
     *
     * The **caller** turns the feature off with it. Doing it here would mean this class wrote
     * the settings record too, and one store owning another's record is how two writers of one
     * file appear.
     */
    fun clearEndpoint() = secrets.remove(KEY_ENDPOINT)

    fun lastDelivery(): WebhookDelivery? = secrets.get(KEY_LAST_DELIVERY)?.let(::decodeDelivery)

    fun recordDelivery(delivery: WebhookDelivery) =
        secrets.put(KEY_LAST_DELIVERY, encodeDelivery(delivery))

    companion object {
        const val KEY_ENDPOINT: String = "webhook.endpoint"
        const val KEY_LAST_DELIVERY: String = "webhook.last_delivery"
    }
}

internal fun encodeDelivery(delivery: WebhookDelivery): String = JSONObject()
    .put("at", delivery.at.toString())
    .put("result", delivery.result.name)
    .put("status", delivery.status)
    .toString()

/**
 * Null where the record is unreadable or from a version this build does not know.
 *
 * Dropped rather than kept, and this is the one webhook record where that is the right way
 * round: it holds no capture. Losing it means the Settings screen says nothing about the last
 * delivery until the next one, which is a smaller cost than showing a result that might be a
 * misread of some other version's record.
 */
internal fun decodeDelivery(record: String): WebhookDelivery? {
    val json = runCatching { JSONObject(record) }.getOrNull() ?: return null
    val at = runCatching { Instant.parse(json.optString("at")) }.getOrNull() ?: return null
    val result = WebhookResult.entries.firstOrNull { it.name == json.optString("result") } ?: return null
    return WebhookDelivery(at, result, json.optInt("status", 0).takeIf { it != 0 })
}
