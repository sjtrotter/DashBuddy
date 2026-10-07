package cloud.trotter.dashbuddy.core.pipeline

import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.domain.privacy.PiiShapes

/**
 * #1116 — the UNKNOWN-only, rule-independent **address-block backstop**.
 *
 * A customer address can reach an UNKNOWN capture with no lead-in prefix and no view id: DoorDash's
 * Timeline order-detail sheet on 8.97.8 / 8.99.20 rendered an id-less street line, a `City, ST ZIP`
 * line, the customer's quoted note and a bare door code, and none of the [CustomerTextMarkers] scans
 * (text prefix, id suffix, text input) can see them. Widening a recognition rule to own that frame was
 * tried (PR #1265, closed) and every review round found a different frame whose recognition the wider
 * anchor moved; recognition ownership and privacy coverage have to be independent. This detector is
 * that independent layer: it reads only text shapes, so no rule, platform, id, `Close sheet` or task
 * line enables or disables it.
 *
 * **What it selects** (on a temporary structural PROJECTION — branches with no non-blank
 * [UiNode.scrubbableStrings] dropped, field-less single-child wrappers collapsed — while the original
 * tree is what gets serialized):
 *  1. within one projected sibling list, a street line ([PiiShapes.isStreetLine]) followed by a
 *     `City, ST ZIP` line ([PiiShapes.isCityStateZipLine]); only quote-led notes and short codes may
 *     stand between them. A street line followed by a city line inside one field (or a node carrying
 *     both shapes) is a pair too;
 *  2. the pair's SCOPE: its projected parent, extended ONCE to the grandparent when the parent holds
 *     nothing but the pair and quote/code nodes; every address-shaped and every quote/code node
 *     ([PiiShapes.isQuoteLeading], [PiiShapes.isShortCode]) inside the scope is selected, wherever it
 *     sits;
 *  3. a quote/code node immediately followed by a city line (the weaker cluster): both are selected,
 *     the code is never read as a street.
 * The caller plain-masks every non-empty field of a selected node to `[redacted]` — plain, never
 * hashed, because a short code, ZIP or note hashed in a bounded alphabet can be inverted, and an
 * UNKNOWN capture has no customer-correlation use.
 *
 * **Not claimed:** unquoted, unmarked free text outside an address block; a lone street or city line;
 * a city line ahead of its street; a single-line full address; a tapped node isolated from its address
 * block; recognized frames (the rule's redact owns those, and this never runs there).
 *
 * **Cost:** a constant number of linear passes over the tree (flatten, flag, project, scan sibling
 * lists, mark scopes as preorder intervals through one difference array) — O(N) time and storage for
 * the mapper's bounded trees, each field tested at most once per shape with a cheap first/last-character
 * reject before any regex; the "parent holds only the block" test counts each sibling list once, never
 * per pair. [Selection.steps] counts node visits so the test pins the bound without a stopwatch. No search, sort, I/O or rule matching. Selection is by PREORDER INDEX, never
 * `UiNode.equals` (structurally equal rows are distinct nodes).
 */
object UnknownAddressBackstop {

    /** The selected nodes of one tree, by preorder index (root = 0, children in order). */
    class Selection internal constructor(
        private val selected: BooleanArray,
        val count: Int,
        /** Node visits this selection cost — the linear-cost pin (`UnknownAddressBackstopTest`). */
        internal val steps: Long = 0,
    ) {
        fun isEmpty(): Boolean = count == 0

        operator fun contains(preorderIndex: Int): Boolean =
            preorderIndex in selected.indices && selected[preorderIndex]

        companion object {
            val EMPTY = Selection(BooleanArray(0), 0)
        }
    }

    /** The address-block nodes of [tree] to plain-mask; an empty [Selection] when there is none. */
    fun select(tree: UiNode): Selection = Pass(tree).run()

    private class Pass(root: UiNode) {
        private val n = countNodes(root)
        private val parent = IntArray(n)
        private val end = IntArray(n)
        private var next = 0

        // Per-node shape flags from the node's OWN non-blank fields.
        private val hasField = BooleanArray(n)
        private val street = BooleanArray(n)
        private val city = BooleanArray(n)
        private val quoteOrCode = BooleanArray(n)
        private val nodePair = BooleanArray(n)

        // Subtree facts, filled in one reverse pass.
        private val fieldInSubtree = BooleanArray(n)
        private val fieldChild = BooleanArray(n)

        // Projection: the projected parent of each projected node (-1 = root / not projected).
        private val projParent = IntArray(n) { -1 }

        private val explicit = BooleanArray(n)
        private val scopeDelta = IntArray(n + 1)
        private var scopes = 0
        private var steps = 0L

        init {
            flatten(root, -1)
        }

        private fun flatten(node: UiNode, parentIndex: Int) {
            val index = next++
            steps++
            parent[index] = parentIndex
            classify(index, node)
            for (child in node.children) flatten(child, index)
            end[index] = next
        }

        private fun classify(index: Int, node: UiNode) {
            for ((_, value) in node.scrubbableStrings()) {
                if (value.isNullOrBlank()) continue
                hasField[index] = true
                if (PiiShapes.isQuoteLeading(value) || PiiShapes.isShortCode(value)) quoteOrCode[index] = true
                if (PiiShapes.isStreetLine(value)) street[index] = true
                if (PiiShapes.isCityStateZipLine(value)) city[index] = true
                if ('\n' in value && hasStreetThenCityLine(value)) nodePair[index] = true
            }
            if (street[index] && city[index]) nodePair[index] = true
        }

        private fun hasStreetThenCityLine(value: String): Boolean {
            var previousWasStreet = false
            for (line in value.lineSequence()) {
                if (previousWasStreet && PiiShapes.isCityStateZipLine(line)) return true
                previousWasStreet = PiiShapes.isStreetLine(line)
            }
            return false
        }

        fun run(): Selection {
            for (i in 0 until n) fieldInSubtree[i] = hasField[i]
            for (i in n - 1 downTo 1) {
                steps++
                if (fieldInSubtree[i]) {
                    fieldInSubtree[parent[i]] = true
                    fieldChild[parent[i]] = true
                }
            }
            if (!fieldInSubtree[0]) return Selection(BooleanArray(0), 0, steps)

            val stack = ArrayDeque<Int>()
            stack.addLast(collapse(0))
            val kids = ArrayList<Int>()
            while (stack.isNotEmpty()) {
                val p = stack.removeLast()
                kids.clear()
                var c = p + 1
                while (c < end[p]) {
                    steps++
                    if (fieldInSubtree[c]) kids.add(collapse(c))
                    c = end[c]
                }
                for (k in kids) {
                    projParent[k] = p
                    stack.addLast(k)
                }
                scanSiblings(p, kids)
            }
            return mark()
        }

        /** Descends through field-less wrappers that keep exactly one field-bearing child. */
        private fun collapse(start: Int): Int {
            var i = start
            while (!hasField[i]) {
                var only = -1
                var c = i + 1
                while (c < end[i]) {
                    steps++
                    if (fieldInSubtree[c]) {
                        if (only >= 0) return i
                        only = c
                    }
                    c = end[c]
                }
                if (only < 0) return i
                i = only
            }
            return i
        }

        private fun isIntervening(k: Int) = quoteOrCode[k] && !fieldChild[k]

        private fun scanSiblings(p: Int, kids: List<Int>) {
            // Siblings that are neither quote nor code: a pair's parent "holds nothing but the block"
            // when the pair accounts for all of them — counted once per list, never per pair.
            var others = 0
            for (k in kids) if (!quoteOrCode[k]) others++
            var streetCandidate = -1
            for (j in kids.indices) {
                steps++
                val k = kids[j]
                when {
                    nodePair[k] -> {
                        addScope(p, others, k, k)
                        streetCandidate = -1
                    }
                    city[k] -> {
                        if (streetCandidate >= 0) {
                            addScope(p, others, streetCandidate, k)
                        } else if (j > 0 && quoteOrCode[kids[j - 1]]) {
                            explicit[kids[j - 1]] = true
                            explicit[k] = true
                        }
                        streetCandidate = -1
                    }
                    street[k] -> streetCandidate = k
                    isIntervening(k) -> Unit
                    else -> streetCandidate = -1
                }
            }
        }

        private fun addScope(p: Int, others: Int, a: Int, b: Int) {
            explicit[a] = true
            explicit[b] = true
            var pairOthers = if (quoteOrCode[a]) 0 else 1
            if (b != a && !quoteOrCode[b]) pairOthers++
            val onlyBlock = others == pairOthers
            val scope = if (onlyBlock && projParent[p] >= 0) projParent[p] else p
            scopeDelta[scope]++
            scopeDelta[end[scope]]--
            scopes++
        }

        private fun mark(): Selection {
            val selected = BooleanArray(n)
            var count = 0
            var depth = 0
            for (i in 0 until n) {
                steps++
                depth += scopeDelta[i]
                val inScope = scopes > 0 && depth > 0
                if (explicit[i] || (inScope && (quoteOrCode[i] || street[i] || city[i] || nodePair[i]))) {
                    selected[i] = true
                    count++
                }
            }
            return Selection(selected, count, steps)
        }

        private companion object {
            fun countNodes(node: UiNode): Int {
                var total = 1
                for (child in node.children) total += countNodes(child)
                return total
            }
        }
    }
}
