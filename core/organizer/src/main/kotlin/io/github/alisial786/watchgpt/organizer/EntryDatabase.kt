package io.github.alisial786.watchgpt.organizer

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import io.github.alisial786.watchgpt.reminders.Entry
import io.github.alisial786.watchgpt.reminders.Merge
import kotlinx.serialization.json.Json
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** App-private SQLite index; titles, notes, dates and record contents are authenticated ciphertext. No backup. */
internal class EntryDatabase(context: Context) : SQLiteOpenHelper(context, File(context.noBackupFilesDir, "organizer.db").absolutePath, null, 1) {
    private val json = Json { ignoreUnknownKeys = true }
    private val alias = "${context.packageName}.organizer.v1"
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE entries(id TEXT PRIMARY KEY, revision INTEGER NOT NULL, payload BLOB NOT NULL)")
        db.execSQL("CREATE TABLE meta(name TEXT PRIMARY KEY, value TEXT NOT NULL)")
        db.execSQL("CREATE TABLE delivered(token TEXT PRIMARY KEY)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

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
    val device: String get() = meta("device") ?: UUID.randomUUID().toString().also { meta("device", it) }

    @Synchronized fun all(): List<Entry> = readableDatabase.query("entries", arrayOf("id", "payload"), null, null, null, null, null).use { c ->
        buildList { while (c.moveToNext()) add(decode(c.getString(0), c.getBlob(1))) }
    }

    @Synchronized fun get(id: String): Entry? = readableDatabase.query("entries", arrayOf("payload"), "id=?", arrayOf(id), null, null, null).use { if (it.moveToFirst()) decode(id, it.getBlob(0)) else null }

    @Synchronized fun save(entry: Entry): Entry {
        entry.validate()
        val db = writableDatabase
        db.beginTransaction()
        try {
            val old = get(entry.id)
            require(old?.deleted != true) { "This item was deleted on another device. Create a new item instead." }
            require(old == null || (old.revision == entry.revision && old.author == entry.author)) { "This item changed on another device. Reopen it before saving." }
            val revision = maxOf(meta("clock")?.toLongOrNull() ?: 0, all().maxOfOrNull { it.revision } ?: 0) + 1
            val saved = entry.copy(revision = revision, author = device)
            write(saved)
            meta("clock", revision.toString())
            db.setTransactionSuccessful()
            return saved
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized fun merge(entry: Entry): Boolean {
        val old = get(entry.id)
        val next = Merge.winner(old, entry)
        if (next == old) return false
        write(next)
        meta("clock", maxOf(meta("clock")?.toLongOrNull() ?: 0, next.revision).toString())
        return true
    }

    @Synchronized fun wasDelivered(token: String): Boolean = readableDatabase.rawQuery("SELECT 1 FROM delivered WHERE token=?", arrayOf(token)).use { it.moveToFirst() }

    @Synchronized fun delivered(token: String) {
        writableDatabase.execSQL("INSERT OR IGNORE INTO delivered(token) VALUES(?)", arrayOf(token))
    }
    private fun write(entry: Entry) {
        writableDatabase.insertWithOnConflict(
            "entries",
            null,
            ContentValues().apply {
                put("id", entry.id)
                put("revision", entry.revision)
                put("payload", encrypt(entry.id, json.encodeToString(Entry.serializer(), entry).toByteArray()))
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        // Never silently replace a missing key when records already exist.
        check(
            readableDatabase.rawQuery("SELECT COUNT(*) FROM entries", null).use {
                it.moveToFirst()
                it.getInt(0)
            } == 0,
        ) { "Reminder encryption key unavailable. Existing data was retained." }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }
    private fun encrypt(id: String, bytes: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(id.toByteArray())
        return cipher.iv + cipher.doFinal(bytes)
    }
    private fun decode(id: String, bytes: ByteArray): Entry {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        cipher.updateAAD(id.toByteArray())
        return json.decodeFromString<Entry>(cipher.doFinal(bytes.copyOfRange(12, bytes.size)).decodeToString()).validate()
    }
}
