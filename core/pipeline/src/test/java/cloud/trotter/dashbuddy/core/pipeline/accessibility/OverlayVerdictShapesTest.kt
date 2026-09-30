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
 * #1152 / PR #1155 — the verdict cache and walk-budget shapes as seen through the event path: memo
 * correction (CC10/DD10), generation-checked writes (DD9), the root-fetch budget (CC5/DD4), the cached
 * short-circuit (FF3) and census truth (FF4).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OverlayVerdictShapesTest : WindowResolverTestBase() {

    @Test
    fun `bubble active, an Uber overlay whose root vanished after the package was cached - refused unreadable`() {
        val bubble = node(ownPkg, "bubble")
        val dd = node(ddPkg, "dd", windowId = 3)
        val uber = node(uberPkg, "uber-offer")
        val overlay = uberOverlay(9, 9, uber)
        val h = harness(activeRoot = bubble, windows = listOf(window(1, 10, bubble, active = true), overlay, window(3, 2, dd)))

        assertEquals(listOf("uber-offer"), collect(h, Kind.STATE).map { it.tree.text }) // caches uber for id 9
        whenever(overlay.root).thenReturn(null)
        assertTrue("an unreadable top candidate refuses — never fall through to DoorDash", collect(h, Kind.STATE).isEmpty())
        h.skipped(ForegroundSkipReason.FRONT_UNREADABLE)
        assertEquals("JJ4: the unreadable revalidation is counted", 1L, h.stats.overlayRejectedCount(OverlayRejectReason.UNREADABLE))
    }

    @Test
    fun `CC5 - 65 large readable non-overlay system windows above DoorDash - bounded fetches per scan, refused`() {
        val dd = node(ddPkg, "dd", windowId = 3)
        val shadeRoot = node(systemUiPkg, "big")
        var fetches = 0
        val bigs = (0 until 65).map { i ->
            val w = window(100 + i, 20 + i, null, windowType = system, bounds = OverlayGeometry.FULL_SCREEN)
            whenever(w.root).thenAnswer { fetches++; shadeRoot }
            w
        }
        val h = harness(activeRoot = dd, windows = listOf(window(3, 5, dd, active = true)) + bigs)

        repeat(3) { scan ->
            val before = fetches
            assertTrue("scan $scan refused", collect(h, Kind.STATE, windowId = 3).isEmpty())
            assertTrue("scan $scan fetched ${fetches - before} roots", fetches - before <= AccessibilitySource.MAX_SCAN_ROOT_FETCHES)
        }
        assertEquals(3L, h.stats.foregroundSkipCount(ForegroundSkipReason.SCAN_BUDGET))
    }

    @Test
    fun `CC10 - a stale memoized CANDIDATE is corrected, counted PACKAGE_CHANGED, never refused not-enabled`() {
        val bubble = node(ownPkg, "bubble")
        val dd = node(ddPkg, "dd")
        val uber = node(uberPkg, "uber-offer")
        val shade = node(systemUiPkg, "shade")
        val overlay = uberOverlay(9, 9, uber)
        val h = harness(activeRoot = bubble, windows = listOf(window(1, 10, bubble, active = true), overlay, window(3, 2, dd)))

        assertEquals(listOf("uber-offer"), collect(h, Kind.STATE).map { it.tree.text }) // memo: CANDIDATE
        whenever(overlay.root).thenReturn(shade) // same id + bounds, now owned by someone else
        assertEquals(listOf("dd"), collect(h, Kind.STATE).map { it.tree.text })
        assertEquals(1L, h.stats.overlayRejectedCount(OverlayRejectReason.PACKAGE_CHANGED))
        assertEquals(0L, h.stats.foregroundSkipCount(ForegroundSkipReason.FRONT_NOT_ENABLED))

        assertEquals(listOf("dd"), collect(h, Kind.STATE).map { it.tree.text })
        verify(overlay, times(2)).root // corrected memo: the next frame does not re-fetch
    }

    @Test
    fun `DD4 - a memoized CANDIDATE's revalidation fetch is charged - over budget it refuses SCAN_BUDGET`() {
        val bubble = node(ownPkg, "bubble")
        val dd = node(ddPkg, "dd")
        val uber = node(uberPkg, "uber-offer")
        val overlay = uberOverlay(9, 9, uber)
        val bubbleWindow = window(1, 50, bubble, active = true)
        val ddWindow = window(3, 2, dd)
        val h = harness(activeRoot = bubble, windows = listOf(bubbleWindow, overlay, ddWindow))
        assertEquals(listOf("uber-offer"), collect(h, Kind.STATE).map { it.tree.text }) // memo: CANDIDATE, bubble: own

        // Mixed cache: 8 COLD large readable non-overlay windows above the memoized overlay spend the
        // whole budget; the overlay's revalidation fetch must not be free.
        val shade = node(systemUiPkg, "big")
        val cold = (0 until AccessibilitySource.MAX_SCAN_ROOT_FETCHES).map { i ->
            window(100 + i, 20 + i, shade, windowType = system, bounds = OverlayGeometry.FULL_SCREEN)
        }
        whenever(h.service.windows).thenReturn(listOf(bubbleWindow) + cold + listOf(overlay, ddWindow))

        assertTrue(collect(h, Kind.STATE).isEmpty())
        h.skipped(ForegroundSkipReason.SCAN_BUDGET)
    }

    @Test
    fun `DD9 - a clear between the walk's enumeration and a probe - the probe's verdict is not written`() {
        val bubble = node(ownPkg, "bubble")
        val bubble2 = node(ownPkg, "bubble-2")
        val shade = node(systemUiPkg, "shade")
        val bubbleWindow = window(1, 50, bubble, active = true)
        // A second, non-active window of ours ABOVE the shade: the walk fetches its root (uncached)
        // and skips it — the topology changes during that fetch, i.e. mid-walk.
        val bubble2Window = window(2, 40, null)
        val shadeWindow = window(30, 30, shade, windowType = system, bounds = OverlayGeometry.FULL_SCREEN)
        val h = harness(activeRoot = bubble, windows = listOf(bubbleWindow, bubble2Window, shadeWindow, window(3, 2, node(ddPkg, "dd"))))
        var first = true
        whenever(bubble2Window.root).thenAnswer {
            if (first) {
                first = false
                @Suppress("DEPRECATION")
                h.source.emit(AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOWS_CHANGED))
            }
            bubble2
        }

        assertEquals(listOf("dd"), collect(h, Kind.STATE).map { it.tree.text })
        assertEquals(listOf("dd"), collect(h, Kind.STATE).map { it.tree.text })
        verify(shadeWindow, times(2)).root // the first walk's NOT_OVERLAY_PLATFORM was stale → never memoized
    }

    @Test
    fun `DD10 - a corrected memo whose fresh package IS an enabled overlay platform is used - the overlay, not beneath`() {
        val bubble = node(ownPkg, "bubble")
        val uber = node(uberPkg, "uber-offer")
        val other = "com.example.other"
        val h = harness(
            activeRoot = bubble,
            windows = listOf(window(1, 10, bubble, active = true), uberOverlay(9, 9, uber), window(3, 2, node(ddPkg, "dd"))),
            enabled = setOf(ddPkg, uberPkg, other),
        )
        // Seed a STALE memoized CANDIDATE naming another (enabled) package for the overlay's id.
        val field = AccessibilitySource::class.java.getDeclaredField("packageCache").apply { isAccessible = true }
        val cache = field.get(h.source) as cloud.trotter.dashbuddy.core.pipeline.accessibility.input.WindowVerdictCache
        val b = OverlayGeometry.UBER_OFFER
        cache.putVerdict(
            9, other, cloud.trotter.dashbuddy.core.pipeline.accessibility.input.WindowVerdictCache.Verdict.CANDIDATE,
            cloud.trotter.dashbuddy.core.pipeline.accessibility.input.WindowVerdictCache.Bounds(b.left, b.top, b.right, b.bottom),
            OverlayGeometry.DISPLAY_W.toLong() * OverlayGeometry.DISPLAY_H, cache.generation,
        )

        assertEquals(listOf("uber-offer"), collect(h, Kind.STATE).map { it.tree.text })
        assertEquals(1L, h.stats.overlayRejectedCount(OverlayRejectReason.PACKAGE_CHANGED))
    }

    @Test
    fun `FF3 - a cached enabled application window above the active one decides the overlay walk without a root fetch`() {
        val dd = node(ddPkg, "dd", windowId = 3)
        val sheetWindow = window(7, 9, node(ddPkg, "dd-sheet"))
        val h = harness(activeRoot = dd, windows = listOf(window(3, 2, dd, active = true), uberOverlay(9, 5, node(uberPkg, "uber-offer")), sheetWindow))

        repeat(2) { assertEquals(listOf("dd"), collect(h, Kind.STATE, windowId = 3).map { it.tree.text }) }
        verify(sheetWindow, times(1)).root // the second walk decides on the cached package
    }

    @Test
    fun `FF4 - a memoized overlay whose fresh root has NO package counts UNREADABLE, not PACKAGE_CHANGED`() {
        val bubble = node(ownPkg, "bubble")
        val overlay = uberOverlay(9, 9, node(uberPkg, "uber-offer"))
        val h = harness(activeRoot = bubble, windows = listOf(window(1, 10, bubble, active = true), overlay, window(3, 2, node(ddPkg, "dd"))))
        assertEquals(listOf("uber-offer"), collect(h, Kind.STATE).map { it.tree.text }) // memo: CANDIDATE

        val pkgless = mock<AccessibilityNodeInfo>()
        whenever(overlay.root).thenReturn(pkgless)
        assertTrue(collect(h, Kind.STATE).isEmpty())
        assertEquals(1L, h.stats.overlayRejectedCount(OverlayRejectReason.UNREADABLE))
        assertEquals(0L, h.stats.overlayRejectedCount(OverlayRejectReason.PACKAGE_CHANGED))
    }

    @Test
    fun `KK1 - a clear between the enumeration and the walk - the walk's verdicts are not written`() {
        val bubble = node(ownPkg, "bubble")
        val shade = node(systemUiPkg, "shade")
        val shadeWindow = window(30, 30, shade, windowType = system, bounds = OverlayGeometry.FULL_SCREEN)
        val list = listOf(window(1, 50, bubble, active = true), shadeWindow, window(3, 2, node(ddPkg, "dd")))
        val h = harness(activeRoot = bubble, windows = list)
        var first = true
        // The topology changes right AFTER this enumeration returned, before the walk probes the shade.
        whenever(h.service.windows).thenAnswer {
            if (first) {
                first = false
                @Suppress("DEPRECATION")
                h.source.emit(AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOWS_CHANGED))
            }
            list
        }

        assertEquals(listOf("dd"), collect(h, Kind.STATE).map { it.tree.text })
        assertEquals(listOf("dd"), collect(h, Kind.STATE).map { it.tree.text })
        verify(shadeWindow, times(2)).root // the first walk's verdict was decided on the old list → never memoized
    }
}
