package cloud.trotter.dashbuddy.domain.model.accessibility

import cloud.trotter.dashbuddy.domain.pipeline.NO_ID_FALLBACK
import java.util.Locale

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
 *    - [UiNodeTextField] + [scrubbableStrings] / [mapScrubbableStrings], if the new
 *      property is a STRING the capture envelope serializes (#835)
 *  In :core:database
 *  - UiNodeDto
 *  - UiNode.toDto
 *  - UiNode.toDomain
 */

data class UiNode(

    val text: String? = null,
    val contentDescription: String? = null,
    val stateDescription: String? = null,

    // #1147 (the TalkBack study, win 3) — the richer third-party strings TalkBack reads. Every one is
    // `capText()`-bounded at the mapper, a [UiNodeTextField] entry (so every scrub/redact/PII-scan
    // site covers it through [scrubbableStrings]/[mapScrubbableStrings]), and deliberately OUTSIDE
    // [allText] and every content/structural hash — widening the scrub layer never moves a
    // classification or a frame identity. Rules reach them only through their own node predicates.
    /** `AccessibilityNodeInfo.getPaneTitle()` — the named scope an id-less sheet/dialog carries. */
    val paneTitle: String? = null,
    /** The AndroidX `roleDescription` extra (e.g. "Button", "Tab") a Compose control publishes. */
    val roleDescription: String? = null,
    /** `getHintText()` — an input's placeholder/hint. */
    val hintText: String? = null,
    /** `getTooltipText()`. */
    val tooltipText: String? = null,
    /** `getError()` — an input's error message. */
    val errorText: String? = null,
    /** The label attached to this node's `ACTION_CLICK` entry ("Accept offer"), when it has one. */
    val clickActionLabel: String? = null,
    /**
     * `getUniqueId()` (API 33+). Execution evidence only — NO predicate exposes it and no rule may
     * name it; unverified whether any platform sets it.
     */
    val uniqueId: String? = null,

    val viewIdResourceName: String? = null,
    val className: String? = null,

    // Flags
    val isClickable: Boolean = false,
    val isEnabled: Boolean = false,
    val isChecked: Int = 0,
    /**
     * #1149 review J2 (the #1147 field, brought forward): the live node ADVERTISES
     * `ACTION_CLICK` in its action list — Compose controls often do without setting
     * [isClickable]. Deliberately NOT in [allText] and NOT in any content/structural hash;
     * read it through [takesClick].
     */
    val hasClickAction: Boolean = false,
    /**
     * #1149 review L3/V1: this node belongs to a DIFFERENT package than its window root, or sits beneath
     * one that does (an embedded foreign subtree — the boundary is inherited). Not in [allText] or any content/structural hash.
     */
    val foreignPackage: Boolean = false,
    /**
     * #1149 review L4/N6: advertised children the mapper did not materialize — a null `getChild`, or
     * a [children] entry the tree budget refused (depth, node budget, loop cap) — silently absent. Not in [allText] or any content/structural hash.
     */
    val unreadableChildren: Int = 0,

    // #1147 — non-string semantics TalkBack reads. Defaults are the dominant value so a typical
    // node serializes nothing new. Not in [allText] or any content/structural hash.
    val isVisibleToUser: Boolean = true,
    val isFocusable: Boolean = false,
    val isScreenReaderFocusable: Boolean = false,
    val isCheckable: Boolean = false,
    val isSelected: Boolean = false,
    val isHeading: Boolean = false,
    /** `getLiveRegion()` — 0 none, 1 polite, 2 assertive. */
    val liveRegion: Int = 0,
    /** `getCollectionInfo()` row/column counts; -1 when the node is not a collection. */
    val collectionRows: Int = -1,
    val collectionCols: Int = -1,
    /**
     * `getCollectionItemInfo()` row/column index; -1 when not a collection item. Weak identity —
     * indices move when items reorder — so no predicate exposes them.
     */
    val itemRow: Int = -1,
    val itemCol: Int = -1,

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
     * #1149 review J2 — THE bind-time "this node takes a click" predicate: [isClickable] OR an
     * advertised click action ([hasClickAction]). Its live mirror is
     * the live `AccessibilityNodeInfo.takesClick()` (`isClickable` OR `ACTION_CLICK` in the action list), so the
     * label horizon's clickable-descendant ownership is decided identically on both sides.
     */
    val takesClick: Boolean get() = isClickable || hasClickAction

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
        if (paneTitle != other.paneTitle) return false
        if (roleDescription != other.roleDescription) return false
        if (hintText != other.hintText) return false
        if (tooltipText != other.tooltipText) return false
        if (errorText != other.errorText) return false
        if (clickActionLabel != other.clickActionLabel) return false
        if (uniqueId != other.uniqueId) return false
        // ID and className
        if (viewIdResourceName != other.viewIdResourceName) return false
        if (className != other.className) return false
        // Flags
        if (isClickable != other.isClickable) return false
        if (isEnabled != other.isEnabled) return false
        if (isChecked != other.isChecked) return false
        if (hasClickAction != other.hasClickAction) return false
        if (foreignPackage != other.foreignPackage) return false
        if (unreadableChildren != other.unreadableChildren) return false
        if (isVisibleToUser != other.isVisibleToUser) return false
        if (isFocusable != other.isFocusable) return false
        if (isScreenReaderFocusable != other.isScreenReaderFocusable) return false
        if (isCheckable != other.isCheckable) return false
        if (isSelected != other.isSelected) return false
        if (isHeading != other.isHeading) return false
        if (liveRegion != other.liveRegion) return false
        if (collectionRows != other.collectionRows) return false
        if (collectionCols != other.collectionCols) return false
        if (itemRow != other.itemRow) return false
        if (itemCol != other.itemCol) return false
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
        result = 31 * result + (paneTitle?.hashCode() ?: 0)
        result = 31 * result + (roleDescription?.hashCode() ?: 0)
        result = 31 * result + (hintText?.hashCode() ?: 0)
        result = 31 * result + (tooltipText?.hashCode() ?: 0)
        result = 31 * result + (errorText?.hashCode() ?: 0)
        result = 31 * result + (clickActionLabel?.hashCode() ?: 0)
        result = 31 * result + (uniqueId?.hashCode() ?: 0)
        // ID and className
        result = 31 * result + (viewIdResourceName?.hashCode() ?: 0)
        result = 31 * result + (className?.hashCode() ?: 0)
        // Flags
        result = 31 * result + isClickable.hashCode()
        result = 31 * result + isEnabled.hashCode()
        result = 31 * result + isChecked.hashCode()
        result = 31 * result + hasClickAction.hashCode()
        result = 31 * result + foreignPackage.hashCode()
        result = 31 * result + unreadableChildren
        result = 31 * result + isVisibleToUser.hashCode()
        result = 31 * result + isFocusable.hashCode()
        result = 31 * result + isScreenReaderFocusable.hashCode()
        result = 31 * result + isCheckable.hashCode()
        result = 31 * result + isSelected.hashCode()
        result = 31 * result + isHeading.hashCode()
        result = 31 * result + liveRegion
        result = 31 * result + collectionRows
        result = 31 * result + collectionCols
        result = 31 * result + itemRow
        result = 31 * result + itemCol
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
     *
     * Resolves this node's index by STRUCTURAL equality ([List.indexOf]), so a structurally
     * identical earlier sibling resolves the wrong index. Kept as-is for the positional
     * `sibling(N)` rule vocabulary that has always had those semantics; anything that must be
     * right in the presence of twins uses [followingSiblings] / [precedingSibling] instead.
     */
    fun sibling(offset: Int): UiNode? {
        val siblings = parent?.children ?: return null
        val idx = siblings.indexOf(this) + offset
        return siblings.getOrNull(idx)
    }

    /**
     * The siblings AFTER this node, in order, resolved by REFERENTIAL identity (#1029 / #860).
     *
     * The one owner of the identity-based sibling walk: `UiNode.equals` compares text, desc, class,
     * flags and bounds and never identity, so a structural lookup silently resolves a twin — two
     * blank wrapper `View`s in one row are the norm on a flattened render, and DoorDash 8.93.7
     * flattened exactly the rows this is used on. Getting the WRONG index here would under-mask a
     * PII node (`hasPrecedingSiblingText`/`hasFollowingSiblingTextMatchesRegex`, #860/#886) or read
     * money off the wrong slot (`nextSiblingMatchingRegex`, #1029) — so both callers share this.
     *
     * Empty when the tree has no parent back-references wired (`restoreParents`, always called by
     * `AccessibilityNodeMapper` and `UiNodeDto.toDomain`) or this node is last.
     */
    fun followingSiblings(): List<UiNode> {
        val siblings = parent?.children ?: return emptyList()
        val idx = siblings.indexOfFirst { it === this }
        return if (idx >= 0) siblings.subList(idx + 1, siblings.size) else emptyList()
    }

    /** The sibling immediately BEFORE this node — [followingSiblings]' mirror, same identity walk. */
    fun precedingSibling(): UiNode? {
        val siblings = parent?.children ?: return null
        val idx = siblings.indexOfFirst { it === this }
        return if (idx > 0) siblings[idx - 1] else null
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
    //  SCRUBBABLE STRING FIELDS (#835)
    //  The privacy-side enumeration of this node's serialized string values.
    //  Deliberately SEPARATE from the recognition-side [allText] — see the
    //  [UiNodeTextField] KDoc for why the two must not be merged.
    // ========================================================================

    /**
     * This node's own scrubbable string values, paired with their field identity
     * (#835). The SSOT every scrub/redact/scan site iterates instead of
     * hand-listing `text` + `contentDescription` — a string field added to this
     * class is covered by every reader the moment it joins [UiNodeTextField],
     * rather than silently missing a scrub site (which is exactly how
     * [stateDescription] shipped outside every layer).
     *
     * Values are returned RAW (nulls and blanks included) so a caller can decide
     * its own emptiness policy; [allScrubbableText] is the non-blank tree-wide
     * collection.
     */
    fun scrubbableStrings(): List<Pair<UiNodeTextField, String?>> = listOf(
        UiNodeTextField.TEXT to text,
        UiNodeTextField.CONTENT_DESCRIPTION to contentDescription,
        UiNodeTextField.STATE_DESCRIPTION to stateDescription,
        UiNodeTextField.PANE_TITLE to paneTitle,
        UiNodeTextField.ROLE_DESCRIPTION to roleDescription,
        UiNodeTextField.HINT_TEXT to hintText,
        UiNodeTextField.TOOLTIP_TEXT to tooltipText,
        UiNodeTextField.ERROR_TEXT to errorText,
        UiNodeTextField.CLICK_ACTION_LABEL to clickActionLabel,
        UiNodeTextField.UNIQUE_ID to uniqueId,
    )

    /**
     * A copy of THIS node (children untouched) with every scrubbable string
     * rewritten through [transform] (#835). The write-side twin of
     * [scrubbableStrings] and the ONE place the fields are enumerated for
     * writing — a masker/scrubber that uses it cannot forget a field.
     *
     * [transform] receives each value as-is (null included) and returns the
     * replacement; returning the argument leaves the field untouched. Note the
     * returned copy has no wired [parent] (as with any `copy`), which the
     * envelope serializers ignore.
     */
    fun mapScrubbableStrings(transform: (String?) -> String?): UiNode = copy(
        text = transform(text),
        contentDescription = transform(contentDescription),
        stateDescription = transform(stateDescription),
        paneTitle = transform(paneTitle),
        roleDescription = transform(roleDescription),
        hintText = transform(hintText),
        tooltipText = transform(tooltipText),
        errorText = transform(errorText),
        clickActionLabel = transform(clickActionLabel),
        uniqueId = transform(uniqueId),
    )

    /**
     * Every non-blank scrubbable string in this subtree (#835) — the privacy-side counterpart of
     * [allText], which recognition owns.
     *
     * TWO segments (#1147 review X1): first the PRE-#1147 projection — the
     * [UiNodeTextField.LEGACY_SCAN_ORDER] fields of every node, DFS, byte-for-byte what this returned
     * before — then the newer fields of every node, DFS. `SensitiveTextMarkers` joins this list and
     * relies on sibling ADJACENCY ("Transfer" | "$45.66"); interleaving a new field between two
     * legacy values would silently break such a detection, and widening the scrub layer must never
     * weaken one. A marker riding a new field is still in the stream (second segment).
     *
     * Deliberately NOT memoized: it is built once per capture envelope at the
     * tree root, whereas [allText] is read per node per rule, so a `by lazy`
     * field here would cost memory on every node for a value almost none of
     * them are asked for.
     */
    fun allScrubbableText(): List<String> {
        // Review Z5: ONE traversal into two segments; the frozen ordering is the concatenation.
        val legacy = mutableListOf<String>()
        val fresh = mutableListOf<String>()
        collectScrubbableText(this, legacy, fresh)
        return legacy + fresh
    }

    private fun collectScrubbableText(node: UiNode, legacy: MutableList<String>, fresh: MutableList<String>) {
        for ((field, value) in node.scrubbableStrings()) {
            if (value.isNullOrBlank()) continue
            if (field in UiNodeTextField.LEGACY_SCAN_ORDER) legacy.add(value) else fresh.add(value)
        }
        node.children.forEach { collectScrubbableText(it, legacy, fresh) }
    }

    // ========================================================================
    //  DATA COLLECTION (Lazy)
    // ========================================================================

    /**
     * Collects all text and content descriptions from the entire tree.
     * Used primarily by Screen Recognizers.
     *
     * RECOGNITION SSOT — deliberately EXCLUDES [stateDescription] (#835) and the #1147
     * fields ([paneTitle], [clickActionLabel], …). Rules
     * match on this list, so folding a new field in would shift classification
     * corpus-wide; the privacy layers read [allScrubbableText] instead.
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

    /**
     * The subtree's [allText] joined on the `\u001F` unit separator and lowercased
     * once (Locale.ROOT), memoized (#293 item 9). The `allText*` tree predicates
     * rebuilt this string per rule per event; this node is immutable so a single
     * `by lazy` is the SSOT the compiler's match closures read. Locale.ROOT keeps
     * the fold locale-independent (#187/#211), matching the compiler's search-text
     * side (both lowercase with Locale.ROOT).
     */
    val allTextLowerJoined: String by lazy {
        allText.joinToString("\u001F").lowercase(Locale.ROOT)
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
        val id = node.viewIdResourceName?.substringAfter("id/") ?: NO_ID_FALLBACK
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

/**
 * The string fields a [UiNode] carries into a capture envelope (#835) — the
 * screen-node analogue of `NotifTextField` (#666), and the SSOT every scrub /
 * redact / PII-scan site enumerates via [UiNode.scrubbableStrings] /
 * [UiNode.mapScrubbableStrings] / [UiNode.allScrubbableText].
 *
 * Why it exists: `stateDescription` is captured (SDK ≥ R) and serialized as
 * `"state"`, but every scrub layer hand-listed `text` + `contentDescription`, so
 * a third-party view that mirrors its label into `stateDescription` (toggles,
 * sliders, custom controls do) would have shipped it verbatim on both the
 * recognized and the UNKNOWN path. Enumerating once makes the NEXT added field a
 * compile-visible gap instead of a silent leak channel.
 *
 * This is a PRIVACY enumeration, not a recognition one: [UiNode.allText] (what
 * rules match on) is deliberately narrower and stays untouched, so widening
 * scrub coverage can never move a classification.
 *
 * [wire] is the capture-envelope JSON key (`UiNodeDto`'s `@SerialName`), so
 * JSON-level tooling (the corpus `SnapshotRedactor`) keys off this list too.
 * Annotation arguments must be compile-time constants, so `UiNodeDto` cannot
 * reference these directly — `UiNodeScrubbableFieldsTest` pins the two in sync.
 */
enum class UiNodeTextField(val wire: String) {
    TEXT("text"),
    CONTENT_DESCRIPTION("desc"),
    STATE_DESCRIPTION("state"),
    // #1147 — the TalkBack-study strings. Each is scrubbed/redacted/scanned like `text` the moment it
    // is listed here; none is in [UiNode.allText].
    PANE_TITLE("pane"),
    ROLE_DESCRIPTION("role"),
    HINT_TEXT("hint"),
    TOOLTIP_TEXT("tooltip"),
    ERROR_TEXT("error"),
    CLICK_ACTION_LABEL("clickLabel"),
    UNIQUE_ID("uid"),
    ;

    companion object {
        /**
         * #1147 review X1 — the fields (and their order) [UiNode.allScrubbableText] projected before
         * #1147. FROZEN: its first segment is exactly this projection, so every adjacency the
         * sensitive-marker scan relied on survives any later field addition.
         */
        val LEGACY_SCAN_ORDER: List<UiNodeTextField> = listOf(TEXT, CONTENT_DESCRIPTION, STATE_DESCRIPTION)

        fun fromWire(wire: String): UiNodeTextField? = entries.firstOrNull { it.wire == wire }
    }
}

// ============================================================================
//  Stable hash helpers (package-private)
// ============================================================================

private fun computeStableHash(node: UiNode): Int {
    // Anonymous wrapper: no ID, generic class — hash through to children only. The predicate is the ONE
    // shared with the census fingerprint (ADR-0011 §8); this algorithm (fold, never splice) is unchanged,
    // pinned by UiNodeStableHashPinTest.
    if (AnonymousWrappers.isAnonymousWrapper(node.className, node.viewIdResourceName)) {
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
