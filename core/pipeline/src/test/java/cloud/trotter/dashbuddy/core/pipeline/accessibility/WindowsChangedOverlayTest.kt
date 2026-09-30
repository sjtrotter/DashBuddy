package cloud.trotter.dashbuddy.core.pipeline.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import cloud.trotter.dashbuddy.core.pipeline.PipelineStats
import cloud.trotter.dashbuddy.core.pipeline.accessibility.event.type.window.windows_changed.WindowsChangedPipeline
import cloud.trotter.dashbuddy.core.pipeline.accessibility.input.AccEvent
import cloud.trotter.dashbuddy.core.pipeline.accessibility.input.AccessibilitySource
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #1148 review G6/H1 — the topology path emits TRUE OVERLAYS only: enabled-package APPLICATION
 * windows ABOVE the active window. A window beneath the active one — the activity under a DoorDash
 * sheet — is never emitted (that re-opened the F1 interleaving). #1152 D6: a platform offer overlay
 * (a11y `TYPE_SYSTEM`, size + package) above the active window is emitted too; every other
 * system-layer window (the puck, the status bar, the shade, a DoorDash toast) never is.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WindowsChangedOverlayTest {

    private val ddPkg = "com.doordash.driverapp"
    private val uberPkg = "com.ubercab.driver"

    private fun node(pkg: String, label: String): AccessibilityNodeInfo = mock {
        on { packageName } doReturn pkg
        on { text } doReturn label
    }

    private fun window(
        windowId: Int,
        windowLayer: Int,
        root: AccessibilityNodeInfo?,
        windowType: Int = AccessibilityWindowInfo.TYPE_APPLICATION,
        active: Boolean = false,
        bounds: Rect? = null, // null → zero-size
    ): AccessibilityWindowInfo {
        val w = mock<AccessibilityWindowInfo> {
            on { id } doReturn windowId
            on { layer } doReturn windowLayer
            on { this.root } doReturn root
            on { isActive } doReturn active
            on { type } doReturn windowType
        }
        return if (bounds != null) withBounds(w, bounds) else w
    }

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
}
