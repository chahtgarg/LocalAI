
package com.example.localai

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

// Custom Android Accessibility Service.
// Allows LocalAI to read accessible screen content and perform supported actions.
class ScreenReaderService : AccessibilityService() {

    companion object {

        // Stores the currently connected service instance.
        // MainActivity uses this to access the service.
        @Volatile
        var instance: ScreenReaderService? = null
    }

    // Called when Android successfully connects the accessibility service.
    override fun onServiceConnected() {
        instance = this
    }

    // Called when the service is disconnected.
    // Clears the stored instance.
    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    // Called when an accessibility event occurs.
    // Currently, this project does not process individual events here.
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    // Called if Android interrupts the service.
    override fun onInterrupt() {}


    // =========================================
    // PART 1: READING SCREEN TEXT
    // =========================================

    fun readScreenText(): String {

        // Gets the root node of the currently active window.
        // Returns empty text if no active window is available.
        val root = rootInActiveWindow ?: return ""

        val sb = StringBuilder()

        // Recursively collects readable text from the screen.
        collectText(root, sb, 0)

        return sb.toString().trim()
    }

    // Recursively visits accessibility nodes in the screen hierarchy.
    private fun collectText(
        node: AccessibilityNodeInfo?,
        sb: StringBuilder,
        depth: Int
    ) {

        // Stops if the node is missing or the traversal is too deep.
        if (node == null || depth > 40) return

        // Skips password fields for privacy.
        if (node.isPassword) return

        // Gets visible text or the node's content description.
        val text = node.text?.toString()
            ?: node.contentDescription?.toString()

        // Adds readable text to the final output.
        if (!text.isNullOrBlank()) {
            sb.append(text.trim()).append('\n')
        }

        // Visits every child node recursively.
        for (i in 0 until node.childCount) {
            collectText(node.getChild(i), sb, depth + 1)
        }
    }


    // =========================================
    // PART 2: ACCESSIBILITY ACTIONS
    // =========================================

    // Scrolls the active screen downward.
    // Called only after the user confirms the action.
    fun scrollDown(): Boolean {

        val root = rootInActiveWindow ?: return false

        // Finds a scrollable element on the screen.
        val target = findScrollable(root) ?: return false

        // Performs Android's forward-scroll accessibility action.
        return target.performAction(
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        )
    }

    // Searches the screen hierarchy for a scrollable node.
    private fun findScrollable(
        node: AccessibilityNodeInfo?
    ): AccessibilityNodeInfo? {

        if (node == null) return null

        // Returns the node if it supports scrolling.
        if (node.isScrollable) return node

        // Searches its children until a scrollable node is found.
        for (i in 0 until node.childCount) {
            findScrollable(node.getChild(i))?.let {
                return it
            }
        }

        return null
    }

    // Finds an element by its text and attempts to click it.
    fun clickByText(label: String): Boolean {

        val root = rootInActiveWindow ?: return false

        // Finds the first accessibility node matching the given text.
        val match = root.findAccessibilityNodeInfosByText(label)
            .firstOrNull() ?: return false

        var node: AccessibilityNodeInfo? = match

        // Moves up the hierarchy until it finds a clickable parent.
        while (node != null && !node.isClickable) {
            node = node.parent
        }

        // Performs the click if a clickable node was found.
        return node?.performAction(
            AccessibilityNodeInfo.ACTION_CLICK
        ) ?: false
    }

    // Uses Android's global accessibility action to go back.
    fun goBack(): Boolean =
        performGlobalAction(GLOBAL_ACTION_BACK)
}
