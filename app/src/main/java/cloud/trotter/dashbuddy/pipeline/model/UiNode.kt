package cloud.trotter.dashbuddy.pipeline.model

import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json

/**
 * A data class to hold structured information about a single UI element (node).
 */
@Serializable
data class UiNode(
    // 1. Use @SerialName to keep the JSON keys short (matches your old output)
    @SerialName("text") val text: String? = null,
    @SerialName("desc") val contentDescription: String? = null,
    @SerialName("state") val stateDescription: String? = null,

    // Note: This will save the FULL ID ("com.app:id/foo"), not just "foo".
    // This is better for data integrity.
    @SerialName("id") val viewIdResourceName: String? = null,
    @SerialName("class") val className: String? = null,

    // Flags
    val isClickable: Boolean = false,
    val isEnabled: Boolean = false,
    val isChecked: Int = 0,

    // 2. Use the Custom Serializer for Android Rect
    @Serializable(with = RectSerializer::class)
    @SerialName("bounds")
    val boundsInScreen: Rect = Rect(),

    // 3. Transient + VAR: Not saved to JSON, but we can re-link it later.
    @Transient
    var parent: UiNode? = null,

    @SerialName("children")
    val children: MutableList<UiNode> = mutableListOf(),

    // 4. System object: Never save this.
    @Transient
    val originalNode: AccessibilityNodeInfo? = null,
) {

    /**
     * Call this immediately after deserializing from JSON to re-populate
     * the 'parent' fields for the entire tree.
     */
    fun restoreParents() {
        for (child in children) {
            child.parent = this
            child.restoreParents() // Recurse down
        }
    }

    /**
     * Serializes to JSON using kotlinx.serialization.
     */
    fun toJson(): String {
        val json = Json {
            prettyPrint = true
            ignoreUnknownKeys = true
            explicitNulls = false
        }
        return json.encodeToString(this)
    }

    // --- YOUR EXISTING LOGIC (Preserved) ---

    fun hasId(idSnippet: String): Boolean {
        return viewIdResourceName?.endsWith(idSnippet) == true
    }

    fun findChildById(idSnippet: String): UiNode? {
        return children.find { it.hasId(idSnippet) }
    }

    fun findDescendantById(idSnippet: String): UiNode? {
        if (hasId(idSnippet)) return this
        for (child in children) {
            val found = child.findDescendantById(idSnippet)
            if (found != null) return found
        }
        return null
    }

    override fun toString(): String {
        val builder = StringBuilder()
        appendNode(builder, this, 0)
        return builder.toString()
    }

    private fun appendNode(builder: StringBuilder, node: UiNode, indent: Int) {
        val indentation = "  ".repeat(indent)
        val id = node.viewIdResourceName?.substringAfter("id/") ?: "no_id"
        val desc = node.contentDescription?.let { "desc='$it'" } ?: ""
        val txt = node.text?.let { "text='$it'" } ?: ""
        val state = node.stateDescription?.let { "state='$it'" }
        val identifier = listOf(txt, desc).filter { it.isNotEmpty() }.joinToString(", ")

        builder.append(indentation)
            .append("UiNode($identifier, id=$id, state=${state}, class=${node.className})\n")

        for (child in node.children) {
            appendNode(builder, child, indent + 1)
        }
    }

    fun findNode(predicate: (UiNode) -> Boolean): UiNode? {
        if (predicate(this)) return this
        for (child in children) {
            val found = child.findNode(predicate)
            if (found != null) return found
        }
        return null
    }

    fun findNodes(predicate: (UiNode) -> Boolean): List<UiNode> {
        val matches = mutableListOf<UiNode>()
        if (predicate(this)) matches.add(this)
        for (child in children) {
            matches.addAll(child.findNodes(predicate))
        }
        return matches
    }

    fun hasNode(predicate: (UiNode) -> Boolean): Boolean {
        return this.findNode(predicate) != null
    }

    val structuralHash: Int by lazy {
        var result = className?.hashCode() ?: 0
        result = 31 * result + (viewIdResourceName?.hashCode() ?: 0)
        for (child in children) {
            result = 31 * result + child.structuralHash
        }
        result
    }

    val contentHash: Int by lazy {
        var result = text?.hashCode() ?: 0
        result = 31 * result + (contentDescription?.hashCode() ?: 0)
        for (child in children) {
            result = 31 * result + child.contentHash
        }
        result
    }

    val allText: List<String> by lazy {
        val results = mutableListOf<String>()
        collectText(this, results)
        results
    }

    private fun collectText(node: UiNode, list: MutableList<String>) {
        if (!node.text.isNullOrBlank()) list.add(node.text)
        if (!node.contentDescription.isNullOrBlank()) list.add(node.contentDescription)
        node.children.forEach { collectText(it, list) }
    }

    companion object {
        @RequiresApi(Build.VERSION_CODES.BAKLAVA)
        fun from(accNode: AccessibilityNodeInfo?, parentUiNode: UiNode? = null): UiNode? {
            if (accNode == null) return null

            val bounds = Rect()
            accNode.getBoundsInScreen(bounds)

            // Convert boolean checked to Int if that is your preference
            val checkedInt = accNode.checked

            val currentUiNode = UiNode(
                text = accNode.text?.toString(),
                contentDescription = accNode.contentDescription?.toString(),
                stateDescription = accNode.stateDescription?.toString(),
                viewIdResourceName = accNode.viewIdResourceName,
                className = accNode.className?.toString(),
                isClickable = accNode.isClickable,
                isEnabled = accNode.isEnabled,
                isChecked = checkedInt,
                boundsInScreen = bounds,
                parent = parentUiNode,
                originalNode = accNode
            )

            for (i in 0 until accNode.childCount) {
                val childAccNode = accNode.getChild(i)
                from(childAccNode, currentUiNode)?.let { childUiNode ->
                    currentUiNode.children.add(childUiNode)
                }
            }

            return currentUiNode
        }
    }
}