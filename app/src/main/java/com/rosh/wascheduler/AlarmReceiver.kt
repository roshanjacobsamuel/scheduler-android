package com.rosh.wascheduler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.net.URLEncoder

/**
 * Fires when the scheduled time arrives.
 *
 * Individual chats: opens WhatsApp's chat screen with the message pre-filled,
 * using WhatsApp's own documented "click to chat" deep link
 * (https://wa.me/<number>?text=<text>) — that part is 100% official Meta
 * behavior, not automation. WhatsAppAccessibilityService then just clicks Send.
 *
 * Groups: there is no deep link for a specific group (groups don't have phone
 * numbers), so this just brings WhatsApp's chat list to the front and hands
 * WhatsAppAccessibilityService a "job" describing the group name and the
 * message to type — it drives the search-and-type flow itself.
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra("scheduleId", -1)
        if (id == -1L) return

        val store = ScheduleStore(context)
        val entry = store.get(id) ?: return
        store.delete(id)

        if (entry.recipientType == "group") {
            sendToGroup(context, entry)
        } else {
            sendToIndividual(context, entry)
        }
    }

    private fun sendToIndividual(context: Context, entry: ScheduledMessage) {
        // Real @mentions are a group-chat concept in WhatsApp, so in a 1:1
        // chat we just flatten "@[Name]" down to plain "@Name" text.
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
        val segments = parseSegments(entry.message)
        WhatsAppAccessibilityService.pendingGroupJob =
            WhatsAppAccessibilityService.GroupJob(entry.recipient, segments)

        val launchIntent = context.packageManager.getLaunchIntentForPackage("com.whatsapp") ?: return
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        context.startActivity(launchIntent)
    }

    /** Splits "Hi @[Priya Sharma], meeting at 5" into text/mention segments. */
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
