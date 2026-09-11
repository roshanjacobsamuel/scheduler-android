package com.rosh.wascheduler

import android.app.AlarmManager
import android.app.DatePickerDialog
import android.app.PendingIntent
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.util.Calendar

class MainActivity : AppCompatActivity() {

    private var chosenCalendar: Calendar? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val recipientTypeGroup = findViewById<RadioGroup>(R.id.recipientTypeGroup)
        val phoneLabel = findViewById<TextView>(R.id.phoneLabel)
        val phoneInput = findViewById<EditText>(R.id.phoneInput)
        val groupLabel = findViewById<TextView>(R.id.groupLabel)
        val groupNameInput = findViewById<EditText>(R.id.groupNameInput)
        val messageInput = findViewById<EditText>(R.id.messageInput)
        val chosenTimeLabel = findViewById<TextView>(R.id.chosenTimeLabel)
        val statusLabel = findViewById<TextView>(R.id.statusLabel)

        recipientTypeGroup.setOnCheckedChangeListener { _, checkedId ->
            val isGroup = checkedId == R.id.groupRadio
            phoneLabel.visibility = if (isGroup) View.GONE else View.VISIBLE
            phoneInput.visibility = if (isGroup) View.GONE else View.VISIBLE
            groupLabel.visibility = if (isGroup) View.VISIBLE else View.GONE
            groupNameInput.visibility = if (isGroup) View.VISIBLE else View.GONE
        }

        findViewById<Button>(R.id.pickTimeButton).setOnClickListener {
            val now = Calendar.getInstance()
            DatePickerDialog(this, { _, year, month, day ->
                TimePickerDialog(this, { _, hour, minute ->
                    val cal = Calendar.getInstance()
                    cal.set(year, month, day, hour, minute, 0)
                    chosenCalendar = cal
                    chosenTimeLabel.text = cal.time.toString()
                }, now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), false).show()
            }, now.get(Calendar.YEAR), now.get(Calendar.MONTH), now.get(Calendar.DAY_OF_MONTH)).show()
        }

        findViewById<Button>(R.id.scheduleButton).setOnClickListener {
            val isGroup = recipientTypeGroup.checkedRadioButtonId == R.id.groupRadio
            val recipient = if (isGroup) groupNameInput.text.toString().trim()
            else phoneInput.text.toString().trim()
            val message = messageInput.text.toString().trim()
            val cal = chosenCalendar

            if (recipient.isEmpty() || message.isEmpty() || cal == null) {
                Toast.makeText(this, "Fill in the recipient, message, and pick a time", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (cal.timeInMillis <= System.currentTimeMillis()) {
                Toast.makeText(this, "Pick a time in the future", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                // Android makes you approve exact alarms explicitly on newer versions.
                startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))
                Toast.makeText(this, "Grant exact-alarm permission, then tap Schedule again", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            val store = ScheduleStore(this)
            val recipientType = if (isGroup) "group" else "individual"
            val id = store.insert(recipientType, recipient, message, cal.timeInMillis)

            val intent = Intent(this, AlarmReceiver::class.java).apply {
                putExtra("scheduleId", id)
            }
            val pendingIntent = PendingIntent.getBroadcast(
                this, id.toInt(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP, cal.timeInMillis, pendingIntent
            )

            statusLabel.text = "Scheduled for ${cal.time}"
        }

        findViewById<Button>(R.id.viewPendingButton).setOnClickListener {
            startActivity(Intent(this, PendingActivity::class.java))
        }

        findViewById<Button>(R.id.settingsButton).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }
}
