package com.emerge.app.capture

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/** Ephemeral structural scan. Input values are never read, retained or logged. */
internal object PagePrivacyGuard {
    data class Result(val payment: Boolean, val inputVisible: Boolean, val complete: Boolean) {
        val blockText get() = payment || !complete
        val blockImage get() = blockText || inputVisible
    }

    @Suppress("DEPRECATION")
    fun inspect(root: AccessibilityNodeInfo, visible: Rect): Result {
        val nodes = mutableListOf<PaymentNode>()
        var count = 0
        var complete = true
        var input = false
        fun visit(node: AccessibilityNodeInfo, depth: Int, parent: Int) {
            if (++count > 1500 || depth > 40) { complete = false; return }
            val bounds = Rect().also(node::getBoundsInScreen)
            var visibleParent = parent
            if (node.isVisibleToUser && Rect.intersects(bounds, visible)) {
                val editable = node.isPassword || node.isEditable
                input = input || editable
                val id = node.viewIdResourceName.orEmpty()
                visibleParent = nodes.size
                nodes += PaymentNode(resourceId = id, parentIndex = parent,
                    actionable = node.isClickable || node.className?.toString()?.endsWith("Button") == true)
                if (!editable) {
                    listOf(node.text, node.contentDescription).forEach { value ->
                        // Full short labels only: an article excerpt must not become a payment button.
                        if (value != null && value.length <= 100)
                            nodes += PaymentNode(value.toString(), id, parentIndex = visibleParent)
                    }
                }
            }
            for (index in 0 until node.childCount) {
                if (count >= 1500) { complete = false; break }
                node.getChild(index)?.let { child ->
                    try { visit(child, depth + 1, visibleParent) } finally { child.recycle() }
                }
            }
        }
        visit(root, 0, -1)
        return Result(PaymentPagePolicy.blocks(nodes), input, complete)
    }
}
