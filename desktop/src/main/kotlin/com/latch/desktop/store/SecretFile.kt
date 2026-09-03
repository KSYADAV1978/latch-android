package com.latch.desktop.store

import java.io.File

/**
 * NFR-203's store on Windows: named secrets, each encrypted on its own under DPAPI.
 *
 * **Per-record encryption rather than one encrypted file**, which is the shape
 * `EncryptedPreferences` and the write queue already use on Android. It costs a DPAPI call per
 * value and buys the property this project keeps insisting on: one record the operating system
 * will not decrypt — written by another user, restored from another machine — is dropped, and
 * every other record still reads. A single blob would lose all of them together.
 *
 * The file is a version, then one record per line, key and ciphertext separated by a tab. The
 * key is a name this application chose and is not a secret; the value never appears in it in
 * the clear. A leading version is the same discipline as every other record format here — an
 * unrecognised one is dropped rather than guessed at, which is what stops a future layout
 * being read as this one.
 */
class SecretFile(
    private val file: File,
    private val secrets: WindowsSecrets = WindowsSecrets(),
) {
    fun put(key: String, value: String) {
        val ciphertext = secrets.protect(value)
            ?: throw IllegalStateException("Windows would not encrypt this secret")
        write(read() + (key to ciphertext))
    }

    fun get(key: String): String? = read()[key]?.let { secrets.unprotect(it) }

    /**
     * Every named secret, decrypted in **one** crossing of the process boundary.
     *
     * A caller that wanted three values used to pay three PowerShell launches. Nothing about
     * the store changes — each record is still encrypted on its own and one the operating
     * system refuses still comes back null on its own.
     */
    fun getAll(keys: List<String>): Map<String, String> {
        val records = read()
        val present = keys.filter { it in records }
        val values = secrets.unprotectAll(present.map { records.getValue(it) })
        return present.zip(values).mapNotNull { (key, value) -> value?.let { key to it } }.toMap()
    }

    fun remove(key: String) {
        write(read() - key)
    }

    /**
     * NFR-205: everything, gone.
     *
     * The file is deleted rather than emptied. An empty file left behind still says which
     * machine ran this app and when it was last touched, and the user asked for their data
     * to be gone rather than to be blank.
     */
    fun clear() {
        file.delete()
    }

    fun keys(): Set<String> = read().keys

    private fun read(): Map<String, String> {
        if (!file.isFile) return emptyMap()
        val lines = runCatching { file.readLines() }.getOrElse { return emptyMap() }
        return parseSecretFile(lines)
    }

    private fun write(records: Map<String, String>) {
        file.parentFile?.mkdirs()
        val body = buildList {
            add(SECRET_FILE_VERSION)
            records.forEach { (key, ciphertext) -> add(key + "\t" + ciphertext) }
        }
        // Written beside the target and moved into place, so a process that dies mid-write
        // leaves the previous file intact rather than a truncated one. Every secret in here
        // costs the user a sign-in to replace.
        val staging = File(file.parentFile, file.name + ".new")
        staging.writeText(body.joinToString("\n"))
        if (!staging.renameTo(file)) {
            file.delete()
            check(staging.renameTo(file)) { "could not replace " + file }
        }
    }
}

const val SECRET_FILE_VERSION: String = "latch-secrets/1"

/**
 * The file's own format, separate from the disk so a test reaches every branch.
 *
 * A record whose key is empty, whose line has no tab, or which repeats a key is dropped rather
 * than repaired. Repairing would mean guessing which half of a corrupted line was the name of
 * a secret, and the cost of being wrong is handing back a value under the wrong name.
 */
internal fun parseSecretFile(lines: List<String>): Map<String, String> {
    if (lines.firstOrNull()?.trim() != SECRET_FILE_VERSION) return emptyMap()
    val records = LinkedHashMap<String, String>()
    lines.drop(1).forEach { line ->
        if (line.isBlank()) return@forEach
        val tab = line.indexOf('\t')
        if (tab <= 0 || tab == line.lastIndex) return@forEach
        val key = line.substring(0, tab)
        if (key in records) return@forEach
        records[key] = line.substring(tab + 1)
    }
    return records
}

/**
 * Where this application keeps its files.
 *
 * `%LOCALAPPDATA%` rather than `%APPDATA%`, and the difference matters in a managed
 * environment: roaming would copy a DPAPI blob to another machine, where it cannot be
 * decrypted, so the user would meet an unreadable secret rather than an absent one. Local is
 * the honest place for something the local machine's key protects.
 */
fun latchDataDirectory(): File {
    val local = System.getenv("LOCALAPPDATA")
    val base = if (!local.isNullOrBlank()) File(local) else File(System.getProperty("user.home"), ".latch")
    return File(base, "Latch")
}
