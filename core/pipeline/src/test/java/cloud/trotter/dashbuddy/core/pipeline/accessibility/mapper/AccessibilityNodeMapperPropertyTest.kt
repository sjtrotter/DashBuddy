package cloud.trotter.dashbuddy.core.pipeline.accessibility.mapper

import android.view.accessibility.AccessibilityNodeInfo
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import io.kotest.property.Arb
import io.kotest.property.RandomSource
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/**
 * #590 — BOUNDED INGESTION of untrusted third-party accessibility trees, as a
 * property over generated mock node graphs. Generalizes the example-based
 * [TreeBudgetTest] onto the real [toUiNode] mapper.
 *
 * Third-party trees are untrusted input: every `getChild(i)` is a binder IPC and
 * the converted tree is serialized into the capture envelope, so a pathological or
 * redesigned target UI must not be able to stack-overflow the service, balloon
 * captures, or spin unbounded IPC. For ANY mock node graph the mapper must hold:
 *  1. resulting **depth ≤ [TreeBudget.MAX_TREE_DEPTH]** (deep chains truncated);
 *  2. resulting **node count ≤ [TreeBudget.MAX_TREE_NODES]** (wide/dense fans truncated);
 *  3. every mapped **text/desc/state length ≤ [TreeBudget.MAX_TEXT_LENGTH]**;
 *  4. it **never throws** on hostile input;
 *  5. total **`getChild()` IPC is bounded by the caps** — a node reporting a huge
 *     `childCount` cannot force one IPC per reported child.
 *
 * Red-first observations (pre-fix):
 *  - (3) NO text-size cap existed — a 100 000-char text node mapped verbatim, so the
 *    100k string rode straight into the [UiNode] and the capture envelope. `RED`.
 *  - (5) the child loop ran `for (i in 0 until childCount)` with NO breadth
 *    short-circuit after the budget was exhausted — a node reporting `childCount =
 *    50 000` triggered 50 000 `getChild()` binder calls even though only ~4 000 nodes
 *    could ever be admitted. `RED`.
 * Depth (1) and node-count (2) caps already existed and are regression-guarded here.
 *
 * Pinned to SDK 36: the mapper reads the tri-state `getChecked():int` (API 36) and
 * `getStateDescription()` (API 30), so the Robolectric shadow must supply both.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AccessibilityNodeMapperPropertyTest {

    // ---- Mock AccessibilityNodeInfo factory (counts getChild IPC) ---------------

    /**
     * Build a mock node with fixed [children]. `getChild(i)` returns the i-th child
     * (or null out of range) and increments [ipc]. All other accessors default
     * (null text/id/class, false flags), overridden per-arg.
     */
    private fun node(
        ipc: AtomicInteger,
        children: List<AccessibilityNodeInfo> = emptyList(),
        text: String? = null,
        desc: String? = null,
        state: String? = null,
    ): AccessibilityNodeInfo {
        val m = mock<AccessibilityNodeInfo>()
        whenever(m.childCount).thenReturn(children.size)
        whenever(m.getChild(anyInt())).thenAnswer { inv ->
            ipc.incrementAndGet()
            children.getOrNull(inv.getArgument(0))
        }
        whenever(m.text).thenReturn(text)
        whenever(m.contentDescription).thenReturn(desc)
        whenever(m.stateDescription).thenReturn(state)
        return m
    }

    /**
     * A node that REPORTS [fanCount] children but hands the SAME shared [leaf] for
     * every index — models a hostile `childCount` without materializing millions of
     * mocks. Total `getChild` calls == the IPC the mapper actually issues.
     */
    private fun wideFan(ipc: AtomicInteger, fanCount: Int, leaf: AccessibilityNodeInfo): AccessibilityNodeInfo {
        val m = mock<AccessibilityNodeInfo>()
        whenever(m.childCount).thenReturn(fanCount)
        whenever(m.getChild(anyInt())).thenAnswer {
            ipc.incrementAndGet()
            leaf
        }
        whenever(m.text).thenReturn("root")
        return m
    }

    /** Eager deep chain of [depth] single-child nodes (cheap; each node is one mock). */
    private fun chain(ipc: AtomicInteger, depth: Int, text: String? = "n"): AccessibilityNodeInfo {
        var cur = node(ipc, text = text)
        repeat(depth) { cur = node(ipc, children = listOf(cur), text = text) }
        return cur
    }

    // ---- Tree measurements ------------------------------------------------------

    private fun maxDepthIndex(n: UiNode, d: Int = 0): Int =
        if (n.children.isEmpty()) d else n.children.maxOf { maxDepthIndex(it, d + 1) }

    private fun allNodes(n: UiNode): Sequence<UiNode> =
        sequenceOf(n) + n.children.asSequence().flatMap { allNodes(it) }

    private fun assertWithinCaps(root: UiNode?) {
        if (root == null) return
        val nodes = allNodes(root).toList()
        assertTrue(
            "depth ${maxDepthIndex(root)} exceeds MAX_TREE_DEPTH=${TreeBudget.MAX_TREE_DEPTH}",
            maxDepthIndex(root) <= TreeBudget.MAX_TREE_DEPTH,
        )
        assertTrue(
            "node count ${nodes.size} exceeds MAX_TREE_NODES=${TreeBudget.MAX_TREE_NODES}",
            nodes.size <= TreeBudget.MAX_TREE_NODES,
        )
        for (n in nodes) {
            n.text?.let {
                assertTrue(
                    "text length ${it.length} exceeds MAX_TEXT_LENGTH=${TreeBudget.MAX_TEXT_LENGTH}",
                    it.length <= TreeBudget.MAX_TEXT_LENGTH,
                )
            }
            n.contentDescription?.let {
                assertTrue("desc length ${it.length} exceeds MAX_TEXT_LENGTH", it.length <= TreeBudget.MAX_TEXT_LENGTH)
            }
            n.stateDescription?.let {
                assertTrue("state length ${it.length} exceeds MAX_TEXT_LENGTH", it.length <= TreeBudget.MAX_TEXT_LENGTH)
            }
        }
    }

    /** Generous-but-finite IPC bound: every admitted node examines at most its own
     * children before the budget short-circuit, and admitted nodes ≤ MAX_TREE_NODES. */
    private val ipcBound = TreeBudget.MAX_TREE_NODES + TreeBudget.MAX_TREE_DEPTH + 16

    // ---- Adversarial text pool for the generated trees --------------------------

    private fun textArb(rs: RandomSource): String? = when (rs.random.nextInt(12)) {
        0 -> "x".repeat(100_000)                 // (3) oversized — must be capped
        1 -> "y".repeat(4_500)                   // just over the cap
        2 -> null
        3 -> "   "
        4 -> "\uD800 lone-surrogate"
        else -> "node-${rs.random.nextInt(1000)}"
    }

    /** Eager bounded mock tree: depth ≤ [maxDepth], total nodes ≤ [budget]. */
    private fun buildTree(rs: RandomSource, maxDepth: Int, budget: IntArray, ipc: AtomicInteger): AccessibilityNodeInfo {
        budget[0]--
        val childCount = if (maxDepth <= 0 || budget[0] <= 0) 0 else rs.random.nextInt(4)
        val children = (0 until childCount).mapNotNull {
            if (budget[0] <= 0) null else buildTree(rs, maxDepth - 1, budget, ipc)
        }
        return node(ipc, children = children, text = textArb(rs), desc = textArb(rs))
    }

    /** One generated case: the mock root + the IPC counter it increments. */
    private class TreeCase(val root: AccessibilityNodeInfo, val ipc: AtomicInteger)

    private val treeArb: Arb<TreeCase> = arbitrary { rs ->
        // depth up to 75 (exceeds MAX_TREE_DEPTH=60), materialization budget 120 —
        // kept lean so the Mockito-inline mock graph doesn't pressure the shared worker.
        val ipc = AtomicInteger(0)
        val root = buildTree(rs, maxDepth = 75, budget = intArrayOf(120), ipc = ipc)
        TreeCase(root, ipc)
    }

    // ---- Property: any generated tree stays within every cap, never throws ------

    @Test
    fun `property - generated trees stay within depth-node-text caps, bound IPC, never throw`() = runTest {
        checkAll(200, treeArb) { case ->
            val mapped = case.root.toUiNode()   // must not throw
            assertWithinCaps(mapped)
            assertTrue(
                "getChild IPC ${case.ipc.get()} exceeds bound $ipcBound",
                case.ipc.get() <= ipcBound,
            )
        }
    }

    // ---- Targeted adversarial cases (crisp red-first evidence) -------------------

    @Test
    fun `oversized text node is truncated to the text cap`() {
        val ipc = AtomicInteger(0)
        val root = node(
            ipc,
            text = "x".repeat(100_000),
            desc = "d".repeat(100_000),
            state = "s".repeat(100_000),
        )
        assertWithinCaps(root.toUiNode())
    }

    @Test
    fun `hostile childCount does not force one IPC per reported child`() {
        val ipc = AtomicInteger(0)
        val leaf = node(ipc, text = "leaf")
        val root = wideFan(ipc, fanCount = 50_000, leaf = leaf)
        val mapped = root.toUiNode()
        assertWithinCaps(mapped)
        assertTrue(
            "getChild IPC ${ipc.get()} for a 50 000-fan node is not bounded by the caps " +
                "(bound=$ipcBound) — the breadth short-circuit is missing",
            ipc.get() <= ipcBound,
        )
    }

    @Test
    fun `deep chain is truncated at the depth cap`() {
        val ipc = AtomicInteger(0)
        val mapped = chain(ipc, depth = 200).toUiNode()
        assertWithinCaps(mapped)
        assertTrue(
            "depth ${maxDepthIndex(mapped!!)} should be capped at MAX_TREE_DEPTH",
            maxDepthIndex(mapped) <= TreeBudget.MAX_TREE_DEPTH,
        )
    }

    @Test
    fun `null root maps to null`() {
        val src: AccessibilityNodeInfo? = null
        assertTrue(src.toUiNode() == null)
    }
}
