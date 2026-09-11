package com.rosh.wascheduler

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

private const val PREFS_FILE = "pin_prefs"
private const val KEY_PIN = "unlock_pin"

class PinStore(context: Context) {

    private val prefs = EncryptedSharedPreferences.create(
        context.applicationContext,
        PREFS_FILE,
        MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun savePin(pin: String) = prefs.edit().putString(KEY_PIN, pin).apply()

    fun getPin(): String? = prefs.getString(KEY_PIN, null)

    fun clearPin() = prefs.edit().remove(KEY_PIN).apply()
}
