package com.rosh.wascheduler

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import java.net.URLEncoder

/**
 * Fires when the scheduled time arrives.
 *
 * If the phone is locked and a PIN is saved in Settings, this wakes the screen
 * and hands the PIN + job to WhatsAppAccessibilityService, which taps the lock
 * screen digits and then launches WhatsApp itself once unlocked.
 *
 * If the phone is already unlocked (or no PIN is saved), it launches WhatsApp
 * directly as before.
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra("scheduleId", -1)
        if (id == -1L) return

        val store = ScheduleStore(context)
        val entry = store.get(id) ?: return
        store.delete(id)

        val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val pin = PinStore(context).getPin()

        if (keyguardManager.isKeyguardLocked && pin != null) {
            wakeScreen(context)
            WhatsAppAccessibilityService.pendingPinUnlock = pin
            if (entry.recipientType == "group") {
                WhatsAppAccessibilityService.pendingGroupJob = buildGroupJob(entry)
            } else {
                WhatsAppAccessibilityService.pendingIndividualEntry = entry
            }
            // Don't launch WhatsApp yet — service handles it after unlock.
            return
        }

        if (entry.recipientType == "group") {
            sendToGroup(context, entry)
        } else {
            sendToIndividual(context, entry)
        }
    }

    @Suppress("DEPRECATION")
    private fun wakeScreen(context: Context) {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "RoshSchedule:wakeForUnlock"
        )
        wakeLock.acquire(5 * 60 * 1000L)
        WhatsAppAccessibilityService.screenWakeLock = wakeLock
    }

    private fun sendToIndividual(context: Context, entry: ScheduledMessage) {
        val plainMessage = entry.message.replace(Regex("""@\[([^]]+)]"""), "@$1")
        val encodedMessage = URLEncoder.encode(plainMessage, "UTF-8")
        val uri = Uri.parse("https://wa.me/${entry.recipient}?text=$encodedMessage")

        val waIntent = Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage("com.whatsapp")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(waIntent)
    }

    private fun sendToGroup(context: Context, entry: ScheduledMessage) {
        WhatsAppAccessibilityService.pendingGroupJob = buildGroupJob(entry)

        val launchIntent = context.packageManager.getLaunchIntentForPackage("com.whatsapp") ?: return
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        context.startActivity(launchIntent)
    }

    private fun buildGroupJob(entry: ScheduledMessage): WhatsAppAccessibilityService.GroupJob =
        WhatsAppAccessibilityService.GroupJob(entry.recipient, parseSegments(entry.message))

    private fun parseSegments(message: String): List<WhatsAppAccessibilityService.Segment> {
        val segments = mutableListOf<WhatsAppAccessibilityService.Segment>()
        val regex = Regex("""@\[([^]]+)]""")
        var lastIndex = 0
        for (match in regex.findAll(message)) {
            if (match.range.first > lastIndex) {
                segments.add(WhatsAppAccessibilityService.Segment.Text(message.substring(lastIndex, match.range.first)))
            }
            segments.add(WhatsAppAccessibilityService.Segment.Mention(match.groupValues[1]))
            lastIndex = match.range.last + 1
        }
        if (lastIndex < message.length) {
            segments.add(WhatsAppAccessibilityService.Segment.Text(message.substring(lastIndex)))
        }
        return segments
    }
}
