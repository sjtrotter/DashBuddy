package cloud.trotter.dashbuddy.core.pipeline.accessibility.input

import android.accessibilityservice.AccessibilityService
import android.content.res.Resources
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import cloud.trotter.dashbuddy.core.pipeline.PipelineStats
import cloud.trotter.dashbuddy.core.pipeline.accessibility.OverlayGeometry
import cloud.trotter.dashbuddy.core.pipeline.accessibility.OverlayRejectReason
import cloud.trotter.dashbuddy.core.pipeline.accessibility.displayResources
import cloud.trotter.dashbuddy.core.pipeline.accessibility.withBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #1152 D2 — a platform offer overlay is decided by TYPE, then SIZE, then PACKAGE (cheapest first).
 * Geometry is the Pixel 7 record from #248 ([OverlayGeometry]). Each refusal names its reason in
 * `PipelineStats.overlayRejected{…}`, and every check that runs before the package read proves the
 * root was NEVER fetched.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OverlayCandidateTest {

    private val uberPkg = "com.ubercab.driver"
    private val ddPkg = "com.doordash.driverapp"
    private val systemUiPkg = "com.android.systemui"

    private val stats = PipelineStats()

    private fun node(pkg: String): AccessibilityNodeInfo = mock { on { packageName } doReturn pkg }

    private fun window(
        id: Int,
        root: AccessibilityNodeInfo?,
        bounds: Rect,
        type: Int = AccessibilityWindowInfo.TYPE_SYSTEM,
        pip: Boolean = false,
    ): AccessibilityWindowInfo = withBounds(
        mock {
            on { this.id } doReturn id
            on { this.type } doReturn type
            on { this.root } doReturn root
            on { isInPictureInPictureMode } doReturn pip
        },
        bounds,
    )

    private fun source(windows: List<AccessibilityWindowInfo> = emptyList(), res: Resources? = displayResources()): AccessibilitySource {
        val service = mock<AccessibilityService> {
            on { this.windows } doReturn windows
            on { resources } doReturn res
            on { packageName } doReturn "cloud.trotter.dashbuddy"
        }
        return AccessibilitySource(stats).apply { registerService(service) }
    }

    private val display = OverlayGeometry.DISPLAY_W.toLong() * OverlayGeometry.DISPLAY_H

    @Test
    fun `the Uber offer overlay (1080x2211 on 1080x2400) is a candidate`() {
        val w = window(9, node(uberPkg), OverlayGeometry.UBER_OFFER)
        assertTrue(candidate(source(), w, display))
    }

    @Test
    fun `the 142x142 Uber puck is refused on size, root never fetched`() {
        val w = window(10, node(uberPkg), OverlayGeometry.UBER_PUCK)
        assertFalse(candidate(source(), w, display))
        verify(w, never()).root
        assertEquals(1L, stats.overlayRejectedCount(OverlayRejectReason.TOO_SMALL))
    }

    @Test
    fun `the status bar is refused on size, root never fetched`() {
        val w = window(20, node(systemUiPkg), OverlayGeometry.STATUS_BAR)
        assertFalse(candidate(source(), w, display))
        verify(w, never()).root
    }

    @Test
    fun `the size threshold is a quarter of the display`() {
        val quarter = Rect(0, 0, 1080, 600) // exactly 25 %
        val justUnder = Rect(0, 0, 1080, 599)
        assertTrue(candidate(source(), window(1, node(uberPkg), quarter), display))
        assertFalse(candidate(source(), window(2, node(uberPkg), justUnder), display))
    }

    @Test
    fun `the expanded shade passes size, fails package, and its root is fetched ONCE across two frames`() {
        val shade = window(30, node(systemUiPkg), OverlayGeometry.FULL_SCREEN)
        val src = source()
        assertFalse(candidate(src, shade, display))
        assertFalse(candidate(src, shade, display))
        verify(shade, times(1)).root
        assertEquals("BB7: counted once per decision, not per frame", 1L, stats.overlayRejectedCount(OverlayRejectReason.NOT_OVERLAY_PLATFORM))
    }

    @Test
    fun `a DoorDash system-layer window is refused - DoorDash declares no offer overlay`() {
        val w = window(11, node(ddPkg), OverlayGeometry.UBER_OFFER)
        assertFalse(candidate(source(), w, display))
        assertEquals(1L, stats.overlayRejectedCount(OverlayRejectReason.NOT_OVERLAY_PLATFORM))
    }

    @Test
    fun `picture-in-picture and application windows are never overlay candidates`() {
        val pip = window(12, node(uberPkg), OverlayGeometry.UBER_OFFER, pip = true)
        val app = window(13, node(uberPkg), OverlayGeometry.UBER_OFFER, type = AccessibilityWindowInfo.TYPE_APPLICATION)
        assertFalse(candidate(source(), pip, display))
        assertFalse(candidate(source(), app, display))
        verify(pip, never()).root
        verify(app, never()).root
    }

    @Test
    fun `a large unreadable system window cannot prove its package - Unreadable, not merely false`() {
        val w = window(14, null, OverlayGeometry.UBER_OFFER)
        assertEquals(AccessibilitySource.OverlayProbe.Unreadable, source().walk.overlayProbe(w, display)) // CC11: the distinction a Boolean lost
        assertEquals(1L, stats.overlayRejectedCount(OverlayRejectReason.UNREADABLE))
    }

    @Test
    fun `an unknown display area admits nothing and fetches nothing (fail closed)`() {
        val w = window(15, node(uberPkg), OverlayGeometry.UBER_OFFER)
        val src = source(windows = listOf(w), res = null)
        val area = src.walk.displayArea()
        assertEquals(0L, area)
        assertFalse(candidate(src, w, area))
        verify(w, never()).root
        assertEquals(1L, stats.overlayRejectedCount(OverlayRejectReason.NO_DISPLAY_AREA))
    }

    @Test
    fun `BB4 - no metrics - no window-bounds fallback - a puck beside a small PiP is refused NO_DISPLAY_AREA`() {
        // The removed fallback measured the largest APPLICATION window: a 200×200 PiP would make the
        // 142×142 puck ~50 % of "the display" and admit it.
        val pip = window(3, node("com.google.android.apps.maps"), Rect(0, 0, 200, 200), type = AccessibilityWindowInfo.TYPE_APPLICATION, pip = true)
        val puck = window(10, node(uberPkg), OverlayGeometry.UBER_PUCK)
        val src = source(windows = listOf(pip, puck), res = null)
        assertEquals(0L, src.walk.displayArea())
        assertFalse(candidate(src, puck, src.walk.displayArea()))
        verify(puck, never()).root
        assertEquals(1L, stats.overlayRejectedCount(OverlayRejectReason.NO_DISPLAY_AREA))
    }

    @Test
    fun `display area is the service's display metrics`() {
        assertEquals(display, source().walk.displayArea())
    }

    @Test
    fun `DD5 - the display area doubles, same window bounds - the memo is re-probed and TOO_SMALL`() {
        val metrics = android.util.DisplayMetrics().apply {
            widthPixels = OverlayGeometry.DISPLAY_W
            heightPixels = OverlayGeometry.DISPLAY_H
        }
        val res = mock<Resources> { on { displayMetrics } doReturn metrics }
        val w = window(9, node(uberPkg), Rect(0, 0, 1080, 700)) // ~29 % of 1080×2400
        val src = source(windows = listOf(w), res = res)

        assertTrue(candidate(src, w, src.walk.displayArea()))
        metrics.widthPixels = 2 * OverlayGeometry.DISPLAY_W // no topology event — nothing clears the memo
        assertEquals("read per resolution, never memoized", 2 * display, src.walk.displayArea())
        assertFalse(candidate(src, w, src.walk.displayArea())) // ~15 % → too small
        assertEquals(1L, stats.overlayRejectedCount(OverlayRejectReason.TOO_SMALL))
    }

    /** CC11: tests assert on the ONE seam's sealed result. */
    private fun candidate(src: AccessibilitySource, w: AccessibilityWindowInfo, area: Long): Boolean =
        src.walk.overlayProbe(w, area) is AccessibilitySource.OverlayProbe.Candidate
}
