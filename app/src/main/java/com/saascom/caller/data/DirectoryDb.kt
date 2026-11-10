package com.saascom.caller.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** One phone number of the client list, as stored on the phone. */
data class DirectoryEntry(
    val phone: String,
    val leadId: Long,
    val name: String?,
    val company: String?,
    val stage: String,
    val status: String,
    val owner: String?,
)

/**
 * The client list on the phone (GET /directory), for the instant match while it rings.
 * Plain SQLite: one indexed lookup by phone takes a few milliseconds, even right after
 * the process starts, and needs no annotation processing.
 */
class DirectoryDb(context: Context) : SQLiteOpenHelper(context, "directory.db", null, VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE entries (
                phone TEXT PRIMARY KEY NOT NULL,
                lead_id INTEGER NOT NULL,
                name TEXT,
                company TEXT,
                stage TEXT NOT NULL,
                status TEXT NOT NULL,
                owner TEXT
            )""",
        )
        db.execSQL(
            """CREATE TABLE statuses (
                id INTEGER PRIMARY KEY NOT NULL,
                name TEXT NOT NULL,
                stage TEXT NOT NULL,
                position INTEGER NOT NULL
            )""",
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // The list is a cache of the CRM: rebuild it and let the next sync refill it.
        db.execSQL("DROP TABLE IF EXISTS entries")
        db.execSQL("DROP TABLE IF EXISTS statuses")
        onCreate(db)
    }

    fun find(phone: String): DirectoryEntry? =
        readableDatabase.query("entries", null, "phone = ?", arrayOf(phone), null, null, null).use { c ->
            if (c.moveToFirst()) c.toEntry() else null
        }

    private fun android.database.Cursor.toEntry() = DirectoryEntry(
        phone = getString(getColumnIndexOrThrow("phone")),
        leadId = getLong(getColumnIndexOrThrow("lead_id")),
        name = getString(getColumnIndexOrThrow("name")),
        company = getString(getColumnIndexOrThrow("company")),
        stage = getString(getColumnIndexOrThrow("stage")),
        status = getString(getColumnIndexOrThrow("status")),
        owner = getString(getColumnIndexOrThrow("owner")),
    )

    /** Lead statuses in the CRM's order, for the "change status" list. */
    fun statuses(): List<StatusDto> =
        readableDatabase.query("statuses", null, null, null, null, null, "position ASC").use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(StatusDto(c.getLong(c.getColumnIndexOrThrow("id")), c.getString(c.getColumnIndexOrThrow("name")), c.getString(c.getColumnIndexOrThrow("stage"))))
                }
            }
        }

    /**
     * Search by digits of the number, or by name / company. One row per lead (a lead can have
     * two numbers): the first number found is kept.
     */
    fun search(query: String, limit: Int = 50): List<DirectoryEntry> {
        val text = query.trim()
        if (text.isEmpty()) return emptyList()
        val digits = searchDigits(text)
        val (where, args) = if (digits != null) {
            "phone LIKE ?" to arrayOf("%$digits%")
        } else {
            "name LIKE ? OR company LIKE ?" to arrayOf("%$text%", "%$text%")
        }
        return readableDatabase.query("entries", null, where, args, null, null, "name IS NULL, name ASC", (limit * 2).toString()).use { c ->
            buildList {
                while (c.moveToNext()) add(c.toEntry())
            }
        }.distinctBy { it.leadId }.take(limit)
    }

    fun count(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM entries", null).use { c ->
        if (c.moveToFirst()) c.getInt(0) else 0
    }

    /** Swaps the whole list in one transaction, so a lookup never sees a half-written list. */
    fun replaceAll(entries: List<DirectoryEntryDto>, statuses: List<StatusDto>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("entries", null, null)
            db.delete("statuses", null, null)
            val row = ContentValues()
            for (entry in entries) {
                row.clear()
                row.put("phone", entry.phone)
                row.put("lead_id", entry.leadId)
                row.put("name", entry.name)
                row.put("company", entry.company)
                row.put("stage", entry.stage)
                row.put("status", entry.status)
                row.put("owner", entry.owner)
                db.insertWithOnConflict("entries", null, row, SQLiteDatabase.CONFLICT_IGNORE)
            }
            statuses.forEachIndexed { position, status ->
                row.clear()
                row.put("id", status.id)
                row.put("name", status.name)
                row.put("stage", status.stage)
                row.put("position", position)
                db.insertWithOnConflict("statuses", null, row, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun clear() {
        writableDatabase.apply {
            delete("entries", null, null)
            delete("statuses", null, null)
        }
    }

    private companion object {
        const val VERSION = 1
    }
}

/**
 * When the search text is a phone number (3+ digits, at most one other character such as "+"),
 * the digits to look for; null for a name search. "+213 550…" / "00213 550…" become "0550…",
 * the format of the local list.
 */
internal fun searchDigits(text: String): String? {
    val digits = text.filter(Char::isDigit)
    val isNumber = digits.length >= 3 && digits.length >= text.count { !it.isWhitespace() } - 1
    return if (isNumber) digits.replaceFirst(Regex("^(00)?213(?=\\d{8})"), "0") else null
}
