package com.rosh.wascheduler

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import java.net.URLEncoder

/**
 * Watches all windows (packageNames filter removed from accessibility_service_config.xml
 * so it can see the lock screen) but only acts on two things:
 *
 *  1. Lock screen — if AlarmReceiver set pendingPinUnlock (phone was locked when the
 *     alarm fired), this finds the digit buttons on the keypad and taps them in order,
 *     then launches WhatsApp once the phone reports as unlocked.
 *
 *  2. WhatsApp windows (com.whatsapp) — the existing individual/group send logic.
 *     Individual: taps the Send button after AlarmReceiver opened the wa.me deep link.
 *     Group: drives search → open group → type message (resolving @mentions through
 *     WhatsApp's own suggestion popup) → tap Send.
 *
 * If a lock-screen stage fails (wrong PIN, keypad layout not recognised), it shows a
 * Toast, clears all pending state, and gives up rather than retrying forever.
 */
class WhatsAppAccessibilityService : AccessibilityService() {

    sealed class Segment {
        data class Text(val text: String) : Segment()
        data class Mention(val name: String) : Segment()
    }

    data class GroupJob(val groupName: String, val segments: List<Segment>)

    companion object {
        @Volatile var pendingGroupJob: GroupJob? = null
        @Volatile var pendingIndividualEntry: ScheduledMessage? = null
        @Volatile var pendingPinUnlock: String? = null
        @Volatile var screenWakeLock: PowerManager.WakeLock? = null
    }

    private val handler = Handler(Looper.getMainLooper())
    private var individualHandled = false
    private var pinUnlockInProgress = false

    private enum class Stage { IDLE, RUNNING }
    private var stage = Stage.IDLE
    private var segmentIndex = 0
    private var textSoFar = ""

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) return

        if (pendingPinUnlock != null) {
            handlePossibleLockScreen()
            return
        }

        if (event.packageName != "com.whatsapp") return

        val job = pendingGroupJob
        if (job == null) {
            if (!individualHandled) {
                handler.removeCallbacksAndMessages(null)
                handler.postDelayed({ tryClickSendForIndividual() }, 1200)
            }
            return
        }

        if (stage == Stage.IDLE) {
            stage = Stage.RUNNING
            handler.postDelayed({ openSearch(job) }, 1000)
        }
    }

    // ---------- lock screen unlock ----------

    private fun handlePossibleLockScreen() {
        val keyguardManager = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        if (keyguardManager.isKeyguardLocked) {
            if (!pinUnlockInProgress) {
                pinUnlockInProgress = true
                handler.postDelayed({ startPinEntry() }, 600)
            }
        } else {
            // Phone just unlocked — proceed to WhatsApp
            pendingPinUnlock = null
            pinUnlockInProgress = false
            screenWakeLock?.release()
            screenWakeLock = null
            handler.postDelayed({ launchWhatsAppAfterUnlock() }, 800)
        }
    }

    private fun startPinEntry() {
        val pin = pendingPinUnlock ?: return
        var delay = 0L
        for (digit in pin) {
            val d = digit
            handler.postDelayed({ tapPinDigit(d) }, delay)
            delay += 400
        }
        // Check unlock result after all digits have been tapped + 1.2s buffer
        handler.postDelayed({ checkUnlockResult() }, delay + 1200)
    }

    private fun tapPinDigit(digit: Char) {
        val root = rootInActiveWindow ?: return
        // Lock screen digit buttons have their digit as visible text on all stock
        // Android versions and most OEM skins (Samsung, OnePlus, Pixel, etc.)
        val node = findClickableByText(root, digit.toString()) ?: return
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun checkUnlockResult() {
        val keyguardManager = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        if (!keyguardManager.isKeyguardLocked) {
            pendingPinUnlock = null
            pinUnlockInProgress = false
            screenWakeLock?.release()
            screenWakeLock = null
            launchWhatsAppAfterUnlock()
        } else {
            Toast.makeText(
                this,
                "Rosh Schedule: PIN unlock failed — check the PIN in Settings",
                Toast.LENGTH_LONG
            ).show()
            pendingPinUnlock = null
            pinUnlockInProgress = false
            pendingGroupJob = null
            pendingIndividualEntry = null
            screenWakeLock?.release()
            screenWakeLock = null
        }
    }

    private fun launchWhatsAppAfterUnlock() {
        val individualEntry = pendingIndividualEntry
        if (individualEntry != null) {
            pendingIndividualEntry = null
            individualHandled = false
            val plainMessage = individualEntry.message.replace(Regex("""@\[([^]]+)]"""), "@$1")
            val encodedMessage = URLEncoder.encode(plainMessage, "UTF-8")
            val uri = Uri.parse("https://wa.me/${individualEntry.recipient}?text=$encodedMessage")
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                setPackage("com.whatsapp")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } else if (pendingGroupJob != null) {
            val launchIntent = packageManager.getLaunchIntentForPackage("com.whatsapp") ?: return
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            startActivity(launchIntent)
        }
    }

    // ---------- individual: simple case ----------

    private fun tryClickSendForIndividual() {
        if (individualHandled) return
        val root = rootInActiveWindow ?: return
        val sendButton = findByViewId(root, "com.whatsapp:id/send") ?: findClickableByText(root, "Send")
        if (sendButton != null && sendButton.isClickable) {
            sendButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            individualHandled = true
        }
    }

    // ---------- group: search -> open -> type (with mentions) -> send ----------

    private fun openSearch(job: GroupJob) {
        pollFor(
            find = {
                findByViewId(it, "com.whatsapp:id/menuitem_search")
                    ?: findByViewId(it, "com.whatsapp:id/search")
                    ?: findClickableByDescription(it, "Search")
            },
            onFound = {
                it.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                handler.postDelayed({ typeSearchQuery(job) }, 700)
            },
            onTimeout = { giveUp("couldn't find WhatsApp's search button (its id may have changed — see README)") }
        )
    }

    private fun typeSearchQuery(job: GroupJob) {
        pollFor(
            find = { findEditable(it) },
            onFound = {
                setNodeText(it, job.groupName)
                handler.postDelayed({ openGroupChat(job) }, 1200)
            },
            onTimeout = { giveUp("couldn't find the search input box") }
        )
    }

    private fun openGroupChat(job: GroupJob) {
        pollFor(
            find = { findClickableByText(it, job.groupName) },
            onFound = {
                it.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                segmentIndex = 0
                textSoFar = ""
                handler.postDelayed({ typeNextSegment(job) }, 1200)
            },
            onTimeout = { giveUp("no search result matched \"${job.groupName}\" exactly — check the name matches your chat list exactly") },
            timeoutMs = 8000
        )
    }

    private fun typeNextSegment(job: GroupJob) {
        if (segmentIndex >= job.segments.size) {
            handler.postDelayed({ clickSendForGroup() }, 500)
            return
        }

        pollFor(
            find = { findByViewId(it, "com.whatsapp:id/entry") ?: findEditable(it) },
            onFound = { field ->
                when (val segment = job.segments[segmentIndex]) {
                    is Segment.Text -> {
                        textSoFar += segment.text
                        setNodeText(field, textSoFar)
                        segmentIndex++
                        handler.postDelayed({ typeNextSegment(job) }, 400)
                    }
                    is Segment.Mention -> {
                        textSoFar += "@" + segment.name
                        setNodeText(field, textSoFar)
                        // Give WhatsApp's mention-suggestion popup a moment to
                        // appear, then tap the matching contact so this
                        // becomes a real mention, not literal "@Name" text.
                        handler.postDelayed({
                            pollFor(
                                find = { findClickableByText(it, segment.name) },
                                onFound = {
                                    it.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                                    textSoFar += " "
                                    segmentIndex++
                                    handler.postDelayed({ typeNextSegment(job) }, 400)
                                },
                                onTimeout = { giveUp("no mention suggestion matched \"${segment.name}\" — they may not be a member of this group, or the name doesn't match exactly") },
                                timeoutMs = 5000
                            )
                        }, 700)
                    }
                }
            },
            onTimeout = { giveUp("couldn't find the message box in the group chat") }
        )
    }

    private fun clickSendForGroup() {
        pollFor(
            find = { findByViewId(it, "com.whatsapp:id/send") ?: findClickableByText(it, "Send") },
            onFound = {
                it.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                cleanup()
            },
            onTimeout = { giveUp("typed the message but couldn't find Send") }
        )
    }

    private fun giveUp(reason: String) {
        Toast.makeText(this, "Rosh Schedule: group send failed — $reason", Toast.LENGTH_LONG).show()
        cleanup()
    }

    private fun cleanup() {
        pendingGroupJob = null
        stage = Stage.IDLE
        segmentIndex = 0
        textSoFar = ""
    }

    // ---------- node-finding helpers ----------

    private fun pollFor(
        find: (AccessibilityNodeInfo) -> AccessibilityNodeInfo?,
        onFound: (AccessibilityNodeInfo) -> Unit,
        onTimeout: () -> Unit,
        timeoutMs: Long = 6000,
        intervalMs: Long = 300,
        elapsed: Long = 0
    ) {
        val root = rootInActiveWindow
        val found = root?.let(find)
        if (found != null) {
            onFound(found)
            return
        }
        if (elapsed >= timeoutMs) {
            onTimeout()
            return
        }
        handler.postDelayed(
            { pollFor(find, onFound, onTimeout, timeoutMs, intervalMs, elapsed + intervalMs) },
            intervalMs
        )
    }

    private fun setNodeText(node: AccessibilityNodeInfo, text: String) {
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun findByViewId(root: AccessibilityNodeInfo, viewId: String): AccessibilityNodeInfo? =
        root.findAccessibilityNodeInfosByViewId(viewId).firstOrNull()

    private fun findClickableByText(root: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        val matches = root.findAccessibilityNodeInfosByText(text)
        return matches.firstOrNull { it.isClickable }
            ?: matches.firstOrNull { it.parent?.isClickable == true }?.parent
    }

    private fun findClickableByDescription(root: AccessibilityNodeInfo, description: String): AccessibilityNodeInfo? =
        findClickableByText(root, description)
            ?: walk(root) { node ->
                node.isClickable && node.contentDescription?.toString()?.contains(description, ignoreCase = true) == true
            }

    private fun findEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        walk(root) { it.isEditable }

    private fun walk(node: AccessibilityNodeInfo, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (predicate(node)) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = walk(child, predicate)
            if (result != null) return result
        }
        return null
    }

    override fun onInterrupt() {
        // Nothing to clean up.
    }
}
