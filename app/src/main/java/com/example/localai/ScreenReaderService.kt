package com.example.localai

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class ScreenReaderService : AccessibilityService() {

    companion object {
        @Volatile var instance: ScreenReaderService? = null
    }

    override fun onServiceConnected() {
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    // ---------- READ ----------
    fun readScreenText(): String {
        val root = rootInActiveWindow ?: return ""
        val sb = StringBuilder()
        collectText(root, sb, 0)
        return sb.toString().trim()
    }

    private fun collectText(node: AccessibilityNodeInfo?, sb: StringBuilder, depth: Int) {
        if (node == null || depth > 40) return
        if (node.isPassword) return
        val text = node.text?.toString() ?: node.contentDescription?.toString()
        if (!text.isNullOrBlank()) sb.append(text.trim()).append('\n')
        for (i in 0 until node.childCount) collectText(node.getChild(i), sb, depth + 1)
    }

    // ---------- ACT (only called after the user confirms) ----------
    fun scrollDown(): Boolean {
        val root = rootInActiveWindow ?: return false
        val target = findScrollable(root) ?: return false
        return target.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
    }

    private fun findScrollable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            findScrollable(node.getChild(i))?.let { return it }
        }
        return null
    }

    fun clickByText(label: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val match = root.findAccessibilityNodeInfosByText(label).firstOrNull() ?: return false
        var node: AccessibilityNodeInfo? = match
        while (node != null && !node.isClickable) node = node.parent
        return node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
    }

    fun goBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
}