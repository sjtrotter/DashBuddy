package cloud.trotter.dashbuddy.core.pipeline.accessibility

import android.util.Log
import cloud.trotter.dashbuddy.core.pipeline.accessibility.input.AccessibilitySource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import timber.log.Timber

/** #1164: deterministic mapper linkage failures cost frames, never a supervised restart loop. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AccessibilitySourceLinkageTest : WindowResolverTestBase() {

    @Test
    fun `both snapshot seams drop linkage failures and WARN once across source instances`() {
        val root = node(ddPkg, "tree text must never be logged")
        // toUiNode's first framework property read, before any tree conversion.
        whenever(root.packageName).thenThrow(NoSuchMethodError("missing framework method"))
        val w = window(3, 1, root)
        val h = harness(root, listOf(w))
        assertEquals(0L, h.stats.mapperLinkageRefusalCount)
        assertFalse(h.stats.summary().contains("mapperLinkageRefusals"))

        val entries = mutableListOf<LogEntry>()
        val recorder = object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                entries += LogEntry(priority, tag, message, t)
            }
        }
        Timber.plant(recorder)
        try {
            assertNull(h.source.getCurrentRootSnapshot())
            assertEquals(1L, h.stats.mapperLinkageRefusalCount)
            // Recreating the source must not re-arm the process-wide WARN gate.
            assertNull(AccessibilitySource(h.stats).getWindowSnapshot(w, root, totalWindowCount = 1))
            assertEquals(2L, h.stats.mapperLinkageRefusalCount)
            assertTrue(h.stats.summary().contains(" mapperLinkageRefusals=2"))
            assertEquals(
                listOf(LogEntry(Log.WARN, "Pipeline", "Accessibility mapper refused a frame (NoSuchMethodError: missing framework method); frames are dropped and counted (#1164)", null)),
                entries,
            )
        } finally {
            Timber.uproot(recorder)
        }
    }

    @Test
    fun `ordinary mapping exceptions still drop frames without linkage counts`() {
        val root = node(ddPkg, "Offer")
        whenever(root.packageName).thenThrow(IllegalStateException("unreadable"))
        val w = window(3, 1, root)
        val h = harness(root, listOf(w))

        assertNull(h.source.getCurrentRootSnapshot())
        assertNull(h.source.getWindowSnapshot(w, root, totalWindowCount = 1))
        assertEquals(0L, h.stats.mapperLinkageRefusalCount)
    }

    @Test
    fun `other Errors still propagate through both snapshot seams`() {
        for (error in listOf(OutOfMemoryError("heap exhausted"), AssertionError("broken invariant"))) {
            val root = node(ddPkg, "Offer")
            whenever(root.packageName).thenThrow(error)
            val w = window(3, 1, root)
            val h = harness(root, listOf(w))

            assertSame(error, assertThrows(Error::class.java) { h.source.getCurrentRootSnapshot() })
            assertSame(error, assertThrows(Error::class.java) {
                h.source.getWindowSnapshot(w, root, totalWindowCount = 1)
            })
            assertEquals(0L, h.stats.mapperLinkageRefusalCount)
        }
    }

    private data class LogEntry(val priority: Int, val tag: String?, val message: String, val error: Throwable?)
}
