package cloud.trotter.dashbuddy.core.pipeline.accessibility.mapper

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #363 — bounded ingestion of untrusted third-party trees. The budget is the
 * pure decision core the AccessibilityNodeMapper applies during conversion
 * (the mapper itself needs live AccessibilityNodeInfo binder objects, so the
 * cap LOGIC is what unit tests pin).
 */
class TreeBudgetTest {

    @Test
    fun `admits everything inside both limits`() {
        val budget = TreeBudget(maxDepth = 5, maxNodes = 10)
        repeat(10) { assertTrue(budget.admit(depth = it % 6)) }
        assertFalse(budget.truncated)
    }

    @Test
    fun `rejects past max depth and flags truncation`() {
        val budget = TreeBudget(maxDepth = 3, maxNodes = 100)
        assertTrue(budget.admit(depth = 3))
        assertFalse(budget.admit(depth = 4))
        assertTrue(budget.truncated)
        // Shallower siblings continue to be admitted — only the deep branch is cut.
        assertTrue(budget.admit(depth = 2))
    }

    @Test
    fun `rejects past max nodes and flags truncation`() {
        val budget = TreeBudget(maxDepth = 100, maxNodes = 3)
        assertTrue(budget.admit(0))
        assertTrue(budget.admit(1))
        assertTrue(budget.admit(1))
        assertFalse(budget.admit(1))
        assertTrue(budget.truncated)
    }

    @Test
    fun `production limits carry margin over the golden corpus`() {
        // Corpus stats at cap-selection time: max depth 27, max nodes 327.
        assertTrue(TreeBudget.MAX_TREE_DEPTH >= 27 * 2)
        assertTrue(TreeBudget.MAX_TREE_NODES >= 327 * 10)
    }
}
