package cloud.trotter.dashbuddy.accessibility

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * A data class to hold structured information about a single UI element (node).
 * This class is currently unused; it is kept for reference, in case we decide to use
 * it to extract data from events from the Accessibility Service later on.
 */
data class UiNode(
    val text: String? = null,
    val contentDescription: String? = null,
    val viewIdResourceName: String? = null, // e.g., "com.doordash.driverapp:id/accept_button"
    val className: String? = null,           // e.g., "android.widget.Button"
    val isClickable: Boolean = false,
    val isEnabled: Boolean = false,
    val isChecked: Boolean = false,
    val boundsInScreen: Rect = Rect(),
    val parent: UiNode? = null, // Can be useful for hierarchy
    val children: MutableList<UiNode> = mutableListOf(),
    val originalNode: AccessibilityNodeInfo,
) {
    override fun toString(): String {
        val id = viewIdResourceName?.substringAfter("id/") ?: "no_id"
        val desc = contentDescription?.let { "desc='$it'" } ?: ""
        val txt = text?.let { "text='$it'" } ?: ""

        // Combine text and contentDescription for a single 'identifier'
        val identifier = listOf(txt, desc).filter { it.isNotEmpty() }.joinToString(", ")

        return "UiNode($identifier, id=$id, class=$className, clickable=$isClickable, bounds=$boundsInScreen)"
    }
}