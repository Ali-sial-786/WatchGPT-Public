package io.github.alisial786.watchgpt.organizer

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import io.github.alisial786.watchgpt.reminders.Entry
import io.github.alisial786.watchgpt.reminders.EntryCodec
import io.github.alisial786.watchgpt.reminders.Merge
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** App-private SQLite index; titles, notes, dates and record contents are authenticated ciphertext. No backup. */
internal class EntryDatabase(context: Context) : SQLiteOpenHelper(context, File(context.noBackupFilesDir, "organizer.db").absolutePath, null, 2) {
    private val alias = "${context.packageName}.organizer.v1"
    private var key: SecretKey? = null

    /** Decrypted rows by id, reused while their ciphertext is unchanged: Keystore decryption is an IPC per row. */
    private val decoded = HashMap<String, Pair<ByteArray, Entry>>()

    /** Rows that could not be decrypted or read in the last pass; kept on disk, left out of the list. */
    @Volatile var unreadable = 0
        private set

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE entries(id TEXT PRIMARY KEY, revision INTEGER NOT NULL, payload BLOB NOT NULL)")
        db.execSQL("CREATE TABLE meta(name TEXT PRIMARY KEY, value TEXT NOT NULL)")
        db.execSQL("CREATE TABLE delivered(token TEXT PRIMARY KEY)")
        onUpgrade(db, 1, 2)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL("CREATE TABLE IF NOT EXISTS unreadable(id TEXT NOT NULL, payload BLOB NOT NULL, moved INTEGER NOT NULL)")
    }

    @Synchronized fun meta(name: String): String? = readableDatabase.query("meta", arrayOf("value"), "name=?", arrayOf(name), null, null, null).use { if (it.moveToFirst()) it.getString(0) else null }

    @Synchronized fun meta(name: String, value: String) {
        writableDatabase.insertWithOnConflict(
            "meta",
            null,
            ContentValues().apply {
                put("name", name)
                put("value", value)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    /** Removes every meta value whose name starts with [prefix]. */
    @Synchronized fun clearMeta(prefix: String) {
        writableDatabase.delete("meta", "name LIKE ? ESCAPE '\\'", arrayOf(prefix.replace("\\", "\\\\").replace("_", "\\_").replace("%", "\\%") + "%"))
    }

    @get:Synchronized val device: String get() = meta("device") ?: UUID.randomUUID().toString().also { meta("device", it) }

    @Synchronized fun all(): List<Entry> {
        var failed = 0
        val seen = HashSet<String>()
        val list = readableDatabase.query("entries", arrayOf("id", "payload"), null, null, null, null, null).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val id = c.getString(0)
                    seen += id
                    read(id, c.getBlob(1))?.let(::add) ?: failed++
                }
            }
        }
        decoded.keys.retainAll(seen)
        unreadable = failed
        return list
    }

    @Synchronized fun get(id: String): Entry? = readableDatabase.query("entries", arrayOf("payload"), "id=?", arrayOf(id), null, null, null).use { if (it.moveToFirst()) read(id, it.getBlob(0)) else null }

    @Synchronized fun save(entry: Entry): Entry {
        entry.validate()
        val db = writableDatabase
        db.beginTransaction()
        try {
            val old = get(entry.id)
            if (old?.deleted == true) throw ItemChangedException("This item was deleted on another device. Create a new item instead.")
            if (old != null && (old.revision != entry.revision || old.author != entry.author)) throw ItemChangedException(STALE)
            val revision = maxOf(meta("clock")?.toLongOrNull() ?: 0, old?.revision ?: 0, entry.revision) + 1
            val saved = entry.copy(revision = revision, author = device)
            write(saved)
            meta("clock", revision.toString())
            db.setTransactionSuccessful()
            return saved
        } finally {
            db.endTransaction()
        }
    }

    /** Applies a record from the other device or the calendar; true when it changed what this device holds. */
    @Synchronized fun merge(entry: Entry): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val old = get(entry.id)
            val next = Merge.winner(old, entry)
            meta("clock", maxOf(meta("clock")?.toLongOrNull() ?: 0, entry.revision).toString())
            if (next != old) write(next)
            db.setTransactionSuccessful()
            return next != old
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized fun wasDelivered(token: String): Boolean = readableDatabase.rawQuery("SELECT 1 FROM delivered WHERE token=?", arrayOf(token)).use { it.moveToFirst() }

    @Synchronized fun delivered(token: String) {
        writableDatabase.execSQL("INSERT OR IGNORE INTO delivered(token) VALUES(?)", arrayOf(token))
    }

    /** Forgets delivery receipts of deleted items; every other receipt is still needed to avoid ringing twice. */
    @Synchronized fun forgetDeliveries(id: String) {
        writableDatabase.execSQL("DELETE FROM delivered WHERE token LIKE ? ESCAPE '\\'", arrayOf(id.replace("_", "\\_").replace("%", "\\%") + "%"))
    }

    private fun write(entry: Entry) {
        val payload = encrypt(entry.id, EntryCodec.encode(entry).toByteArray())
        writableDatabase.insertWithOnConflict(
            "entries",
            null,
            ContentValues().apply {
                put("id", entry.id)
                put("revision", entry.revision)
                put("payload", payload)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        decoded[entry.id] = payload to entry
    }

    private fun read(id: String, payload: ByteArray): Entry? {
        decoded[id]?.let { (bytes, entry) -> if (bytes.contentEquals(payload)) return entry }
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, payload.copyOfRange(0, 12)))
            cipher.updateAAD(id.toByteArray())
            EntryCodec.decode(cipher.doFinal(payload.copyOfRange(12, payload.size)).decodeToString()).also { decoded[id] = payload to it }
        } catch (e: KeyUnavailable) {
            throw e
        } catch (_: Exception) {
            // A corrupt or undecryptable row must not take every other reminder down with it.
            null
        }
    }

    private fun key(): SecretKey {
        key?.let { return it }
        // A Keystore failure is not a lost key: report it, never treat it as missing (that would start over).
        val existing = try {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey(alias, null) as? SecretKey
        } catch (e: Exception) {
            throw KeyUnavailable(e)
        }
        existing?.let { return it.also { key = it } }
        val db = writableDatabase
        val rows = db.rawQuery("SELECT COUNT(*) FROM entries", null).use {
            it.moveToFirst()
            it.getInt(0)
        }
        if (rows > 0) {
            // The key is gone (it can't come back), so these rows can never be read again. Keep the ciphertext aside
            // and start over: the other device re-sends its copies on the next sync.
            db.beginTransaction()
            try {
                db.execSQL("INSERT INTO unreadable(id, payload, moved) SELECT id, payload, ? FROM entries", arrayOf(System.currentTimeMillis()))
                db.execSQL("DELETE FROM entries")
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            decoded.clear()
        }
        return try {
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).build())
            }.generateKey().also { key = it }
        } catch (e: Exception) {
            throw KeyUnavailable(e)
        }
    }

    private fun encrypt(id: String, bytes: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(id.toByteArray())
        return cipher.iv + cipher.doFinal(bytes)
    }

    class KeyUnavailable(cause: Throwable) : IllegalStateException("Reminder encryption is unavailable on this device right now. Existing data was retained.", cause)

    companion object {
        const val STALE = "This item changed on another device. Reopen it before saving."
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
