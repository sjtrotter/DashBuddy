package cloud.trotter.dashbuddy.core.pipeline.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import cloud.trotter.dashbuddy.core.pipeline.PipelineStats
import cloud.trotter.dashbuddy.core.pipeline.accessibility.event.type.window.content_changed.ContentChangedPipeline
import cloud.trotter.dashbuddy.core.pipeline.accessibility.event.type.window.state_changed.StateChangedPipeline
import cloud.trotter.dashbuddy.core.pipeline.accessibility.event.type.window.windows_changed.WindowsChangedPipeline
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
 * #1148 review G6/H1 — the topology path emits TRUE OVERLAYS only: enabled-package APPLICATION
 * windows ABOVE the active window. A window beneath the active one — the activity under a DoorDash
 * sheet — is never emitted (that re-opened the F1 interleaving). #1152 D6: a platform offer overlay
 * (a11y `TYPE_SYSTEM`, size + package) above the active window is emitted too; every other
 * system-layer window (the puck, the status bar, the shade, a DoorDash toast) never is. The
 * shared-fixture cases (review FF1/GG1/HH1, moved here by HH7) assert the topology path and the event
 * path agree on ONE window list.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WindowsChangedOverlayTest : WindowResolverTestBase() {

    private fun system(windowId: Int, windowLayer: Int, root: AccessibilityNodeInfo?, bounds: Rect) =
        window(windowId, windowLayer, root, windowType = AccessibilityWindowInfo.TYPE_SYSTEM, bounds = bounds)

    private val stats = PipelineStats()

    private fun emitted(windows: List<AccessibilityWindowInfo>, enabled: Set<String>): List<TreeSnapshot> {
        val res = displayResources()
        val service = mock<AccessibilityService> {
            on { this.windows } doReturn windows
            on { packageName } doReturn "cloud.trotter.dashbuddy"
            on { resources } doReturn res
        }
        val source = AccessibilitySource(stats).apply { registerService(service) }
        val pipeline = WindowsChangedPipeline(source, FakePlatformPreferences(enabled), stats)
        val out = mutableListOf<TreeSnapshot>()
        runTest {
            val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                pipeline.output().collect { out += it }
            }
            advanceUntilIdle()
            @Suppress("DEPRECATION")
            source.emit(AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOWS_CHANGED))
            advanceTimeBy(1_000)
            runCurrent()
            job.cancel()
        }
        return out
    }

    @Test
    fun `a DoorDash sheet active over its activity - nothing emitted`() {
        val out = emitted(
            listOf(window(7, 5, node(ddPkg, "sheet"), active = true), window(3, 2, node(ddPkg, "activity"))),
            enabled = setOf(ddPkg, uberPkg),
        )
        assertTrue("the activity beneath the active sheet is never emitted", out.isEmpty())
    }

    @Test
    fun `DD3 - an enabled application window above an ENABLED active window is NOT emitted`() {
        // The event path owns the enabled active window and never reads a non-active application
        // window above it; emitting it here would interleave the two.
        val out = emitted(
            listOf(
                window(3, 2, node(ddPkg, "dd"), active = true),
                window(9, 9, node(uberPkg, "uber-app")),
            ),
            enabled = setOf(ddPkg, uberPkg),
        )
        assertTrue(out.isEmpty())
    }

    @Test
    fun `an enabled application window above a NON-enabled active window is emitted (the single front winner)`() {
        val out = emitted(
            listOf(
                window(3, 2, node("com.android.launcher3", "home"), active = true),
                window(9, 9, node(uberPkg, "uber-app")),
            ),
            enabled = setOf(ddPkg, uberPkg),
        )
        assertEquals(listOf("uber-app"), out.map { it.tree.text })
        assertEquals(TreeSnapshot.Trigger.Reason.WINDOWS, out.single().trigger?.reason)
    }

    @Test
    fun `a DISABLED platform's window above is never emitted`() {
        val out = emitted(
            listOf(
                window(3, 2, node(ddPkg, "dd"), active = true),
                window(9, 9, node(uberPkg, "uber-offer")),
            ),
            enabled = setOf(ddPkg),
        )
        assertTrue(out.isEmpty())
    }

    @Test
    fun `no active window - nothing emitted`() {
        val out = emitted(
            listOf(system(9, 9, node(uberPkg, "uber-offer"), OverlayGeometry.UBER_OFFER)),
            enabled = setOf(ddPkg, uberPkg),
        )
        assertTrue(out.isEmpty())
    }

    @Test
    fun `a small system-layer window is never emitted and never has its root fetched (H1)`() {
        val uberSystem = system(9, 9, node(uberPkg, "uber-puck"), OverlayGeometry.UBER_PUCK)
        val out = emitted(
            listOf(window(3, 2, node(ddPkg, "dd"), active = true), uberSystem),
            enabled = setOf(ddPkg, uberPkg),
        )
        assertTrue(out.isEmpty())
        verify(uberSystem, never()).root
    }

    @Test
    fun `our bubble active - the window in front is emitted, the one beneath it is not (H6)`() {
        val out = emitted(
            listOf(
                window(1, 10, node("cloud.trotter.dashbuddy", "bubble"), active = true),
                window(3, 2, node(ddPkg, "dd")),
                window(5, 5, node(uberPkg, "uber-app")),
            ),
            enabled = setOf(ddPkg, uberPkg),
        )
        assertEquals(listOf("uber-app"), out.map { it.tree.text })
    }

    @Test
    fun `our bubble active and the foreground refused - nothing emitted (H6)`() {
        val out = emitted(
            listOf(
                window(1, 10, node("cloud.trotter.dashbuddy", "bubble"), active = true),
                window(4, 6, node("com.android.launcher3", "home")),
                window(3, 2, node(ddPkg, "dd")),
            ),
            enabled = setOf(ddPkg),
        )
        assertTrue(out.isEmpty())
        // FF4: the topology path's refusals have their OWN census, never foregroundSkip{} (event-path loss).
        assertEquals(1L, stats.topologySkipCount(ForegroundSkipReason.FRONT_NOT_ENABLED))
        assertEquals(0L, stats.foregroundSkipCount(ForegroundSkipReason.FRONT_NOT_ENABLED))
    }

    // --- #1152 D6 --------------------------------------------------------------------------------

    @Test
    fun `an Uber offer overlay above the active DoorDash window is emitted and counted`() {
        val out = emitted(
            listOf(
                window(3, 2, node(ddPkg, "dd"), active = true),
                system(9, 9, node(uberPkg, "uber-offer"), OverlayGeometry.UBER_OFFER),
            ),
            enabled = setOf(ddPkg, uberPkg),
        )
        assertEquals(listOf("uber-offer"), out.map { it.tree.text })
        assertEquals(9, out.single().windowContext?.windowId)
        assertEquals(1L, stats.overlaySnapshotCount())
    }

    @Test
    fun `the Uber puck above the active window is never emitted`() {
        val puck = system(10, 9, node(uberPkg, "uber-puck"), OverlayGeometry.UBER_PUCK)
        val out = emitted(listOf(window(3, 2, node(ddPkg, "dd"), active = true), puck), enabled = setOf(ddPkg, uberPkg))
        assertTrue(out.isEmpty())
        verify(puck, never()).root
    }

    @Test
    fun `a DISABLED overlay platform's overlay is never emitted`() {
        val out = emitted(
            listOf(
                window(3, 2, node(ddPkg, "dd"), active = true),
                system(9, 9, node(uberPkg, "uber-offer"), OverlayGeometry.UBER_OFFER),
            ),
            enabled = setOf(ddPkg),
        )
        assertTrue(out.isEmpty())
        assertEquals(0L, stats.overlaySnapshotCount())
    }

    @Test
    fun `a large system window of a non-overlay package (the shade, a DoorDash toast) is never emitted`() {
        val out = emitted(
            listOf(
                window(3, 2, node(ddPkg, "dd"), active = true),
                system(30, 30, node("com.android.systemui", "shade"), OverlayGeometry.FULL_SCREEN),
                system(11, 9, node(ddPkg, "dd-toast"), OverlayGeometry.UBER_OFFER),
            ),
            enabled = setOf(ddPkg, uberPkg),
        )
        assertTrue(out.isEmpty())
    }

    @Test
    fun `an Uber overlay BENEATH the active window is never emitted`() {
        val out = emitted(
            listOf(
                window(3, 12, node(ddPkg, "dd"), active = true),
                system(9, 9, node(uberPkg, "uber-offer"), OverlayGeometry.UBER_OFFER),
            ),
            enabled = setOf(ddPkg, uberPkg),
        )
        assertTrue(out.isEmpty())
    }

    @Test
    fun `DD3 - a non-active DoorDash sheet above the active DoorDash activity - nothing (the event path reads the activity)`() {
        val out = emitted(
            listOf(window(3, 2, node(ddPkg, "dd-activity"), active = true), window(7, 5, node(ddPkg, "dd-sheet"))),
            enabled = setOf(ddPkg, uberPkg),
        )
        assertTrue(out.isEmpty())
    }

    @Test
    fun `DD3 - activity 2, Uber overlay 5, NON-active DoorDash sheet 9 - nothing emitted (agrees with the event path)`() {
        val out = emitted(
            listOf(
                window(3, 2, node(ddPkg, "dd-activity"), active = true),
                system(9, 5, node(uberPkg, "uber-offer"), OverlayGeometry.UBER_OFFER),
                window(7, 9, node(ddPkg, "dd-sheet")),
            ),
            enabled = setOf(ddPkg, uberPkg),
        )
        assertTrue(out.isEmpty())
    }

    @Test
    fun `our bubble active, an Uber overlay above DoorDash - the overlay is emitted and counted`() {
        val out = emitted(
            listOf(
                window(1, 10, node("cloud.trotter.dashbuddy", "bubble"), active = true),
                system(9, 9, node(uberPkg, "uber-offer"), OverlayGeometry.UBER_OFFER),
                window(3, 2, node(ddPkg, "dd")),
            ),
            enabled = setOf(ddPkg, uberPkg),
        )
        assertEquals(listOf("uber-offer"), out.map { it.tree.text })
        assertEquals(1L, stats.overlaySnapshotCount())
    }

    // --- PR #1155 review round 2 ---------------------------------------------------------------

    @Test
    fun `CC3 - activity, its sheet above, an enabled overlay above both - ONLY the overlay`() {
        val out = emitted(
            listOf(
                window(3, 2, node(ddPkg, "dd-activity"), active = true),
                window(7, 5, node(ddPkg, "dd-sheet")),
                system(9, 9, node(uberPkg, "uber-offer"), OverlayGeometry.UBER_OFFER),
            ),
            enabled = setOf(ddPkg, uberPkg),
        )
        assertEquals(listOf("uber-offer"), out.map { it.tree.text })
    }

    @Test
    fun `CC3 - two stacked enabled overlays - the top one only`() {
        val out = emitted(
            listOf(
                window(3, 2, node(ddPkg, "dd"), active = true),
                system(9, 9, node(uberPkg, "uber-offer-low"), OverlayGeometry.UBER_OFFER),
                system(11, 11, node(uberPkg, "uber-offer-top"), OverlayGeometry.UBER_OFFER),
            ),
            enabled = setOf(ddPkg, uberPkg),
        )
        assertEquals(listOf("uber-offer-top"), out.map { it.tree.text })
    }

    @Test
    fun `CC2 - an unreadable LARGE system window above an enabled overlay is a barrier - nothing emitted`() {
        val overlay = system(9, 9, node(uberPkg, "uber-offer"), OverlayGeometry.UBER_OFFER)
        val out = emitted(
            listOf(
                window(3, 2, node(ddPkg, "dd"), active = true),
                overlay,
                system(12, 12, null, OverlayGeometry.FULL_SCREEN),
            ),
            enabled = setOf(ddPkg, uberPkg),
        )
        assertTrue(out.isEmpty())
        verify(overlay, never()).root
    }

    @Test
    fun `CC2 - an unreadable application window above an enabled overlay is a barrier - nothing emitted`() {
        val out = emitted(
            listOf(
                window(3, 2, node(ddPkg, "dd"), active = true),
                system(9, 9, node(uberPkg, "uber-offer"), OverlayGeometry.UBER_OFFER),
                window(12, 12, null),
            ),
            enabled = setOf(ddPkg, uberPkg),
        )
        assertTrue(out.isEmpty())
    }

    @Test
    fun `CC2 - a SMALL unreadable system window above the overlay is no barrier - the overlay is emitted`() {
        val out = emitted(
            listOf(
                window(3, 2, node(ddPkg, "dd"), active = true),
                system(9, 9, node(uberPkg, "uber-offer"), OverlayGeometry.UBER_OFFER),
                system(12, 12, null, OverlayGeometry.STATUS_BAR),
            ),
            enabled = setOf(ddPkg, uberPkg),
        )
        assertEquals(listOf("uber-offer"), out.map { it.tree.text })
    }

    @Test
    fun `FF1 - two windows flagged active (a transition in flight) - no single active window - nothing emitted`() {
        val out = emitted(
            listOf(
                window(3, 5, node(ddPkg, "dd"), active = true),
                window(4, 6, node(ddPkg, "dd-2"), active = true),
                system(9, 9, node(uberPkg, "uber-offer"), OverlayGeometry.UBER_OFFER),
            ),
            enabled = setOf(ddPkg, uberPkg),
        )
        assertTrue(out.isEmpty())
    }

    // --- Shared-fixture: the topology path agrees with the event path (FF1/GG1/HH1) ---

    @Test
    fun `FF1 - shared fixture - one enumeration, both paths agree on the overlay`() {
        // rootInActiveWindow says window 42 (not enumerated); the enumeration flags window 3 at layer 5
        // with an enabled Uber overlay at 9 above it. Same list → same answer on both paths.
        val stray = node(ddPkg, "dd-stray", windowId = 42)
        val h = harness(
            activeRoot = stray,
            windows = listOf(window(3, 5, node(ddPkg, "dd-other"), active = true), uberOverlay(9, 9, node(uberPkg, "uber-offer"))),
        )

        val eventFrames = Kind.entries.flatMap { collect(h, it, windowId = 9, pkg = uberPkg) }.map { it.tree.text }
        val topologyFrames = collectWith(
            h.events,
            cloud.trotter.dashbuddy.core.pipeline.accessibility.event.type.window.windows_changed
                .WindowsChangedPipeline(h.source, h.prefs, h.stats).output(),
            event(AccessibilityEvent.TYPE_WINDOWS_CHANGED, windowId = -1),
        ).map { it.tree.text }

        assertEquals(listOf("uber-offer", "uber-offer"), eventFrames)
        assertEquals(listOf("uber-offer"), topologyFrames)
        verify(h.service, never()).rootInActiveWindow
    }

    @Test
    fun `GG1, HH1 - unreadable flagged active root, COLD cache, enabled overlay above - BOTH paths yield the overlay`() {
        val dd = node(ddPkg, "dd")
        val activeWindow = window(3, 5, null, active = true)
        val h = harness(activeRoot = dd, windows = listOf(activeWindow, uberOverlay(9, 9, node(uberPkg, "uber-offer"))))

        assertEquals(listOf("uber-offer", "uber-offer"), Kind.entries.flatMap { collect(h, it, windowId = 3) }.map { it.tree.text })
        assertEquals(listOf("uber-offer"), topologyFrames(h).map { it.tree.text })
    }

    @Test
    fun `GG1, HH1 - unreadable flagged active root, WARM cache - the memoized package never bypasses the fresh-root check`() {
        val dd = node(ddPkg, "dd", windowId = 3)
        val activeWindow = window(3, 5, dd, active = true)
        val h = harness(activeRoot = dd, windows = listOf(activeWindow, uberOverlay(9, 9, node(uberPkg, "uber-offer"))))
        assertEquals(listOf("uber-offer"), collect(h, Kind.STATE, windowId = 3).map { it.tree.text }) // warms window 3 → DoorDash

        whenever(activeWindow.root).thenReturn(null) // the active window's root is now unreadable
        assertEquals(listOf("uber-offer"), collect(h, Kind.STATE, windowId = 3).map { it.tree.text })
        assertEquals(listOf("uber-offer"), topologyFrames(h).map { it.tree.text })
    }

    @Test
    fun `HH1, II1 - unreadable flagged active root, NO overlay, MATCHING native root - the event path reads it, topology emits nothing`() {
        Kind.entries.forEach { kind ->
            val dd = node(ddPkg, "dd", windowId = 3) // rootInActiveWindow IS window 3
            val h = harness(activeRoot = dd, windows = listOf(window(3, 5, null, active = true), window(4, 2, node(ddPkg, "dd-below"))))

            val frames = collect(h, kind, windowId = 3)
            assertEquals(listOf("dd"), frames.map { it.tree.text })
            assertEquals(3, frames.single().windowContext?.windowId) // mapped through the window builder
            assertTrue(topologyFrames(h).isEmpty())
            assertEquals(1L, h.stats.topologySkipCount(ForegroundSkipReason.FRONT_UNREADABLE))
        }
    }

    @Test
    fun `II1 - unreadable flagged active root, NO overlay, MISMATCHED native root - the event path refuses`() {
        Kind.entries.forEach { kind ->
            val stray = node(ddPkg, "dd-stray", windowId = 42) // not the flagged window
            val h = harness(activeRoot = stray, windows = listOf(window(3, 5, null, active = true), window(4, 2, node(ddPkg, "dd-below"))))

            assertTrue("never a root that is not provably the flagged window", collect(h, kind, windowId = 3).isEmpty())
            h.skipped(ForegroundSkipReason.FRONT_UNREADABLE)
        }
    }

    @Test
    fun `II1 - matching native root of a NOT-enabled package - refused`() {
        Kind.entries.forEach { kind ->
            val launcher = node("com.android.launcher3", "home", windowId = 3)
            val h = harness(activeRoot = launcher, windows = listOf(window(3, 5, null, active = true)))

            assertTrue(collect(h, kind, windowId = 3).isEmpty())
            h.skipped(ForegroundSkipReason.FRONT_UNREADABLE)
        }
    }
}
