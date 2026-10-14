package io.github.alisial786.watchgpt.organizer

import androidx.test.platform.app.InstrumentationRegistry
import io.github.alisial786.watchgpt.reminders.Entry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Runs against Android SQLite and AndroidKeyStore, not substitutes for either. Synthetic fixtures only. */
class StorageTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun offlineSaveSurvivesProcessDatabaseReopenAndIsEncrypted() {
        val first = EntryDatabase(context)
        val saved = first.save(Entry(title = "SYNTHETIC_PRIVATE_TITLE_782", notes = "Synthetic note", localTime = "2027-01-02T09:00"))
        first.readableDatabase.rawQuery("SELECT payload FROM entries WHERE id=?", arrayOf(saved.id)).use {
            assertTrue(it.moveToFirst())
            assertFalse(it.getBlob(0).decodeToString().contains(saved.title))
        }
        first.close()
        val reopened = EntryDatabase(context)
        assertEquals(saved, reopened.get(saved.id))
        reopened.close()
    }

    @Test fun duplicateReconnectAndTombstoneProtectAgainstResurrection() {
        val db = EntryDatabase(context)
        val saved = db.save(Entry(title = "Synthetic sync test", localTime = "2027-01-02T09:00"))
        assertFalse(db.merge(saved))
        val removed = db.save(saved.copy(deleted = true))
        assertFalse(db.merge(saved.copy(revision = removed.revision + 100, title = "Stale offline edit")))
        assertTrue(db.get(saved.id)!!.deleted)
        db.close()
    }

    @Test fun concurrentEditorCannotOverwriteUnseenChanges() {
        val db = EntryDatabase(context)
        val saved = db.save(Entry(title = "Synthetic conflict", localTime = "2027-01-02T09:00"))
        db.save(saved.copy(title = "New version"))
        assertThrows(ItemChangedException::class.java) { db.save(saved.copy(title = "Stale editor")) }
        assertEquals("New version", db.get(saved.id)!!.title)
        db.close()
    }

    @Test fun deliveryReceiptSurvivesRebootDatabaseReopen() {
        val token = "synthetic-${java.util.UUID.randomUUID()}"
        EntryDatabase(context).use { it.delivered(token) }
        EntryDatabase(context).use { assertTrue(it.wasDelivered(token)) }
    }

    @Test fun oneCorruptRowDoesNotHideTheOthers() {
        val db = EntryDatabase(context)
        val good = db.save(Entry(title = "Synthetic readable", localTime = "2027-01-02T09:00"))
        val bad = java.util.UUID.randomUUID().toString()
        db.writableDatabase.execSQL("INSERT INTO entries(id, revision, payload) VALUES(?, 1, ?)", arrayOf<Any>(bad, ByteArray(40) { 7 }))
        try {
            assertTrue(db.all().any { it.id == good.id })
            assertTrue(db.unreadable >= 1)
            assertEquals(null, db.get(bad))
        } finally {
            db.writableDatabase.execSQL("DELETE FROM entries WHERE id=?", arrayOf(bad))
            db.close()
        }
    }

    @Test fun deletionKeepsNoContent() {
        val db = EntryDatabase(context)
        val saved = db.save(Entry(title = "SYNTHETIC_PRIVATE_TITLE_901", notes = "Synthetic note", localTime = "2027-01-02T09:00"))
        val removed = db.save(saved.tombstone())
        assertTrue(removed.deleted)
        assertEquals(Entry.DELETED_TITLE, db.get(saved.id)!!.title)
        assertEquals("", db.get(saved.id)!!.notes)
        db.close()
    }
}
