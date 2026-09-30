package cloud.trotter.dashbuddy.core.pipeline.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import cloud.trotter.dashbuddy.core.pipeline.accessibility.event.type.window.content_changed.ContentChangedPipeline
import cloud.trotter.dashbuddy.core.pipeline.accessibility.event.type.window.state_changed.StateChangedPipeline
import cloud.trotter.dashbuddy.core.pipeline.PipelineStats
import cloud.trotter.dashbuddy.core.pipeline.accessibility.input.AccEvent
import cloud.trotter.dashbuddy.core.pipeline.accessibility.input.AccessibilitySource
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.spy
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * PR #1155 reviews FF1/HH1/HH4 — which window is ACTIVE on the event path: one enumeration decides,
 * the single `rootInActiveWindow` fallback, and a throwing enumeration.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ActiveResolutionTest : WindowResolverTestBase() {

    @Test
    fun `FF1 - no flagged active window - the fallback reads rootInActiveWindow with no overlay scan`() = bothKinds { kind ->
        val dd = node(ddPkg, "dd", windowId = 3)
        val uber = node(uberPkg, "uber-offer")
        val h = harness(activeRoot = dd, windows = listOf(window(3, 5, dd), uberOverlay(9, 9, uber)))

        assertEquals(listOf("dd"), collect(h, kind, windowId = 9, pkg = uberPkg).map { it.tree.text })
        assertEquals(0L, h.stats.overlayScanCount())
        assertEquals(0L, h.stats.overlaySnapshotCount())
    }

    @Test
    fun `FF1 - rootInActiveWindow disagrees with the enumeration - the enumeration decides, nothing reconciled`() = bothKinds { kind ->
        // rootInActiveWindow says window 42 (not enumerated); the enumeration flags window 3 — its
        // root is the active root, and the overlay above it (same list) is the frame.
        val stray = node(ddPkg, "dd-stray", windowId = 42)
        val h = harness(
            activeRoot = stray,
            windows = listOf(window(3, 5, node(ddPkg, "dd-other"), active = true), uberOverlay(9, 9, node(uberPkg, "uber-offer"))),
        )

        assertEquals(listOf("uber-offer"), collect(h, kind, windowId = 9, pkg = uberPkg).map { it.tree.text })
        verify(h.service, never()).rootInActiveWindow
    }

    @Test
    fun `FF1 - the enumeration flags our bubble - a LOWER overlay is never returned, the front DoorDash is read`() = bothKinds { kind ->
        // rootInActiveWindow still says DoorDash (window 3, layer 12); the enumeration flags our bubble
        // (layer 20). The overlay (layer 9) is BELOW DoorDash: the bubble path's front walk reads DoorDash.
        val dd = node(ddPkg, "dd", windowId = 3)
        val bubble = node(ownPkg, "bubble")
        val uber = node(uberPkg, "uber-offer")
        val h = harness(
            activeRoot = dd,
            windows = listOf(window(1, 20, bubble, active = true), window(3, 12, dd), uberOverlay(9, 9, uber)),
        )

        assertEquals(listOf("dd"), collect(h, kind, windowId = 9, pkg = uberPkg).map { it.tree.text })
        assertEquals(0L, h.stats.overlaySnapshotCount())
    }

    @Test
    fun `HH4 - a throwing enumeration is not an empty list - bubble active - refused FRONT_UNREADABLE, not NO_CANDIDATE`() {
        val bubble = node(ownPkg, "bubble")
        val h = harness(activeRoot = bubble, windows = listOf(window(1, 10, bubble, active = true)))
        whenever(h.service.windows).thenThrow(IllegalStateException("binder died"))

        assertTrue(collect(h, Kind.STATE).isEmpty())
        h.skipped(ForegroundSkipReason.FRONT_UNREADABLE)
        assertEquals(0L, h.stats.foregroundSkipCount(ForegroundSkipReason.NO_CANDIDATE))
    }
}
