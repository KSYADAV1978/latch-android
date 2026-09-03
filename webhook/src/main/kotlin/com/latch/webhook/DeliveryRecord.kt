package com.latch.webhook

import com.latch.google.json.JSONObject
import java.time.Instant

/**
 * FR-1004b's passive report, as a record.
 *
 * **Shared, because both clients keep one and neither should invent a second shape.** The
 * report itself is per client — a Settings screen phrases it (NFR-402) — but what is stored is
 * the same three facts, and a client that spelled `result` differently would show nothing after
 * an upgrade rather than a delivery.
 *
 * **It carries no endpoint**, deliberately: the screen this is shown on masks the endpoint
 * (NFR-203), so a record holding the address would be one render away from unmasking it.
 */
fun encodeDelivery(delivery: WebhookDelivery): String = JSONObject()
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
fun decodeDelivery(record: String): WebhookDelivery? {
    val json = runCatching { JSONObject(record) }.getOrNull() ?: return null
    val at = runCatching { Instant.parse(json.optString("at")) }.getOrNull() ?: return null
    val result = WebhookResult.entries.firstOrNull { it.name == json.optString("result") } ?: return null
    return WebhookDelivery(at, result, json.optInt("status", 0).takeIf { it != 0 })
}
