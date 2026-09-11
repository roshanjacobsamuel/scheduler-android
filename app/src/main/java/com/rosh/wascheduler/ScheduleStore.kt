package com.rosh.wascheduler

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class ScheduledMessage(
    val id: Long,
    val recipientType: String, // "individual" or "group"
    val recipient: String,     // phone number, or the group's exact name as it appears in WhatsApp
    val message: String,
    val sendAtMillis: Long
)

/**
 * Local-only store for scheduled messages. Nothing here ever leaves the
 * device — it's just a table Android's AlarmManager callback reads from when
 * an alarm fires, so the message text survives a reboot / process death
 * between "you tapped Schedule" and "the alarm actually goes off".
 */
class ScheduleStore(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "wascheduler.db", null, 2) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE scheduled (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                recipientType TEXT NOT NULL,
                recipient TEXT NOT NULL,
                message TEXT NOT NULL,
                sendAtMillis INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // This is a personal single-device app with no real migration need —
        // an upgrade just clears any old pending rows rather than carrying
        // forward the old one-column (phone-only) schema.
        db.execSQL("DROP TABLE IF EXISTS scheduled")
        onCreate(db)
    }

    fun insert(recipientType: String, recipient: String, message: String, sendAtMillis: Long): Long {
        val values = ContentValues().apply {
            put("recipientType", recipientType)
            put("recipient", recipient)
            put("message", message)
            put("sendAtMillis", sendAtMillis)
        }
        return writableDatabase.insert("scheduled", null, values)
    }

    fun get(id: Long): ScheduledMessage? {
        readableDatabase.query(
            "scheduled", arrayOf("id", "recipientType", "recipient", "message", "sendAtMillis"),
            "id = ?", arrayOf(id.toString()), null, null, null
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                return ScheduledMessage(
                    id = cursor.getLong(0),
                    recipientType = cursor.getString(1),
                    recipient = cursor.getString(2),
                    message = cursor.getString(3),
                    sendAtMillis = cursor.getLong(4)
                )
            }
        }
        return null
    }

    fun listAll(): List<ScheduledMessage> {
        val results = mutableListOf<ScheduledMessage>()
        readableDatabase.query(
            "scheduled", arrayOf("id", "recipientType", "recipient", "message", "sendAtMillis"),
            null, null, null, null, "sendAtMillis ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                results.add(
                    ScheduledMessage(
                        id = cursor.getLong(0),
                        recipientType = cursor.getString(1),
                        recipient = cursor.getString(2),
                        message = cursor.getString(3),
                        sendAtMillis = cursor.getLong(4)
                    )
                )
            }
        }
        return results
    }

    fun delete(id: Long) {
        writableDatabase.delete("scheduled", "id = ?", arrayOf(id.toString()))
    }
}
