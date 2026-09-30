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
 * #1152 / PR #1155 — the EVENT-path platform-offer-overlay rules: an enabled overlay in front of the
 * active window is the frame (BB5); its candidacy (size, package, enablement — D2/BB6); what above
 * the active window refuses the frame and what does not (BB1/CC4/CC7/DD6/DD7/DD8/HH3); map failure
 * (DD1) and the focused overlay (HH5).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OverlayEventPathTest : WindowResolverTestBase() {

    @Test
    fun `(a) Uber event, Uber overlay above the active DoorDash window - the OVERLAY is read and counted`() = bothKinds { kind ->
        val dd = node(ddPkg, "dd", windowId = 3)
        val uber = node(uberPkg, "uber-offer")
        val h = harness(activeRoot = dd, windows = listOf(window(3, 5, dd, active = true), uberOverlay(9, 9, uber)))

        val emitted = collect(h, kind, windowId = 9, pkg = uberPkg)

        assertEquals(listOf("uber-offer"), emitted.map { it.tree.text })
        assertEquals(uberPkg, emitted.single().packageName)
        assertEquals(9, emitted.single().windowContext?.windowId)
        assertEquals(1L, h.stats.overlaySnapshotCount())
        assertEquals("CC9: one scan per resolution, counted", 1L, h.stats.overlayScanCount())
        verify(h.source, never()).getCurrentRootSnapshot(any<AccessibilityNodeInfo>())
    }

    @Test
    fun `(b) Uber event from the Uber APP window beneath the active DoorDash - the DoorDash active root`() = bothKinds { kind ->
        val dd = node(ddPkg, "dd", windowId = 3)
        val uberApp = node(uberPkg, "uber-app")
        val uberAppWindow = window(4, 2, uberApp, bounds = OverlayGeometry.FULL_SCREEN) // TYPE_APPLICATION
        val h = harness(activeRoot = dd, windows = listOf(window(3, 5, dd, active = true), uberAppWindow))

        val emitted = collect(h, kind, windowId = 4, pkg = uberPkg)

        assertEquals(listOf("dd"), emitted.map { it.tree.text })
        assertEquals(0L, h.stats.overlaySnapshotCount())
        verify(uberAppWindow, never()).root // an application window is never an overlay candidate
    }

    @Test
    fun `an Uber overlay BENEATH the active window is never read (the layer rule)`() = bothKinds { kind ->
        val dd = node(ddPkg, "dd", windowId = 3)
        val uber = node(uberPkg, "uber-offer")
        val h = harness(activeRoot = dd, windows = listOf(window(3, 12, dd, active = true), uberOverlay(9, 9, uber)))

        assertEquals(listOf("dd"), collect(h, kind, windowId = 9, pkg = uberPkg).map { it.tree.text })
        assertEquals(0L, h.stats.overlaySnapshotCount())
    }

    @Test
    fun `(c) Uber DISABLED - the branch is skipped without an enumeration - DoorDash active root`() = bothKinds { kind ->
        val dd = node(ddPkg, "dd", windowId = 3)
        val uberWindow = uberOverlay(9, 9, node(uberPkg, "uber-offer"))
        val h = harness(
            activeRoot = dd,
            windows = listOf(window(3, 5, dd, active = true), uberWindow),
            enabled = setOf(ddPkg),
        )

        assertEquals(listOf("dd"), collect(h, kind, windowId = 9, pkg = uberPkg).map { it.tree.text })
        verify(h.service, never()).windows
        verify(uberWindow, never()).root
    }

    @Test
    fun `(d, BB5) a DoorDash event while an enabled Uber overlay is above - the OVERLAY is the frame`() = bothKinds { kind ->
        // PR #1155 review BB5 supersedes spec (d): an enabled overlay on top wins for EVERY event, so
        // the covered window can never interleave with it.
        val dd = node(ddPkg, "dd", windowId = 3)
        val h = harness(
            activeRoot = dd,
            windows = listOf(window(3, 5, dd, active = true), uberOverlay(9, 9, node(uberPkg, "uber-offer"))),
        )

        assertEquals(listOf("uber-offer"), collect(h, kind, windowId = 3, pkg = ddPkg).map { it.tree.text })
        assertEquals(1L, h.stats.overlaySnapshotCount())
    }

    @Test
    fun `Uber enabled, nothing system-layer above the active DoorDash - the active root, no metrics read`() = bothKinds { kind ->
        val dd = node(ddPkg, "dd", windowId = 3)
        val h = harness(activeRoot = dd, windows = listOf(window(3, 5, dd, active = true), window(4, 2, node(uberPkg, "uber-app"))))

        assertEquals(listOf("dd"), collect(h, kind, windowId = 3).map { it.tree.text })
        verify(h.service, never()).resources
    }

    @Test
    fun `the Uber PUCK firing an event above DoorDash is never read`() = bothKinds { kind ->
        val dd = node(ddPkg, "dd", windowId = 3)
        val puck = window(10, 9, node(uberPkg, "uber-puck"), windowType = system, bounds = OverlayGeometry.UBER_PUCK)
        val h = harness(activeRoot = dd, windows = listOf(window(3, 5, dd, active = true), puck))

        assertEquals(listOf("dd"), collect(h, kind, windowId = 10, pkg = uberPkg).map { it.tree.text })
        verify(puck, never()).root
    }

    @Test
    fun `a DoorDash system-layer window firing a DoorDash event is never read (not an overlay platform)`() = bothKinds { kind ->
        val dd = node(ddPkg, "dd", windowId = 3)
        val ddToast = window(11, 9, node(ddPkg, "dd-toast"), windowType = system, bounds = OverlayGeometry.UBER_OFFER)
        val h = harness(activeRoot = dd, windows = listOf(window(3, 5, dd, active = true), ddToast))

        assertEquals(listOf("dd"), collect(h, kind, windowId = 11, pkg = ddPkg).map { it.tree.text })
    }

    @Test
    fun `HH5 - the overlay with focus (the card IS the active window) - counted and carries its WindowContext`() = bothKinds { kind ->
        val uber = node(uberPkg, "uber-offer", windowId = 9)
        val dd = node(ddPkg, "dd", windowId = 3)
        val h = harness(activeRoot = uber, windows = listOf(uberOverlay(9, 9, uber, active = true), window(3, 5, dd)))

        val emitted = collect(h, kind, windowId = 9, pkg = uberPkg)

        assertEquals(listOf("uber-offer"), emitted.map { it.tree.text })
        assertEquals(9, emitted.single().windowContext?.windowId)
        assertEquals(1L, h.stats.overlaySnapshotCount())
    }

    @Test
    fun `(e) our bubble active, Uber overlay above DoorDash - the overlay via the foreground read, counted`() = bothKinds { kind ->
        val bubble = node(ownPkg, "bubble")
        val dd = node(ddPkg, "dd", windowId = 3)
        val uber = node(uberPkg, "uber-offer")
        val h = harness(
            activeRoot = bubble,
            windows = listOf(window(1, 10, bubble, active = true), uberOverlay(9, 9, uber), window(3, 2, dd)),
        )

        // A DoorDash event: the foreground read decides regardless of which package fired.
        val emitted = collect(h, kind, windowId = 3, pkg = ddPkg)

        assertEquals(listOf("uber-offer"), emitted.map { it.tree.text })
        assertEquals(9, emitted.single().windowContext?.windowId)
        assertEquals(1L, h.stats.overlaySnapshotCount())
    }

    @Test
    fun `(f) our bubble active, only the puck and DoorDash - DoorDash`() = bothKinds { kind ->
        val bubble = node(ownPkg, "bubble")
        val dd = node(ddPkg, "dd", windowId = 3)
        val puck = window(10, 9, node(uberPkg, "uber-puck"), windowType = system, bounds = OverlayGeometry.UBER_PUCK)
        val h = harness(activeRoot = bubble, windows = listOf(window(1, 10, bubble, active = true), puck, window(3, 2, dd)))

        assertEquals(listOf("dd"), collect(h, kind).map { it.tree.text })
        verify(puck, never()).root
        assertEquals(0L, h.stats.overlaySnapshotCount())
    }

    @Test
    fun `BB6 - bubble active, a DISABLED platform's overlay above DoorDash is skipped - DoorDash is read`() = bothKinds { kind ->
        // The dasher chose to ignore that platform: its offer must not blank DoorDash for its lifetime.
        val bubble = node(ownPkg, "bubble")
        val dd = node(ddPkg, "dd", windowId = 3)
        val h = harness(
            activeRoot = bubble,
            windows = listOf(window(1, 10, bubble, active = true), uberOverlay(9, 9, node(uberPkg, "uber-offer")), window(3, 2, dd)),
            enabled = setOf(ddPkg),
        )

        assertEquals(listOf("dd"), collect(h, kind).map { it.tree.text })
        assertEquals(0L, h.stats.overlaySnapshotCount())
        assertEquals(0L, h.stats.foregroundSkipCount(ForegroundSkipReason.FRONT_NOT_ENABLED))
    }

    @Test
    fun `bubble active, the notification shade (large, systemui) is not a candidate - DoorDash is read`() = bothKinds { kind ->
        val bubble = node(ownPkg, "bubble")
        val dd = node(ddPkg, "dd", windowId = 3)
        val shade = window(30, 30, node(systemUiPkg, "shade"), windowType = system, bounds = OverlayGeometry.FULL_SCREEN)
        val h = harness(activeRoot = bubble, windows = listOf(shade, window(1, 10, bubble, active = true), window(3, 2, dd)))

        assertEquals(listOf("dd"), collect(h, kind).map { it.tree.text })
    }

    @Test
    fun `BB1 - bubble active, cold cache, an unreadable LARGE system window above DoorDash - refused unreadable`() = bothKinds { kind ->
        // It may be an offer overlay whose owner we cannot verify: never read the window beneath it.
        val bubble = node(ownPkg, "bubble")
        val dd = node(ddPkg, "dd", windowId = 3)
        val unreadable = window(9, 9, null, windowType = system, bounds = OverlayGeometry.UBER_OFFER)
        val h = harness(activeRoot = bubble, windows = listOf(window(1, 10, bubble, active = true), unreadable, window(3, 2, dd)))

        assertTrue(collect(h, kind).isEmpty())
        h.nothingMapped()
        h.skipped(ForegroundSkipReason.FRONT_UNREADABLE)
    }

    @Test
    fun `BB1 - the same unreadable system window when SMALL is skipped without a root fetch`() = bothKinds { kind ->
        val bubble = node(ownPkg, "bubble")
        val dd = node(ddPkg, "dd", windowId = 3)
        val small = window(9, 9, null, windowType = system, bounds = OverlayGeometry.UBER_PUCK)
        val h = harness(activeRoot = bubble, windows = listOf(window(1, 10, bubble, active = true), small, window(3, 2, dd)))

        assertEquals(listOf("dd"), collect(h, kind).map { it.tree.text })
        verify(small, never()).root
    }

    @Test
    fun `BB5 - Uber active under its own offer overlay - every frame is the overlay - dismissed, the map resumes`() {
        val map = node(uberPkg, "uber-map", windowId = 3)
        val mapWindow = window(3, 5, map, active = true)
        val h = harness(activeRoot = map, windows = listOf(mapWindow, uberOverlay(9, 9, node(uberPkg, "uber-offer"))))

        val up = collectSequence(
            h,
            listOf(0L to content(3, uberPkg), 100L to content(9, uberPkg), 250L to content(3, uberPkg), 400L to content(9, uberPkg)),
        ).map { it.tree.text }
        assertTrue("frames while the offer is up: $up", up.isNotEmpty() && up.all { it == "uber-offer" })

        whenever(h.service.windows).thenReturn(listOf(mapWindow)) // the offer is dismissed
        val after = collectSequence(h, listOf(0L to content(3, uberPkg))).map { it.tree.text }
        assertEquals(listOf("uber-map"), after)
    }

    @Test
    fun `DD1 - a SELECTED overlay that fails to map - the frame is skipped, never the covered window`() = bothKinds { kind ->
        // Reverses BB9: a map failure does not prove the overlay left.
        val dd = node(ddPkg, "dd", windowId = 3)
        val h = harness(activeRoot = dd, windows = listOf(window(3, 5, dd, active = true), uberOverlay(9, 9, node(uberPkg, "uber-offer"))))
        doReturn(null).whenever(h.source).getWindowSnapshot(any(), any(), any())

        assertTrue(collect(h, kind, windowId = 9, pkg = uberPkg).isEmpty())
        h.skipped(ForegroundSkipReason.MAP_FAILED)
        verify(h.source, never()).getCurrentRootSnapshot(any<AccessibilityNodeInfo>())
    }

    @Test
    fun `CC4, DD3 - activity 2, overlay 5, NON-active DoorDash sheet 9 - the event path reads the active activity`() = bothKinds { kind ->
        // The same layout WindowsChangedOverlayTest's DD3 case pins at "nothing emitted".
        val dd = node(ddPkg, "dd", windowId = 3)
        val h = harness(
            activeRoot = dd,
            windows = listOf(
                window(3, 2, dd, active = true),
                uberOverlay(9, 5, node(uberPkg, "uber-offer")),
                window(7, 9, node(ddPkg, "dd-sheet")),
            ),
        )

        assertEquals(listOf("dd"), collect(h, kind, windowId = 9, pkg = uberPkg).map { it.tree.text })
        assertEquals(0L, h.stats.overlaySnapshotCount())
    }

    @Test
    fun `CC4 - a foreign application window ABOVE the overlay - never the overlay (event path reads the active root)`() = bothKinds { kind ->
        val dd = node(ddPkg, "dd", windowId = 3)
        val h = harness(
            activeRoot = dd,
            windows = listOf(
                window(3, 2, dd, active = true),
                uberOverlay(9, 5, node(uberPkg, "uber-offer")),
                window(8, 9, node(launcherPkg, "foreign")),
            ),
        )

        assertEquals(listOf("dd"), collect(h, kind, windowId = 9, pkg = uberPkg).map { it.tree.text })
        assertEquals(0L, h.stats.overlaySnapshotCount())
    }

    @Test
    fun `CC4 - bubble active, a foreign application window ABOVE the overlay - refused, another app in front`() = bothKinds { kind ->
        val bubble = node(ownPkg, "bubble")
        val h = harness(
            activeRoot = bubble,
            windows = listOf(
                window(1, 20, bubble, active = true),
                window(8, 9, node(launcherPkg, "foreign")),
                uberOverlay(9, 5, node(uberPkg, "uber-offer")),
                window(3, 2, node(ddPkg, "dd")),
            ),
        )

        assertTrue(collect(h, kind).isEmpty())
        h.skipped(ForegroundSkipReason.FRONT_NOT_ENABLED)
    }

    @Test
    fun `CC7 - the overlay's root is null (animating in) - no frame, then readable - the overlay`() {
        val dd = node(ddPkg, "dd", windowId = 3)
        val uber = node(uberPkg, "uber-offer")
        val overlay = uberOverlay(9, 9, null)
        val h = harness(activeRoot = dd, windows = listOf(window(3, 5, dd, active = true), overlay))

        assertTrue("never the covered DoorDash under an unverifiable window", collect(h, Kind.STATE, windowId = 3).isEmpty())
        h.skipped(ForegroundSkipReason.FRONT_UNREADABLE)

        whenever(overlay.root).thenReturn(uber)
        assertEquals(listOf("uber-offer"), collect(h, Kind.STATE, windowId = 3).map { it.tree.text })
    }

    @Test
    fun `DD6 - an unreadable APPLICATION window above the active DoorDash - not a barrier - the DoorDash frame`() = bothKinds { kind ->
        val dd = node(ddPkg, "dd", windowId = 3)
        val h = harness(
            activeRoot = dd,
            windows = listOf(window(3, 5, dd, active = true), window(8, 9, null), uberOverlay(9, 7, node(uberPkg, "uber-offer"))),
        )

        assertEquals(listOf("dd"), collect(h, kind, windowId = 3).map { it.tree.text })
        assertEquals(0L, h.stats.foregroundSkipCount(ForegroundSkipReason.FRONT_UNREADABLE))
    }

    @Test
    fun `DD6 - an unreadable LARGE system window above the active DoorDash - refused (a possible overlay)`() = bothKinds { kind ->
        val dd = node(ddPkg, "dd", windowId = 3)
        val h = harness(
            activeRoot = dd,
            windows = listOf(window(3, 5, dd, active = true), window(12, 12, null, windowType = system, bounds = OverlayGeometry.FULL_SCREEN)),
        )

        assertTrue(collect(h, kind, windowId = 3).isEmpty())
        h.skipped(ForegroundSkipReason.FRONT_UNREADABLE)
    }

    @Test
    fun `DD7 - a window that throws during the overlay scan - no overlay - the active DoorDash frame`() = bothKinds { kind ->
        val dd = node(ddPkg, "dd", windowId = 3)
        val stale = uberOverlay(9, 9, node(uberPkg, "uber-offer"))
        whenever(stale.isInPictureInPictureMode).thenThrow(IllegalStateException("stale window"))
        val h = harness(activeRoot = dd, windows = listOf(window(3, 5, dd, active = true), stale))

        assertEquals(listOf("dd"), collect(h, kind, windowId = 3).map { it.tree.text })
        assertEquals(0L, h.stats.foregroundSkipCount(ForegroundSkipReason.FRONT_UNREADABLE))
    }

    @Test
    fun `DD7 - bubble path unchanged - a window that throws refuses the frame`() = bothKinds { kind ->
        val bubble = node(ownPkg, "bubble")
        val stale = uberOverlay(9, 9, node(uberPkg, "uber-offer"))
        whenever(stale.isInPictureInPictureMode).thenThrow(IllegalStateException("stale window"))
        val h = harness(activeRoot = bubble, windows = listOf(window(1, 10, bubble, active = true), stale, window(3, 2, node(ddPkg, "dd"))))

        assertTrue(collect(h, kind).isEmpty())
        h.skipped(ForegroundSkipReason.FRONT_UNREADABLE)
    }

    @Test
    fun `DD8 - unknown display area, bubble active - refused NO_DISPLAY_AREA, never walked past`() = bothKinds { kind ->
        val bubble = node(ownPkg, "bubble")
        val h = harness(
            activeRoot = bubble,
            windows = listOf(window(1, 10, bubble, active = true), uberOverlay(9, 9, node(uberPkg, "uber-offer")), window(3, 2, node(ddPkg, "dd"))),
            res = null,
        )

        assertTrue(collect(h, kind).isEmpty())
        h.skipped(ForegroundSkipReason.NO_DISPLAY_AREA)
    }

    @Test
    fun `DD8 - unknown display area, DoorDash active - inconclusive - the active root, counted once`() = bothKinds { kind ->
        val dd = node(ddPkg, "dd", windowId = 3)
        val h = harness(
            activeRoot = dd,
            windows = listOf(window(3, 5, dd, active = true), uberOverlay(9, 9, node(uberPkg, "uber-offer"))),
            res = null,
        )

        assertEquals(listOf("dd"), collect(h, kind, windowId = 3).map { it.tree.text })
        assertEquals(1L, h.stats.overlayRejectedCount(OverlayRejectReason.NO_DISPLAY_AREA))
    }

    @Test
    fun `HH3 - a THROWING root fetch on a size-passing system window is a possible overlay - refused, never read beneath`() = bothKinds { kind ->
        val dd = node(ddPkg, "dd", windowId = 3)
        val overlay = uberOverlay(9, 9, node(uberPkg, "uber-offer"))
        whenever(overlay.root).thenThrow(IllegalStateException("stale window"))
        val h = harness(activeRoot = dd, windows = listOf(window(3, 5, dd, active = true), overlay))

        assertTrue(collect(h, kind, windowId = 3).isEmpty())
        h.skipped(ForegroundSkipReason.FRONT_UNREADABLE)
        assertEquals(1L, h.stats.overlayRejectedCount(OverlayRejectReason.UNREADABLE))
    }

    @Test
    fun `JJ3 - an ACTIVE puck (a small system window) is mapped but never counted as an overlay snapshot`() = bothKinds { kind ->
        val puckRoot = node(uberPkg, "uber-puck", windowId = 10)
        val puck = window(10, 9, puckRoot, windowType = system, active = true, bounds = OverlayGeometry.UBER_PUCK)
        val h = harness(activeRoot = puckRoot, windows = listOf(puck, window(3, 2, node(ddPkg, "dd"))))

        assertEquals(listOf("uber-puck"), collect(h, kind, windowId = 10, pkg = uberPkg).map { it.tree.text })
        assertEquals(0L, h.stats.overlaySnapshotCount())
    }
}
