package com.latch.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

        /**
         * FR-1004b's passive report: what the last delivery attempt did.
         *
         * **Not a secret**, and kept here anyway — it lives beside the endpoint it is about, in
         * the one store NFR-205's deletion already enumerates. What it carries is a moment, an
         * outcome and the status the endpoint gave; the address is deliberately not in it,
         * because the screen it is shown on masks the address.
         */
        const val KEY_WEBHOOK_LAST_DELIVERY = "webhook.last_delivery"

        private const val PREFS_FILE = "secrets"
        private const val KEY_ALIAS = "latch.secrets.v1"
    }
}
