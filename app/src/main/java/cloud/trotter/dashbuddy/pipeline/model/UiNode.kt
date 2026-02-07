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
 * Optimized for both efficient recursive searching (Clicks) and full-text collection (Screen Analysis).
 */
@Serializable
data class UiNode(
    // 1. JSON Keys kept short to match storage format
    @SerialName("text") val text: String? = null,
    @SerialName("desc") val contentDescription: String? = null,
    @SerialName("state") val stateDescription: String? = null,

    // Full Resource ID ("com.app:id/foo")
    @SerialName("id") val viewIdResourceName: String? = null,
    @SerialName("class") val className: String? = null,

    // Flags
    val isClickable: Boolean = false,
    val isEnabled: Boolean = false,
    val isChecked: Int = 0,

    // 2. Custom Serializer for Android Rect
    @Serializable(with = RectSerializer::class)
    @SerialName("bounds")
    val boundsInScreen: Rect = Rect(),

    // 3. Transient: Not saved to JSON, re-linked manually
    @Transient
    var parent: UiNode? = null,

    @SerialName("children")
    val children: MutableList<UiNode> = mutableListOf(),

    // 4. System object: Never saved
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

    // ========================================================================
    //  STRICT MATCHERS (Check ONLY this node)
    //  Use these for specific logic or inside the recursive functions.
    // ========================================================================

    fun matchesId(idSnippet: String): Boolean {
        return viewIdResourceName?.endsWith(idSnippet, ignoreCase = true) == true
    }

    fun matchesText(substring: String): Boolean {
        return text?.contains(substring, ignoreCase = true) == true
    }

    fun matchesDesc(substring: String): Boolean {
        return contentDescription?.contains(substring, ignoreCase = true) == true
    }

    // ========================================================================
    //  RECURSIVE SEARCHERS (Check this node AND children)
    //  Optimized to stop searching as soon as a match is found.
    // ========================================================================

    /** Does this node OR any descendant have this ID suffix? */
    fun hasId(idSnippet: String): Boolean {
        return findNode { it.matchesId(idSnippet) } != null
    }

    /** Does this node OR any descendant contain this visible text? */
    fun hasText(substring: String): Boolean {
        return findNode { it.matchesText(substring) } != null
    }

    /** Does this node OR any descendant contain this content description? */
    fun hasContentDescription(desc: String): Boolean {
        return findNode { it.matchesDesc(desc) } != null
    }

    // ========================================================================
    //  TREE TRAVERSAL & SEARCH
    // ========================================================================

    /**
     * Finds the specific node (self or descendant) that has the matching ID.
     */
    fun findDescendantById(idSnippet: String): UiNode? {
        // STRICT check on self first (Fixes infinite recursion bug)
        if (matchesId(idSnippet)) return this

        for (child in children) {
            val found = child.findDescendantById(idSnippet)
            if (found != null) return found
        }
        return null
    }

    /**
     * Finds the first node matching the predicate.
     */
    fun findNode(predicate: (UiNode) -> Boolean): UiNode? {
        if (predicate(this)) return this
        for (child in children) {
            val found = child.findNode(predicate)
            if (found != null) return found
        }
        return null
    }

    /**
     * Finds ALL nodes matching the predicate.
     */
    fun findNodes(predicate: (UiNode) -> Boolean): List<UiNode> {
        val matches = mutableListOf<UiNode>()
        if (predicate(this)) matches.add(this)
        for (child in children) {
            matches.addAll(child.findNodes(predicate))
        }
        return matches
    }

    /**
     * Returns true if any node in the tree matches the predicate.
     */
    fun hasNode(predicate: (UiNode) -> Boolean): Boolean {
        return this.findNode(predicate) != null
    }

    /**
     * Finds a child (direct descendant) with the matching ID.
     */
    fun findChildById(idSnippet: String): UiNode? {
        return children.find { it.matchesId(idSnippet) }
    }

    // ========================================================================
    //  DATA COLLECTION (Lazy)
    // ========================================================================

    /**
     * Collects all text and content descriptions from the entire tree.
     * Used primarily by Screen Recognizers.
     */
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

    // ========================================================================
    //  DEBUGGING & LOGGING
    // ========================================================================

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

    // ========================================================================
    //  FACTORY
    // ========================================================================

    companion object {
        @RequiresApi(Build.VERSION_CODES.BAKLAVA)
        fun from(accNode: AccessibilityNodeInfo?, parentUiNode: UiNode? = null): UiNode? {
            if (accNode == null) return null

            val bounds = Rect()
            accNode.getBoundsInScreen(bounds)

            val currentUiNode = UiNode(
                text = accNode.text?.toString(),
                contentDescription = accNode.contentDescription?.toString(),
                stateDescription = accNode.stateDescription?.toString(),
                viewIdResourceName = accNode.viewIdResourceName,
                className = accNode.className?.toString(),
                isClickable = accNode.isClickable,
                isEnabled = accNode.isEnabled,
                isChecked = accNode.checked,
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