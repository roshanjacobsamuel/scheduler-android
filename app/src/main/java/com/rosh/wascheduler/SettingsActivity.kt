package com.rosh.wascheduler

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val pinStore = PinStore(this)
        val pinInput = findViewById<EditText>(R.id.pinInput)

        pinStore.getPin()?.let { pinInput.setText(it) }

        findViewById<Button>(R.id.savePinButton).setOnClickListener {
            val pin = pinInput.text.toString().trim()
            if (pin.isEmpty()) {
                Toast.makeText(this, "Enter a PIN", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (pin.any { !it.isDigit() }) {
                Toast.makeText(this, "PIN must be digits only", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            pinStore.savePin(pin)
            Toast.makeText(this, "PIN saved", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.clearPinButton).setOnClickListener {
            pinStore.clearPin()
            pinInput.setText("")
            Toast.makeText(this, "PIN cleared — scheduled sends will wait for manual unlock", Toast.LENGTH_SHORT).show()
        }
    }
}
