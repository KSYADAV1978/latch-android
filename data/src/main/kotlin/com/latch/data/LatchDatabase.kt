package com.latch.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * FR-701's local storage: one app-private SQLite database, hand-rolled.
 *
 * **Not Room**, and the reason is the build rather than the APK — Room needs KSP, which AGP 9's
 * built-in Kotlin support does not have, so adopting it means turning built-in Kotlin off across
 * every module to gain 36 KB of convenience. `docs/DEPENDENCIES.md` records that in full and
 * names hand-rolled `SQLiteOpenHelper` as the interim if the Inbox landed first. It did.
 *
 * **Encryption is per record and reuses [KeystoreCipher]**, exactly as `EncryptedPreferences`
 * and the write queue do, rather than encrypting the file. NFR-204's recorded reading is that
 * platform encryption of app-private storage already satisfies encryption at rest at minSdk 26
 * and that SQLCipher's 2 MB is not owed; what this adds on top is that a row's content is
 * unreadable even to something that has the file, at the cost of nothing but the same cipher
 * the rest of the app already uses.
 *
 * **What is deliberately *not* encrypted is the FR-803 hash index**, and that is a decision:
 * a SHA-256 digest is not content — NFR-206 says exactly that about `latch.source_hash` living
 * in a Google item — and an index that has to be decrypted row by row to be searched is not an
 * index at all. Everything with content beside a digest is in an encrypted payload column.
 *
 * **The migration discipline is the write queue's.** A schema version leads; an upgrade that
 * this code does not know how to perform drops and recreates rather than guessing, *except*
 * where dropping would lose something that exists nowhere else. That exception is why
 * [TABLE_INBOX] is preserved across an unknown upgrade and [TABLE_WRITTEN] is not: an Inbox
 * row is a capture the user made and nothing else holds, while the index is a cache of what is
 * already in their Google account and rebuilds itself as items are written.
 */
internal class LatchDatabase(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DATABASE_NAME, null, SCHEMA_VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        // Off by default on Android, and this schema has none — set explicitly so that adding
        // one later is a decision rather than a surprise about which pragma was in force.
        db.setForeignKeyConstraintsEnabled(false)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_INBOX (
                id TEXT PRIMARY KEY NOT NULL,
                captured_at INTEGER NOT NULL,
                snoozed_until INTEGER,
                state TEXT NOT NULL,
                payload TEXT NOT NULL
            )
            """.trimIndent()
        )
        // Ordering the list is the one query this table does beyond reading it whole, and
        // FR-705 wants the oldest first so an aged capture surfaces rather than sinking.
        db.execSQL("CREATE INDEX ${TABLE_INBOX}_by_age ON $TABLE_INBOX (captured_at)")

        db.execSQL(
            """
            CREATE TABLE $TABLE_WRITTEN (
                remote_id TEXT NOT NULL,
                container_id TEXT NOT NULL,
                item_type TEXT NOT NULL,
                source_hash TEXT NOT NULL,
                item_key TEXT NOT NULL,
                dates TEXT NOT NULL,
                written_at INTEGER NOT NULL,
                PRIMARY KEY (container_id, remote_id)
            )
            """.trimIndent()
        )
        // The two lookups FR-803 and FR-804 make. Both are in the clear because both are
        // digests; see this class's note.
        db.execSQL("CREATE INDEX ${TABLE_WRITTEN}_by_hash ON $TABLE_WRITTEN (source_hash)")
        db.execSQL("CREATE INDEX ${TABLE_WRITTEN}_by_key ON $TABLE_WRITTEN (item_key)")

        db.execSQL(
            """
            CREATE TABLE $TABLE_UNDO (
                chain_id TEXT PRIMARY KEY NOT NULL,
                expires_at INTEGER NOT NULL,
                payload TEXT NOT NULL
            )
            """.trimIndent()
        )
    }

    /**
     * There is no upgrade path yet because there is no version to upgrade from, and writing a
     * speculative one would be a migration nobody has run.
     *
     * What is written is the rule the next version has to follow. The index and the undo table
     * are caches of state that lives in the user's Google account or expires in ten seconds, so
     * dropping them costs nothing and they are recreated. **The Inbox is not**: those rows are
     * captures that exist nowhere else, so an upgrade that cannot carry them forward must fail
     * loudly rather than drop them — losing a capture is the worst outcome this specification
     * admits (design principle 1) and a silent `DROP TABLE` is the quietest way to do it.
     */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        error(
            "No migration from schema $oldVersion to $newVersion. " +
                "$TABLE_INBOX holds captures that exist nowhere else and must be carried " +
                "forward, not dropped."
        )
    }

    /**
     * A downgrade means a build older than the data. The caches go; the Inbox stays and is read
     * by whatever of it the older code understands, which the record's own leading version
     * makes safe — an unreadable row decodes to null and is dropped one at a time rather than
     * the table being emptied.
     */
    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_WRITTEN")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_UNDO")
        onCreateCaches(db)
    }

    private fun onCreateCaches(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_WRITTEN (
                remote_id TEXT NOT NULL,
                container_id TEXT NOT NULL,
                item_type TEXT NOT NULL,
                source_hash TEXT NOT NULL,
                item_key TEXT NOT NULL,
                dates TEXT NOT NULL,
                written_at INTEGER NOT NULL,
                PRIMARY KEY (container_id, remote_id)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS ${TABLE_WRITTEN}_by_hash ON $TABLE_WRITTEN (source_hash)")
        db.execSQL("CREATE INDEX IF NOT EXISTS ${TABLE_WRITTEN}_by_key ON $TABLE_WRITTEN (item_key)")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_UNDO (
                chain_id TEXT PRIMARY KEY NOT NULL,
                expires_at INTEGER NOT NULL,
                payload TEXT NOT NULL
            )
            """.trimIndent()
        )
    }

    /** NFR-205: one action deletes all local data. Everything this file owns, in one place. */
    fun deleteEverything() {
        writableDatabase.run {
            delete(TABLE_INBOX, null, null)
            delete(TABLE_WRITTEN, null, null)
            delete(TABLE_UNDO, null, null)
        }
    }

    companion object {
        const val DATABASE_NAME = "latch.db"

        /** Bumping this requires an [onUpgrade] that carries [TABLE_INBOX] forward. */
        const val SCHEMA_VERSION = 1

        const val TABLE_INBOX = "inbox"
        const val TABLE_WRITTEN = "written_items"
        const val TABLE_UNDO = "undo_offers"
    }
}

/** `use` for a cursor, so no read path can leave one open. */
internal inline fun <T> Cursor.consume(read: (Cursor) -> T): T = use(read)

internal fun contentValuesOf(vararg pairs: Pair<String, Any?>): ContentValues =
    ContentValues().apply {
        pairs.forEach { (key, value) ->
            when (value) {
                null -> putNull(key)
                is String -> put(key, value)
                is Int -> put(key, value)
                is Long -> put(key, value)
                is Boolean -> put(key, value)
                else -> put(key, value.toString())
            }
        }
    }
