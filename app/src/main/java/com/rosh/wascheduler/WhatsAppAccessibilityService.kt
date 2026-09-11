package com.rosh.wascheduler

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

/**
 * Watches ONLY the WhatsApp package (enforced by accessibility_service_config.xml
 * via android:packageNames="com.whatsapp" — this service is structurally
 * unable to see or act on any other app). It also never touches, and cannot
 * touch, Android's lock screen: the keyguard is a separate, hardened system
 * window no accessibility service is allowed to read or inject input into,
 * by OS design — see the README's "About the lock screen" section.
 *
 * Handles two jobs, decided by AlarmReceiver right before it opens WhatsApp:
 *
 *  - INDIVIDUAL: WhatsApp already opened a specific chat with the message
 *    pre-filled via the wa.me deep link. This just finds Send and clicks it.
 *
 *  - GROUP: WhatsApp opened to its main chat list (there's no deep link to a
 *    specific group). This drives WhatsApp's own search box to find the
 *    group, opens it, types the message — routing any @[Name] mention
 *    through WhatsApp's own suggestion popup so it becomes a real, tappable,
 *    notifying mention rather than literal text — then taps Send.
 *
 * The group path touches several different WhatsApp screens (search, results
 * list, chat compose box), so it is meaningfully more likely than the
 * individual path to break when WhatsApp updates its UI. If a stage times
 * out it gives up with a Toast naming which stage failed, rather than
 * retrying forever or silently doing nothing.
 */
class WhatsAppAccessibilityService : AccessibilityService() {

    sealed class Segment {
        data class Text(val text: String) : Segment()
        data class Mention(val name: String) : Segment()
    }

    data class GroupJob(val groupName: String, val segments: List<Segment>)

    companion object {
        @Volatile
        var pendingGroupJob: GroupJob? = null
    }

    private val handler = Handler(Looper.getMainLooper())
    private var individualHandled = false

    private enum class Stage { IDLE, RUNNING }
    private var stage = Stage.IDLE
    private var segmentIndex = 0
    private var textSoFar = ""

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.packageName != "com.whatsapp") return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) return

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

    // ---------- individual: unchanged, simple case ----------

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
