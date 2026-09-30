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
 * #1148 D4 as reworked by review F1 + G5 — which window a content/state frame is read from.
 *
 * The event is a TRIGGER only. One active-root read: null → nothing; an ENABLED package → that
 * root (a sheet over its activity included — the hidden activity is never read); otherwise the
 * readable window IN FRONT, by layer, the first candidate deciding — an unreadable or non-enabled
 * one refuses the frame. The #1152 overlay rules live in [OverlayEventPathTest],
 * [OverlayVerdictShapesTest], [ActiveResolutionTest] and `WindowsChangedOverlayTest` (review HH7).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WindowSpecificSnapshotTest : WindowResolverTestBase() {

    @Test
    fun `null active root - nothing mapped, nothing enumerated`() = bothKinds { kind ->
        // DoorDash-only: no overlay platform enabled → the #1148 path, no enumeration (H4).
        val dd = node(ddPkg, "dd")
        val h = harness(activeRoot = null, windows = listOf(window(3, 2, dd)), enabled = setOf(ddPkg))

        assertTrue(collect(h, kind).isEmpty())
        h.nothingMapped()
        verify(h.service, never()).windows
        h.skipped(ForegroundSkipReason.NO_ACTIVE_ROOT)
    }

    @Test
    fun `watched sheet active over its activity - the active root only`() = bothKinds { kind ->
        val sheet = node(ddPkg, "dd-sheet")
        val activity = node(ddPkg, "dd-activity")
        // DoorDash-only (no overlay platform enabled): the #1152 BB5 overlay check's pre-gate keeps
        // this path enumeration-free (H4).
        val h = harness(
            activeRoot = sheet,
            windows = listOf(window(7, 5, sheet, active = true), window(3, 2, activity)),
            enabled = setOf(ddPkg),
        )

        val emitted = collect(h, kind, windowId = 3) // fired by the hidden activity

        assertEquals(listOf("dd-sheet"), emitted.map { it.tree.text })
        verify(h.source, never()).foregroundWindow(any())
        verify(h.source, never()).getWindowSnapshot(any(), any(), any())
        verify(h.service, never()).windows // H4: no enumeration on the active-root path
        assertEquals(null, emitted.single().windowContext)
        val trigger = requireNotNull(emitted.single().trigger)
        assertEquals(
            if (kind == Kind.CONTENT) TreeSnapshot.Trigger.Reason.CONTENT else TreeSnapshot.Trigger.Reason.STATE,
            trigger.reason,
        )
    }

    @Test
    fun `our bubble active over DoorDash - DoorDash is read`() = bothKinds { kind ->
        val bubble = node(ownPkg, "bubble")
        val dd = node(ddPkg, "dd")
        val h = harness(activeRoot = bubble, windows = listOf(window(1, 10, bubble, active = true), window(3, 2, dd)))

        val emitted = collect(h, kind)

        assertEquals(listOf("dd"), emitted.map { it.tree.text })
        assertEquals(3, emitted.single().windowContext?.windowId)
        verify(h.source, never()).getCurrentRootSnapshot(any<AccessibilityNodeInfo>())
    }

    @Test
    fun `a small system-layer window is never a candidate and never has its root fetched (H1)`() = bothKinds { kind ->
        // Even an ENABLED overlay platform's own system-layer window, when it is small (the puck,
        // a toast) — #1152 D2 refuses it on size, before any root fetch.
        val bubble = node(ownPkg, "bubble")
        val dd = node(ddPkg, "dd")
        val uber = node(uberPkg, "uber-puck")
        val uberWindow = window(9, 9, uber, windowType = system, bounds = OverlayGeometry.UBER_PUCK)
        val h = harness(
            activeRoot = bubble,
            windows = listOf(window(1, 10, bubble, active = true), window(3, 2, dd), uberWindow),
        )

        assertEquals(listOf("dd"), collect(h, kind).map { it.tree.text })
        verify(uberWindow, never()).root
    }

    @Test
    fun `bubble active, unreadable application window above DoorDash - refused`() = bothKinds { kind ->
        val bubble = node(ownPkg, "bubble")
        val dd = node(ddPkg, "dd")
        val h = harness(
            activeRoot = bubble,
            windows = listOf(window(1, 10, bubble, active = true), window(5, 6, null), window(3, 2, dd)),
        )

        assertTrue("never fall through below an unreadable top window", collect(h, kind).isEmpty())
        h.nothingMapped()
        h.skipped(ForegroundSkipReason.FRONT_UNREADABLE)
    }

    @Test
    fun `bubble active, a picture-in-picture Maps window above DoorDash is skipped (H2)`() = bothKinds { kind ->
        val bubble = node(ownPkg, "bubble")
        val maps = node("com.google.android.apps.maps", "maps-pip")
        val dd = node(ddPkg, "dd")
        val mapsWindow = window(8, 9, maps, pip = true)
        val h = harness(
            activeRoot = bubble,
            windows = listOf(window(1, 10, bubble, active = true), mapsWindow, window(3, 2, dd)),
        )

        assertEquals(listOf("dd"), collect(h, kind).map { it.tree.text })
        verify(mapsWindow, never()).root
    }

    @Test
    fun `bubble active, launcher above DoorDash - refused`() = bothKinds { kind ->
        val bubble = node(ownPkg, "bubble")
        val launcher = node(launcherPkg, "home")
        val dd = node(ddPkg, "dd")
        val h = harness(
            activeRoot = bubble,
            windows = listOf(window(1, 10, bubble, active = true), window(4, 6, launcher), window(3, 2, dd)),
        )

        assertTrue(collect(h, kind).isEmpty())
        h.nothingMapped()
        h.skipped(ForegroundSkipReason.FRONT_NOT_ENABLED)
    }

    @Test
    fun `the status bar (system, systemui or unreadable) is never a candidate`() = bothKinds { kind ->
        val bubble = node(ownPkg, "bubble")
        val statusBar = node(systemUiPkg, "status")
        val dd = node(ddPkg, "dd")
        val statusWindow = window(20, 30, statusBar, windowType = system)
        val h = harness(
            activeRoot = bubble,
            windows = listOf(
                statusWindow,
                window(21, 29, null, windowType = system),
                window(1, 10, bubble, active = true),
                window(3, 2, dd),
            ),
        )

        assertEquals(listOf("dd"), collect(h, kind).map { it.tree.text })
        verify(statusWindow, never()).root
    }

    @Test
    fun `the foreground path enumerates windows and fetches each root once per frame`() {
        val bubble = node(ownPkg, "bubble")
        val dd = node(ddPkg, "dd")
        val bubbleWindow = window(1, 10, bubble, active = true)
        val ddWindow = window(3, 2, dd)
        val h = harness(activeRoot = bubble, windows = listOf(bubbleWindow, ddWindow))

        assertEquals(1, collect(h, Kind.STATE).size)
        verify(h.service, times(1)).windows
        // FF1: with an overlay platform enabled the active window and its root come from the ONE
        // enumeration — no separate rootInActiveWindow read.
        verify(h.service, never()).rootInActiveWindow
        verify(ddWindow, times(1)).root
        verify(bubbleWindow, times(1)).root
    }

    @Test
    fun `post-map invariant drops a snapshot whose package is not enabled (belt-and-braces, H5)`() {
        val events = MutableSharedFlow<AccEvent>(extraBufferCapacity = 4)
        val bubble = node(ownPkg, "bubble")
        val located = AccessibilitySource.LocatedWindow(mock(), mock(), 2)
        val source = mock<AccessibilitySource> {
            on { this.events } doReturn events
            on { getLiveNativeRoot() } doReturn bubble
            on { foregroundWindow(any()) } doReturn AccessibilitySource.Foreground.Found(located)
            on { getWindowSnapshot(any(), any(), any()) } doReturn
                AccessibilitySource.RootSnapshot(tree = UiNode(text = "bubble"), packageName = ownPkg)
        }

        val stats = PipelineStats()
        val emitted = collectWith(
            events, ContentChangedPipeline(source, FakePlatformPreferences(setOf(ddPkg)), stats).output(),
            event(Kind.CONTENT.type, windowId = 3),
        )

        assertTrue("a snapshot attributed to a non-enabled package must never be emitted", emitted.isEmpty())
        // Round 4: the invariant has its OWN census reason, so a firing is visible instead of hiding
        // under the legitimate "another app is in front" count.
        assertEquals(1L, stats.foregroundSkipCount(ForegroundSkipReason.POST_MAP_MISMATCH))
        assertEquals(0L, stats.foregroundSkipCount(ForegroundSkipReason.FRONT_NOT_ENABLED))
    }
}
