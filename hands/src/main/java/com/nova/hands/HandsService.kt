package com.nova.hands

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Nova's hands. Waits for commands from the Nova app (signature-protected broadcast),
 * acts on the app you're looking at, and replies with a short result.
 * It only reads the screen when Nova asks, and keeps nothing.
 */
class HandsService : AccessibilityService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val id = i.getStringExtra("id") ?: return
            val cmd = i.getStringExtra("cmd").orEmpty()
            val arg = i.getStringExtra("arg").orEmpty()
            scope.launch {
                val result = try { run(cmd, arg) } catch (e: Exception) { "error: ${e.message}" }
                sendBroadcast(
                    Intent(ACTION_RESULT).setPackage(NOVA).putExtra("id", id).putExtra("result", result),
                    PERMISSION,
                )
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        ContextCompat.registerReceiver(this, receiver, IntentFilter(ACTION_DO), PERMISSION, null, ContextCompat.RECEIVER_EXPORTED)
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(receiver) }
        scope.cancel()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    // ------------------------------------------------------------------ commands

    private suspend fun run(cmd: String, arg: String): String = when (cmd) {
        "tap" -> waitFor(4000) { findClickable(arg) }?.let { click(it) } ?: "not found"
        "type" -> {
            val field = waitFor(3000) { editable() } ?: return "not found"
            val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, arg) }
            field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            if (field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) "ok" else "failed"
        }
        "scroll" -> {
            val node = scrollable() ?: return "not found"
            val action = if (arg == "up") AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            if (node.performAction(action)) "ok" else "failed"
        }
        "global" -> {
            val a = when (arg) {
                "back" -> GLOBAL_ACTION_BACK
                "home" -> GLOBAL_ACTION_HOME
                "recents" -> GLOBAL_ACTION_RECENTS
                "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
                "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS
                else -> return "unknown"
            }
            if (performGlobalAction(a)) "ok" else "failed"
        }
        "read" -> readScreen()
        // WhatsApp: press the Send button once the chat has opened
        "send" -> waitFor(7000) {
            nodes().firstOrNull { it.viewIdResourceName == "com.whatsapp:id/send" }
                ?: nodes().firstOrNull { it.contentDescription?.toString().equals("Send", ignoreCase = true) && it.isVisibleToUser }
        }?.let { click(it) } ?: "not found"
        // YouTube: play the first video in the results
        "play_first" -> waitFor(8000) {
            nodes().firstOrNull {
                it.isVisibleToUser && it.contentDescription?.toString()?.contains("play video", ignoreCase = true) == true
            } ?: nodes().firstOrNull { it.isVisibleToUser && it.viewIdResourceName?.endsWith(":id/thumbnail") == true }
        }?.let { click(it) } ?: "not found"
        else -> "unknown"
    }

    /** The app you're looking at: the top application window that isn't Nova itself. */
    private fun appRoot(): AccessibilityNodeInfo? =
        windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .sortedByDescending { it.layer }
            .mapNotNull { it.root }
            .firstOrNull { it.packageName?.toString() != NOVA }
            ?: rootInActiveWindow

    private fun nodes(): List<AccessibilityNodeInfo> {
        val root = appRoot() ?: return emptyList()
        val out = ArrayList<AccessibilityNodeInfo>(256)
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty() && out.size < 2500) {
            val n = queue.removeFirst()
            out += n
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        return out
    }

    private fun label(n: AccessibilityNodeInfo) =
        (n.text?.toString() ?: n.contentDescription?.toString() ?: "").trim()

    private fun findClickable(target: String): AccessibilityNodeInfo? {
        val want = target.lowercase().trim()
        val scored = nodes().filter { it.isVisibleToUser }.mapNotNull { n ->
            val l = label(n).lowercase()
            when {
                l.isEmpty() -> null
                l == want -> 0 to n
                l.startsWith(want) -> 1 to n
                l.contains(want) -> 2 to n
                else -> null
            }
        }
        val best = scored.minByOrNull { it.first }?.second ?: return null
        var n: AccessibilityNodeInfo? = best
        while (n != null && !n.isClickable) n = n.parent
        return n ?: best
    }

    private fun click(n: AccessibilityNodeInfo): String {
        var node: AccessibilityNodeInfo? = n
        while (node != null && !node.isClickable) node = node.parent
        return if ((node ?: n).performAction(AccessibilityNodeInfo.ACTION_CLICK)) "ok" else "failed"
    }

    private fun editable(): AccessibilityNodeInfo? =
        appRoot()?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }
            ?: nodes().firstOrNull { it.isEditable && it.isVisibleToUser }

    private fun scrollable(): AccessibilityNodeInfo? =
        nodes().filter { it.isScrollable && it.isVisibleToUser }.maxByOrNull {
            val r = android.graphics.Rect(); it.getBoundsInScreen(r); r.width() * r.height()
        }

    private fun readScreen(): String {
        val seen = LinkedHashSet<String>()
        for (n in nodes()) {
            if (!n.isVisibleToUser || n.isPassword) continue // never read password fields
            val t = label(n)
            if (t.length > 1) seen += t
            if (seen.size > 80) break
        }
        return seen.joinToString(". ").take(1500)
    }

    private suspend fun <T> waitFor(ms: Long, find: () -> T?): T? {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            find()?.let { return it }
            delay(250)
        }
        return null
    }

    companion object {
        const val NOVA = "com.nova.assistant"
        const val PERMISSION = "com.nova.assistant.permission.CONTROL"
        const val ACTION_DO = "com.nova.hands.DO"
        const val ACTION_RESULT = "com.nova.assistant.HANDS_RESULT"
    }
}
