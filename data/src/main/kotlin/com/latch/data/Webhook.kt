package com.latch.data

import android.content.Context
import android.content.SharedPreferences
import com.latch.core.model.CaptureSource
import com.latch.core.model.Item
import com.latch.core.model.ItemType
import java.io.IOException
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * NFR-203's secret store: OAuth tokens and the FR-1004 webhook endpoint.
 *
 * The same [KeystoreCipher] every other store here uses, which is what NFR-203's recorded
 * reading asks for — "OAuth tokens and the FR-1004 webhook URL should reuse `KeystoreCipher`
 * rather than introduce a second scheme."
 *
 * **No OAuth token is actually kept here**, and that is stronger than keeping one safely: Play
 * services holds its own cache, tokens last an hour, and re-authorizing a granted scope set
 * returns one with no UI, so there is nothing at rest to protect. What this holds today is the
 * webhook endpoint, which NFR-203 names as a secret because such URLs commonly embed a bearer
 * token in the path or the query string.
 */
class EncryptedSecretStore(context: Context) : SecretStore {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    private val cipher = KeystoreCipher(KEY_ALIAS)

    override suspend fun put(key: String, value: String) {
        withContext(Dispatchers.IO) {
            val written = prefs.edit().putString(key, cipher.encrypt(value)).commit()
            check(written) { "Secret $key was not written to disk." }
        }
    }

    override suspend fun get(key: String): String? = withContext(Dispatchers.IO) {
        prefs.getString(key, null)?.let(cipher::decrypt)
    }

    override suspend fun clear() {
        withContext(Dispatchers.IO) { prefs.edit().clear().commit() }
    }

    companion object {
        /** FR-1004's endpoint. NFR-203 treats it as a secret and requires it masked once saved. */
        const val KEY_WEBHOOK_ENDPOINT = "webhook.endpoint"

        private const val PREFS_FILE = "secrets"
        private const val KEY_ALIAS = "latch.secrets.v1"
    }
}

/**
 * NFR-203: "It shall be masked in the Settings UI once saved."
 *
 * Scheme and host survive so the user can recognise which endpoint they configured; everything
 * after it does not, because that is where the bearer token lives when there is one. The length
 * of the mask is fixed rather than proportional — a mask that grew with the secret would leak
 * its length, which for a token is more than nothing.
 */
fun maskedEndpoint(url: String): String {
    val parsed = runCatching { URL(url) }.getOrNull() ?: return MASK
    val authority = "${parsed.protocol}://${parsed.host}"
    return if (parsed.path.isNullOrEmpty() && parsed.query == null) authority else "$authority/$MASK"
}

private const val MASK = "••••••••"

/**
 * FR-1004a: what a webhook payload may contain, and — more to the point — what it may not.
 *
 * The requirement is a closed list: "the parsed item fields (type, title, start, end, due date,
 * location, notes), the recipe applied, the chain identifier, the source application identifier,
 * and the capture timestamp. It shall **never** contain raw image bytes or the contents of a
 * captured file."
 *
 * **The closed list is why this is a function over an `Item` rather than a serialisation of
 * one.** A payload assembled by reflection, or by adding a field to a class that happens to be
 * serialised, would grow silently the next time `Item` did — and the thing that would leak is
 * whatever was added. Every key below is written out here, so adding one is a diff a reviewer
 * sees and an FR-1004a decision rather than a side effect.
 *
 * **`notes` is the FR-805 body the item is about to be written with** — which for an OCR
 * capture is FR-805b's extract and never the whole recognised text, and for a notification
 * capture is nothing at all, because `sourceBlock` has already decided that upstream and this
 * receives the composed result. The source *image* never appears in any form: FR-216 keeps it
 * off the device's own storage, and `Capture.rawText` holds text.
 *
 * **FR-210a is not enforced here**, deliberately: it decides whether a delivery happens at all,
 * which is [webhookEligible]'s job. A `CaptureSource` parameter on this function would look
 * like the rule lived here and would be checked by nobody.
 */
fun webhookPayload(
    item: Item,
    metadata: RemoteMetadata,
    body: String,
): String {
    val json = JSONObject()
        .put("type", if (item.type == ItemType.EVENT) "event" else "task")
        .put("title", item.title)
        .put("chain_id", metadata.chainId)
        .put("captured_at", metadata.capturedAt.toString())

    item.start?.let { json.put("start", it.toString()) }
    item.end?.let { json.put("end", it.toString()) }
    item.dueDate?.let { json.put("due_date", it.toString()) }
    item.location?.takeIf { it.isNotBlank() }?.let { json.put("location", it) }
    body.takeIf { it.isNotBlank() }?.let { json.put("notes", it) }
    metadata.recipeId?.let { json.put("recipe", it) }
    // Absent means unknown, exactly as §7.2 reads it. Never written empty.
    metadata.sourceApp?.let { json.put("source_app", it) }

    return json.toString()
}

/** A batch of items from one save, as one delivery. FR-1004 says "on save", not "per item". */
fun webhookPayloadForChain(
    items: List<Item>,
    metadata: RemoteMetadata,
    body: String,
): String {
    val array = JSONArray()
    items.forEach { array.put(JSONObject(webhookPayload(it, metadata, body))) }
    return JSONObject().put("version", 1).put("items", array).toString()
}

/**
 * Whether a webhook may be delivered for this capture at all.
 *
 * **FR-210a**: suppressed for anything from the notification listener, "whether or not a webhook
 * is configured". That layer carries the heaviest disclosure obligation under FR-1104, and
 * forwarding notification-derived content to an arbitrary third-party endpoint is inconsistent
 * with the basis on which the user granted notification access. It is the same exclusion
 * `CaptureSource.storesSourceText` and `routableToInbox` already carry, and it is structural
 * here for the same reason: a call site cannot forget a rule it never evaluates.
 */
fun webhookEligible(source: CaptureSource, enabled: Boolean, endpoint: String?): Boolean =
    enabled && !endpoint.isNullOrBlank() && source.webhookEligible

/**
 * Why an endpoint was refused. NFR-402: named, not phrased — the Settings screen says it.
 */
enum class EndpointRefusal {
    /** Not a URL at all. */
    MALFORMED,

    /**
     * Not HTTPS. Refused rather than warned about: FR-1004 sends the user's captured content to
     * this address, and over plaintext that is a decision no warning makes reasonable.
     */
    NOT_HTTPS,

    /** Credentials in the authority. A `user:pass@host` URL is a paste of something else. */
    CARRIES_CREDENTIALS,
}

/**
 * Validates an endpoint the user typed. Pure, so the rule is testable and the Settings screen
 * and the sender cannot disagree about what is acceptable.
 *
 * **This is deliberately not [requireGoogleEndpoint].** That guard exists to hold AC-17 — no
 * request to any non-Google endpoint in the app's default configuration — and a webhook is the
 * single documented exception to it (NFR-201, AC-18). Reusing the guard would be nonsense and
 * widening its allowlist would destroy the property it holds, so a webhook is sent by a
 * separate client with a separate, narrower set of rules. The separation is the point.
 */
fun validateEndpoint(raw: String): EndpointRefusal? {
    val url = runCatching { URL(raw.trim()) }.getOrNull() ?: return EndpointRefusal.MALFORMED
    if (url.protocol != "https") return EndpointRefusal.NOT_HTTPS
    if (url.userInfo != null) return EndpointRefusal.CARRIES_CREDENTIALS
    if (url.host.isNullOrBlank()) return EndpointRefusal.MALFORMED
    return null
}

/**
 * FR-1004b: **best-effort and strictly secondary to the Google write.**
 *
 * Every clause of that requirement is a property of this function or of where it is called.
 * It shall not be placed in the FR-806 write queue — it is not, and structurally cannot be:
 * everything in that queue drains through `CalendarApi`/`TasksApi` and their `ALLOWED_HOSTS`
 * guard, which would refuse any non-Google host. It shall not be retried beyond a single
 * immediate attempt — there is one `execute` and no loop. It shall never block or delay the
 * Google write, nor block FR-807 undo — the caller fires it after the write and does not await
 * it. A failure is reported passively and is **not** a failed API write under NFR-303.
 *
 * **Redirects are refused**, for the reason `GoogleHttp` refuses them: a redirect is the one way
 * a request approved by [validateEndpoint] could still arrive somewhere else, including at a
 * plaintext address.
 *
 * The timeouts are short and deliberately shorter than the Google client's. This is secondary
 * work; a slow endpoint must not hold a coroutine open behind a capture the user has finished
 * with.
 */
class WebhookSender {

    /** True where the endpoint accepted it. False is reported passively (FR-1004b), never thrown. */
    suspend fun deliver(endpoint: String, payload: String): Boolean {
        if (validateEndpoint(endpoint) != null) return false
        val url = runCatching { URL(endpoint.trim()) }.getOrNull() ?: return false
        val bytes = payload.toByteArray(Charsets.UTF_8)

        return runInterruptible(Dispatchers.IO) {
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                instanceFollowRedirects = false
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                setFixedLengthStreamingMode(bytes.size)
            }
            try {
                connection.outputStream.use { it.write(bytes) }
                connection.responseCode in 200..299
            } catch (failure: IOException) {
                false
            } catch (malformed: MalformedURLException) {
                false
            } finally {
                connection.disconnect()
            }
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 5_000
        const val READ_TIMEOUT_MS = 5_000
    }
}
