package cloud.trotter.dashbuddy.domain.model.accessibility

/**
 * A data class to hold structured information about a single UI element (node).
 * Optimized for both efficient recursive searching (Clicks) and full-text collection (Screen Analysis).
 * Essentially a pared-down serialization for AccessibilityNode in Android.
 *
 * IMMUTABLE after construction (#363): [children] is a read-only List built
 * bottom-up by the mappers, so the lazy hash/text caches can never go stale.
 * `equals`/`hashCode` deliberately compare THIS node's identity fields only —
 * never the recursive tree (that's what the hash properties are for).
 *
 * If properties are edited, ensure you update the following to match:
 *  In this file
 *    - the equals function
 *    - the hashcode function
 *  In :core:database
 *  - UiNodeDto
 *  - UiNode.toDto
 *  - UiNode.toDomain
 */

data class UiNode(

    val text: String? = null,
    val contentDescription: String? = null,
    val stateDescription: String? = null,

    val viewIdResourceName: String? = null,
    val className: String? = null,

    // Flags
    val isClickable: Boolean = false,
    val isEnabled: Boolean = false,
    val isChecked: Int = 0,

    val boundsInScreen: BoundingBox = BoundingBox(0, 0, 0, 0),

    val children: List<UiNode> = emptyList(),
) {

    /**
     * Back-reference up the tree. Read-only to consumers (#363): the ONLY
     * writer is [restoreParents], which can never wire anything but the true
     * containing node. Excluded from equals/hashCode/copy by living outside
     * the primary constructor.
     */
    var parent: UiNode? = null
        private set

    /**
     * Wire the parent back-references for the whole tree (#363). The single
     * mutation point on an otherwise-immutable tree — called once by the
     * construction/deserialization factories before the tree is shared.
     * Idempotent; returns this for chaining. The lazy hash/text caches are
     * safe because they never include [parent].
     */
    fun restoreParents(): UiNode {
        for (child in children) {
            child.parent = this
            child.restoreParents() // Recurse down
        }
        return this
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as UiNode

        // Compare unique identifiers instead of the recursive tree.
        // Text comparisons: text, contentDescription, stateDescription
        if (text != other.text) return false
        if (contentDescription != other.contentDescription) return false
        if (stateDescription != other.stateDescription) return false
        // ID and className
        if (viewIdResourceName != other.viewIdResourceName) return false
        if (className != other.className) return false
        // Flags
        if (isClickable != other.isClickable) return false
        if (isEnabled != other.isEnabled) return false
        if (isChecked != other.isChecked) return false
        // Bounds
        if (boundsInScreen != other.boundsInScreen) return false

        return true
    }

    override fun hashCode(): Int {
        // Hash based on unique identifiers
        // Texts: text, contentDescription, stateDescription
        var result = text?.hashCode() ?: 0
        result = 31 * result + (contentDescription?.hashCode() ?: 0)
        result = 31 * result + (stateDescription?.hashCode() ?: 0)
        // ID and className
        result = 31 * result + (viewIdResourceName?.hashCode() ?: 0)
        result = 31 * result + (className?.hashCode() ?: 0)
        // Flags
        result = 31 * result + isClickable.hashCode()
        result = 31 * result + isEnabled.hashCode()
        result = 31 * result + isChecked.hashCode()
        // Bounds
        result = 31 * result + boundsInScreen.hashCode()
        return result
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
    //  INTERPRETER HELPERS
    //  Used by the JSON rule interpreter (RuleCompiler). Internalise patterns
    //  that appear across many Kotlin matchers so the DSL stays simple.
    // ========================================================================

    /** Walk [n] levels up the parent chain. Null if tree is shallower than [n]. */
    fun ancestor(n: Int): UiNode? {
        var current: UiNode? = this
        repeat(n) { current = current?.parent }
        return current
    }

    /**
     * Return the sibling at (this node's index in parent's children + [offset]).
     * Null if there is no parent or the computed index is out of bounds.
     */
    fun sibling(offset: Int): UiNode? {
        val siblings = parent?.children ?: return null
        val idx = siblings.indexOf(this) + offset
        return siblings.getOrNull(idx)
    }

    /**
     * Find [label] in this node's [allText] DFS list and return the entry at label+[offset].
     * [allText] is lazy — computed once and cached — so repeated calls are free.
     * Returns null if [label] is not found or the offset index is out of bounds.
     */
    fun textAfterLabel(label: String, offset: Int = 1): String? {
        val idx = allText.indexOfFirst { it.equals(label, ignoreCase = true) }
        return if (idx >= 0) allText.getOrNull(idx + offset) else null
    }

    /** True when [viewIdResourceName] is non-null and non-blank. */
    val hasViewId: Boolean
        get() = !viewIdResourceName.isNullOrBlank()

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

    /**
     * Structural hash that ignores anonymous wrapper nodes.
     *
     * Compose recomposition transiently adds/removes generic View wrappers
     * (no [viewIdResourceName], generic [className]) without changing the
     * semantic screen content. [structuralHash] is sensitive to these and
     * produces false-positive "new screen" signals.
     *
     * [stableHash] treats anonymous wrappers as transparent — their children
     * contribute directly to the parent's hash, so adding or removing a
     * wrapper does not change the result.
     */
    val stableHash: Int by lazy { computeStableHash(this) }

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
}

// ============================================================================
//  Stable hash helpers (package-private)
// ============================================================================

private fun computeStableHash(node: UiNode): Int {
    // Anonymous wrapper: no ID, generic class — hash through to children only
    if (node.viewIdResourceName == null && node.className.isAnonymousWrapper()) {
        var result = 0
        for (child in node.children) {
            result = 31 * result + computeStableHash(child)
        }
        return result
    }
    // Named or meaningful node — include in hash
    var result = node.className?.hashCode() ?: 0
    result = 31 * result + (node.viewIdResourceName?.hashCode() ?: 0)
    for (child in node.children) {
        result = 31 * result + computeStableHash(child)
    }
    return result
}

private fun String?.isAnonymousWrapper(): Boolean = when (this) {
    "android.view.View",
    "android.view.ViewGroup",
    "android.widget.FrameLayout",
    "android.widget.LinearLayout" -> true
    else -> false
}