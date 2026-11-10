package com.saascom.caller.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** A call the app reacted to (client number, or unknown number looked up in the CRM). */
data class CallRecord(
    val id: Long,
    val phone: String,
    val leadId: Long?,
    val name: String?,
    /** Epoch millis when it started ringing. */
    val at: Long,
    val answered: Boolean,
    val durationSeconds: Int,
    /** A call log was saved in the CRM for it (or is waiting in the outbox). */
    val logged: Boolean,
)

/** A write that could not reach the CRM yet (no internet), replayed later in order. */
data class PendingAction(
    val id: Long,
    val kind: String,
    /** Relative to /api/mobile/, e.g. "leads/12/calls". */
    val path: String,
    val body: String,
    /** What the user sees in the list, e.g. "مكالمة · محمد بن علي". */
    val label: String,
    val callId: Long?,
    val createdAt: Long,
    val attempts: Int,
    /** Set when the CRM refused it (4xx): it is no longer retried and waits for the user. */
    val lastError: String?,
)

/**
 * App data that must survive (unlike the client list, which is a cache that can be rebuilt):
 * the history of calls the app saw, and the outbox of unsent writes.
 * It never reads the phone's own call log.
 */
class LocalDb(context: Context) : SQLiteOpenHelper(context, "local.db", null, VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE calls (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                phone TEXT NOT NULL,
                lead_id INTEGER,
                name TEXT,
                at INTEGER NOT NULL,
                answered INTEGER NOT NULL DEFAULT 0,
                duration INTEGER NOT NULL DEFAULT 0,
                logged INTEGER NOT NULL DEFAULT 0
            )""",
        )
        db.execSQL("CREATE INDEX calls_at ON calls(at)")
        db.execSQL(
            """CREATE TABLE outbox (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                kind TEXT NOT NULL,
                path TEXT NOT NULL,
                body TEXT NOT NULL,
                label TEXT NOT NULL,
                call_id INTEGER,
                created_at INTEGER NOT NULL,
                attempts INTEGER NOT NULL DEFAULT 0,
                last_error TEXT
            )""",
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Version 1 only. Future versions must migrate with ALTER TABLE: this data is not a cache.
    }

    // --- calls ------------------------------------------------------------------------------

    fun insertCall(phone: String, leadId: Long?, name: String?, at: Long): Long =
        writableDatabase.insertOrThrow(
            "calls",
            null,
            ContentValues().apply {
                put("phone", phone)
                put("lead_id", leadId)
                put("name", name)
                put("at", at)
            },
        )

    /** Fills in what the CRM said during the ring (the number may be unknown locally). */
    fun updateCallLead(id: Long, leadId: Long?, name: String?) {
        writableDatabase.update(
            "calls",
            ContentValues().apply {
                put("lead_id", leadId)
                put("name", name)
            },
            "id = ?",
            arrayOf(id.toString()),
        )
    }

    fun finishCall(id: Long, answered: Boolean, durationSeconds: Int) {
        writableDatabase.update(
            "calls",
            ContentValues().apply {
                put("answered", if (answered) 1 else 0)
                put("duration", durationSeconds)
            },
            "id = ?",
            arrayOf(id.toString()),
        )
    }

    fun markLogged(id: Long) = setLogged(id, true)

    /** Back to "not logged": the CRM refused the queued call log, so the agent must log it again. */
    fun markUnlogged(id: Long) = setLogged(id, false)

    private fun setLogged(id: Long, logged: Boolean) {
        writableDatabase.update("calls", ContentValues().apply { put("logged", if (logged) 1 else 0) }, "id = ?", arrayOf(id.toString()))
    }

    fun call(id: Long): CallRecord? =
        readableDatabase.query("calls", null, "id = ?", arrayOf(id.toString()), null, null, null).use { c ->
            if (c.moveToFirst()) c.toCall() else null
        }

    fun recentCalls(limit: Int = 200): List<CallRecord> =
        readableDatabase.query("calls", null, null, null, null, null, "at DESC", limit.toString()).use { c ->
            buildList { while (c.moveToNext()) add(c.toCall()) }
        }

    fun deleteCallsBefore(epochMillis: Long) {
        writableDatabase.delete("calls", "at < ?", arrayOf(epochMillis.toString()))
    }

    // --- outbox -----------------------------------------------------------------------------

    fun enqueue(kind: String, path: String, body: String, label: String, callId: Long?, now: Long): Long =
        writableDatabase.insertOrThrow(
            "outbox",
            null,
            ContentValues().apply {
                put("kind", kind)
                put("path", path)
                put("body", body)
                put("label", label)
                put("call_id", callId)
                put("created_at", now)
            },
        )

    /** Oldest first, so writes reach the CRM in the order they were made. */
    fun outbox(): List<PendingAction> =
        readableDatabase.query("outbox", null, null, null, null, null, "id ASC").use { c ->
            buildList { while (c.moveToNext()) add(c.toPending()) }
        }

    fun deleteAction(id: Long) {
        writableDatabase.delete("outbox", "id = ?", arrayOf(id.toString()))
    }

    fun failAction(id: Long, error: String, attempts: Int) {
        writableDatabase.update(
            "outbox",
            ContentValues().apply {
                put("last_error", error)
                put("attempts", attempts)
            },
            "id = ?",
            arrayOf(id.toString()),
        )
    }

    fun countAttempt(id: Long, attempts: Int) {
        writableDatabase.update("outbox", ContentValues().apply { put("attempts", attempts) }, "id = ?", arrayOf(id.toString()))
    }

    /** Logout: calls and unsent writes belong to this user and hold client data. */
    fun clear() {
        writableDatabase.apply {
            delete("calls", null, null)
            delete("outbox", null, null)
        }
    }

    private fun Cursor.toCall() = CallRecord(
        id = getLong(getColumnIndexOrThrow("id")),
        phone = getString(getColumnIndexOrThrow("phone")),
        leadId = getColumnIndexOrThrow("lead_id").let { if (isNull(it)) null else getLong(it) },
        name = getString(getColumnIndexOrThrow("name")),
        at = getLong(getColumnIndexOrThrow("at")),
        answered = getInt(getColumnIndexOrThrow("answered")) == 1,
        durationSeconds = getInt(getColumnIndexOrThrow("duration")),
        logged = getInt(getColumnIndexOrThrow("logged")) == 1,
    )

    private fun Cursor.toPending() = PendingAction(
        id = getLong(getColumnIndexOrThrow("id")),
        kind = getString(getColumnIndexOrThrow("kind")),
        path = getString(getColumnIndexOrThrow("path")),
        body = getString(getColumnIndexOrThrow("body")),
        label = getString(getColumnIndexOrThrow("label")),
        callId = getColumnIndexOrThrow("call_id").let { if (isNull(it)) null else getLong(it) },
        createdAt = getLong(getColumnIndexOrThrow("created_at")),
        attempts = getInt(getColumnIndexOrThrow("attempts")),
        lastError = getString(getColumnIndexOrThrow("last_error")),
    )

    private companion object {
        const val VERSION = 1
    }
}
