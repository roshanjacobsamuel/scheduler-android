package com.rosh.wascheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.text.DateFormat

/** Lists everything still scheduled, with a Cancel button per entry. */
class PendingActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pending)
        renderList()
    }

    private fun renderList() {
        val container = findViewById<LinearLayout>(R.id.pendingListContainer)
        container.removeAllViews()

        val entries = ScheduleStore(this).listAll()

        if (entries.isEmpty()) {
            container.addView(TextView(this).apply { text = "Nothing scheduled." })
            return
        }

        for (entry in entries) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 16, 0, 16)
            }

            val kind = if (entry.recipientType == "group") "Group" else "Individual"
            val label = TextView(this).apply {
                text = "$kind: ${entry.recipient}\n" +
                    "At: ${DateFormat.getDateTimeInstance().format(entry.sendAtMillis)}\n" +
                    "\"${entry.message}\""
            }

            val cancelButton = Button(this).apply {
                text = "Cancel"
                setOnClickListener {
                    cancelEntry(entry.id)
                    renderList()
                }
            }

            row.addView(label)
            row.addView(cancelButton)
            container.addView(row)
        }
    }

    private fun cancelEntry(id: Long) {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, AlarmReceiver::class.java).apply {
            putExtra("scheduleId", id)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            this, id.toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
        pendingIntent.cancel()

        ScheduleStore(this).delete(id)
    }
}
