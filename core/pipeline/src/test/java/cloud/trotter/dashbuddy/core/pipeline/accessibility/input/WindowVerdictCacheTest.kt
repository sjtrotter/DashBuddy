package cloud.trotter.dashbuddy.core.pipeline.accessibility.input

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import cloud.trotter.dashbuddy.core.pipeline.PipelineStats
import cloud.trotter.dashbuddy.core.pipeline.accessibility.OverlayGeometry
import cloud.trotter.dashbuddy.core.pipeline.accessibility.OverlayRejectReason
import cloud.trotter.dashbuddy.core.pipeline.accessibility.displayResources
import cloud.trotter.dashbuddy.core.pipeline.accessibility.withBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #1152 D3 / PR #1155 review BB7 — `windowId → (package, overlay verdict)`: a hit costs no root
 * fetch (and no bounds read for a decided verdict), a `TYPE_WINDOWS_CHANGED` emit clears it,
 * refusals are counted once per decision, and it is bounded.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WindowVerdictCacheTest {

    private val ownPkg = "cloud.trotter.dashbuddy"
    private val ddPkg = "com.doordash.driverapp"

    private fun node(pkg: String): AccessibilityNodeInfo = mock { on { packageName } doReturn pkg }

    private fun window(id: Int, root: AccessibilityNodeInfo, layer: Int, type: Int, active: Boolean = false) =
        withBounds(
            mock<AccessibilityWindowInfo> {
                on { this.id } doReturn id
                on { this.type } doReturn type
                on { this.root } doReturn root
                on { this.layer } doReturn layer
                on { isActive } doReturn active
            },
            OverlayGeometry.FULL_SCREEN,
        )

    private fun source(windows: List<AccessibilityWindowInfo>): AccessibilitySource {
        val res = displayResources()
        val service = mock<AccessibilityService> {
            on { this.windows } doReturn windows
            on { resources } doReturn res
            on { packageName } doReturn ownPkg
        }
        return AccessibilitySource().apply { registerService(service) }
    }

    @Suppress("DEPRECATION")
    private fun event(type: Int): AccessibilityEvent = AccessibilityEvent.obtain(type)

    private val display = OverlayGeometry.DISPLAY_W.toLong() * OverlayGeometry.DISPLAY_H

    @Test
    fun `a hit costs no root fetch, a WINDOWS_CHANGED emit clears it, other events do not`() {
        val shade = window(30, node("com.android.systemui"), 20, AccessibilityWindowInfo.TYPE_SYSTEM)
        val src = source(listOf(shade))

        candidate(src, shade, display)
        candidate(src, shade, display)
        verify(shade, times(1)).root // second probe was a cache hit

        src.emit(event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED))
        candidate(src, shade, display)
        verify(shade, times(1)).root // a content event leaves the cache alone

        src.emit(event(AccessibilityEvent.TYPE_WINDOWS_CHANGED))
        candidate(src, shade, display)
        verify(shade, times(2)).root // topology changed → re-fetched once
    }

    @Test
    fun `the foreground read skips our own bubble on a cache hit - its root is fetched once across frames`() {
        val bubble = window(1, node(ownPkg), 10, AccessibilityWindowInfo.TYPE_APPLICATION, active = true)
        val dd = window(3, node(ddPkg), 2, AccessibilityWindowInfo.TYPE_APPLICATION)
        val src = source(listOf(bubble, dd))

        repeat(2) {
            val front = src.foregroundWindow { it == ddPkg }
            assertTrue(front is AccessibilitySource.Foreground.Found)
        }
        verify(bubble, times(1)).root
        verify(dd, times(2)).root // a READ window's root is always fetched fresh
    }

    @Test
    fun `BB7 - the same status bar across two frames - decided once, counted once, no root fetch`() {
        val systemUi = node("com.android.systemui") // built up front: no mock inside a stubbing lambda
        val statusBar = withBounds(
            mock<AccessibilityWindowInfo> {
                on { this.id } doReturn 20
                on { this.type } doReturn AccessibilityWindowInfo.TYPE_SYSTEM
                on { this.root } doReturn systemUi
            },
            OverlayGeometry.STATUS_BAR,
        )
        val stats = PipelineStats()
        val res = displayResources()
        val service = mock<AccessibilityService> {
            on { this.windows } doReturn listOf(statusBar)
            on { resources } doReturn res
            on { packageName } doReturn ownPkg
        }
        val src = AccessibilitySource(stats).apply { registerService(service) }

        repeat(2) { src.overlayProbe(statusBar, display) }

        verify(statusBar, never()).root
        assertEquals(1L, stats.overlayRejectedCount(OverlayRejectReason.TOO_SMALL))
    }

    @Test
    fun `an unreadable verdict is NOT memoized - retried next frame`() {
        val unreadable = window(9, node("x"), 9, AccessibilityWindowInfo.TYPE_SYSTEM)
        whenever(unreadable.root).thenReturn(null)
        val src = source(listOf(unreadable))
        repeat(2) { assertEquals(AccessibilitySource.OverlayProbe.Unreadable, src.overlayProbe(unreadable, display)) }
        verify(unreadable, times(2)).root
    }

    @Test
    fun `bounded at CAPACITY, least-recently-used evicted, verdicts keep their package`() {
        val cache = WindowVerdictCache()
        val g = cache.generation
        for (id in 0 until WindowVerdictCache.CAPACITY) cache.putPackage(id, "p$id", g)
        cache.get(0) // touch → most recent
        cache.putPackage(WindowVerdictCache.CAPACITY, "new", g)
        assertEquals(WindowVerdictCache.CAPACITY, cache.size)
        assertEquals("p0", cache.get(0)?.packageName)
        assertNull("the least-recently-used entry is evicted", cache.get(1))
        val b = WindowVerdictCache.Bounds(0, 0, 10, 10)
        cache.putVerdict(0, null, WindowVerdictCache.Verdict.NOT_OVERLAY_PLATFORM, b, g)
        assertEquals(WindowVerdictCache.Entry("p0", WindowVerdictCache.Verdict.NOT_OVERLAY_PLATFORM, b), cache.get(0))
        cache.clear()
        assertEquals(0, cache.size)
    }

    @Test
    fun `CC1 - a write whose generation moved (a clear mid-probe) is discarded`() {
        val cache = WindowVerdictCache()
        val started = cache.generation
        cache.clear() // topology changed while the probe was in its root fetch
        cache.putVerdict(9, "com.ubercab.driver", WindowVerdictCache.Verdict.CANDIDATE, WindowVerdictCache.Bounds(0, 136, 1080, 2347), started)
        cache.putPackage(9, "com.ubercab.driver", started)
        assertNull(cache.get(9))
    }

    @Test
    fun `CC1 - a WINDOWS_CHANGED during the probe's root fetch leaves no stale verdict`() {
        val uber = node("com.ubercab.driver")
        val overlay = window(9, uber, 9, AccessibilityWindowInfo.TYPE_SYSTEM)
        val src = source(listOf(overlay))
        // The clear lands exactly while the probe is inside the (binder) root fetch.
        whenever(overlay.root).thenAnswer {
            src.emit(event(AccessibilityEvent.TYPE_WINDOWS_CHANGED))
            uber
        }

        assertTrue(candidate(src, overlay, display)) // this frame's own decision stands
        candidate(src, overlay, display)
        verify(overlay, times(2)).root // nothing was memoized across the clear → re-probed
    }

    @Test
    fun `CC1 - a memoized CANDIDATE is re-probed when the window's bounds change`() {
        val uber = node("com.ubercab.driver")
        val w = withBounds(
            mock<AccessibilityWindowInfo> {
                on { this.id } doReturn 9
                on { this.type } doReturn AccessibilityWindowInfo.TYPE_SYSTEM
                on { this.root } doReturn uber
            },
            OverlayGeometry.UBER_OFFER,
        )
        val stats = PipelineStats()
        val res = displayResources()
        val service = mock<AccessibilityService> {
            on { this.windows } doReturn listOf(w)
            on { resources } doReturn res
            on { packageName } doReturn ownPkg
        }
        val src = AccessibilitySource(stats).apply { registerService(service) }

        assertTrue(candidate(src, w, display))
        withBounds(w, OverlayGeometry.UBER_PUCK) // the same window id, now puck-sized
        assertEquals(false, candidate(src, w, display))
        assertEquals(1L, stats.overlayRejectedCount(OverlayRejectReason.TOO_SMALL))
    }

    @Test
    fun `CC1 - a memoized CANDIDATE is not honoured without a display area`() {
        val uber = node("com.ubercab.driver")
        val w = window(9, uber, 9, AccessibilityWindowInfo.TYPE_SYSTEM)
        val src = source(listOf(w))
        assertTrue(candidate(src, w, display))
        assertEquals(false, candidate(src, w, 0L))
    }

    /** CC11: tests assert on the ONE seam's sealed result. */
    private fun candidate(src: AccessibilitySource, w: AccessibilityWindowInfo, area: Long): Boolean =
        src.overlayProbe(w, area) is AccessibilitySource.OverlayProbe.Candidate
}
