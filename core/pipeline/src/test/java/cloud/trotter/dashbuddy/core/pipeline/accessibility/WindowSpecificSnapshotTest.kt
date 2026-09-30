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
 * readable window IN FRONT: `TYPE_APPLICATION` windows (never our own bubble) plus platform offer
 * overlays (#1152 — a large `TYPE_SYSTEM` window of an `offerOverlay` platform; every other
 * system-layer window is never a candidate — H1), by layer, the first candidate deciding — an
 * unreadable or non-enabled one refuses the frame. #1152 D5 adds the one "event's own window" read:
 * an enabled overlay platform's event whose window is an overlay ABOVE the active window.
 *
 * Real [AccessibilitySource] over a mocked service (spied, so the path is observable); sdk 36
 * because the node mapper reads the API-36 `getChecked()`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WindowSpecificSnapshotTest {

    private val ownPkg = "cloud.trotter.dashbuddy"
    private val launcherPkg = "com.android.launcher3"
    private val systemUiPkg = "com.android.systemui"
    private val ddPkg = "com.doordash.driverapp"
    private val uberPkg = "com.ubercab.driver"

    private val app = AccessibilityWindowInfo.TYPE_APPLICATION
    private val system = AccessibilityWindowInfo.TYPE_SYSTEM

    private fun node(pkg: String, label: String, windowId: Int = 0): AccessibilityNodeInfo = mock {
        on { packageName } doReturn pkg
        on { text } doReturn label
        on { this.windowId } doReturn windowId
    }

    private fun window(
        windowId: Int,
        windowLayer: Int,
        root: AccessibilityNodeInfo?,
        windowType: Int = app,
        active: Boolean = false,
        pip: Boolean = false,
        bounds: Rect? = null, // null → zero-size (getBoundsInScreen unstubbed)
    ): AccessibilityWindowInfo {
        val w = mock<AccessibilityWindowInfo> {
            on { isInPictureInPictureMode } doReturn pip
            on { id } doReturn windowId
            on { layer } doReturn windowLayer
            on { this.root } doReturn root
            on { isActive } doReturn active
            on { type } doReturn windowType
        }
        return if (bounds != null) withBounds(w, bounds) else w
    }

    /** The Uber offer overlay (#248 geometry): TYPE_SYSTEM, ~92 % of the display. */
    private fun uberOverlay(windowId: Int, windowLayer: Int, root: AccessibilityNodeInfo?, active: Boolean = false) =
        window(windowId, windowLayer, root, windowType = system, active = active, bounds = OverlayGeometry.UBER_OFFER)

    private class Harness(
        val service: AccessibilityService,
        val source: AccessibilitySource,
        val events: MutableSharedFlow<AccEvent>,
        val prefs: FakePlatformPreferences,
        val stats: PipelineStats = PipelineStats(),
    )

    private fun harness(
        activeRoot: AccessibilityNodeInfo?,
        windows: List<AccessibilityWindowInfo>,
        enabled: Set<String> = setOf(ddPkg, uberPkg),
    ): Harness {
        val res = displayResources()
        val service = mock<AccessibilityService> {
            on { rootInActiveWindow } doReturn activeRoot
            on { this.windows } doReturn windows
            on { packageName } doReturn ownPkg
            on { resources } doReturn res
        }
        val events = MutableSharedFlow<AccEvent>(extraBufferCapacity = 4)
        val stats = PipelineStats()
        val source = spy(AccessibilitySource(stats).apply { registerService(service) })
        doReturn(events).whenever(source).events
        return Harness(service, source, events, FakePlatformPreferences(enabled), stats)
    }

    private fun event(type: Int, windowId: Int, pkg: String = ddPkg) = AccEvent(
        type = type,
        windowId = windowId,
        packageName = pkg,
        className = "android.widget.FrameLayout",
        contentChangeTypes = 1,
        eventTimeMs = 0L,
    )

    private enum class Kind(val type: Int) {
        CONTENT(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED),
        STATE(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED),
    }

    private fun output(
        kind: Kind,
        source: AccessibilitySource,
        prefs: FakePlatformPreferences,
        stats: PipelineStats = PipelineStats(),
    ): Flow<TreeSnapshot> =
        when (kind) {
            Kind.CONTENT -> ContentChangedPipeline(source, prefs, stats).output()
            Kind.STATE -> StateChangedPipeline(source, prefs, stats).output()
        }

    private fun collect(h: Harness, kind: Kind, windowId: Int = 3, pkg: String = ddPkg): List<TreeSnapshot> =
        collectWith(h.events, output(kind, h.source, h.prefs, h.stats), event(kind.type, windowId, pkg))

    private fun Harness.skipped(reason: ForegroundSkipReason) =
        assertEquals("counted as $reason (H3)", 1L, stats.foregroundSkipCount(reason))

    private fun collectWith(
        events: MutableSharedFlow<AccEvent>,
        pipelineOutput: Flow<TreeSnapshot>,
        event: AccEvent,
    ): List<TreeSnapshot> {
        val emitted = mutableListOf<TreeSnapshot>()
        runTest {
            val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                pipelineOutput.collect { emitted += it }
            }
            advanceUntilIdle()
            assertTrue("test harness: event must enter the shared flow", events.tryEmit(event))
            // The content pipeline coalesces on background-scope timers, which advanceUntilIdle()
            // does not drive — advance virtual time explicitly.
            advanceTimeBy(1_000)
            runCurrent()
            job.cancel()
        }
        return emitted
    }

    private fun bothKinds(block: (Kind) -> Unit) = Kind.entries.forEach(block)

    private fun Harness.nothingMapped() {
        verify(source, never()).getCurrentRootSnapshot(any<AccessibilityNodeInfo>())
        verify(source, never()).getWindowSnapshot(any(), any(), any())
    }

    @Test
    fun `null active root - nothing mapped, nothing enumerated`() = bothKinds { kind ->
        val dd = node(ddPkg, "dd")
        val h = harness(activeRoot = null, windows = listOf(window(3, 2, dd)))

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
        verify(h.service, times(1)).rootInActiveWindow
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

    // --- #1152: platform offer overlays (a11y TYPE_SYSTEM) -------------------------------------

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
    fun `the overlay with focus is read through the active-root path, not the overlay branch`() = bothKinds { kind ->
        val uber = node(uberPkg, "uber-offer", windowId = 9)
        val dd = node(ddPkg, "dd", windowId = 3)
        val h = harness(activeRoot = uber, windows = listOf(uberOverlay(9, 9, uber, active = true), window(3, 5, dd)))

        val emitted = collect(h, kind, windowId = 9, pkg = uberPkg)

        assertEquals(listOf("uber-offer"), emitted.map { it.tree.text })
        assertEquals("active-root path carries no WindowContext (H4)", null, emitted.single().windowContext)
        assertEquals(0L, h.stats.overlaySnapshotCount())
    }

    @Test
    fun `BB2 - no flagged active window - the ordering is unverifiable - the active root is read`() = bothKinds { kind ->
        val dd = node(ddPkg, "dd", windowId = 3)
        val uber = node(uberPkg, "uber-offer")
        val h = harness(activeRoot = dd, windows = listOf(window(3, 5, dd), uberOverlay(9, 9, uber)))

        assertEquals(listOf("dd"), collect(h, kind, windowId = 9, pkg = uberPkg).map { it.tree.text })
        assertEquals(0L, h.stats.overlaySnapshotCount())
    }

    @Test
    fun `BB2 - the active root's window is absent from the enumeration - the active root is read`() = bothKinds { kind ->
        val dd = node(ddPkg, "dd", windowId = 42) // not enumerated
        val uber = node(uberPkg, "uber-offer")
        val h = harness(activeRoot = dd, windows = listOf(window(3, 5, node(ddPkg, "dd-other"), active = true), uberOverlay(9, 9, uber)))

        assertEquals(listOf("dd"), collect(h, kind, windowId = 9, pkg = uberPkg).map { it.tree.text })
        assertEquals(0L, h.stats.overlaySnapshotCount())
    }

    @Test
    fun `BB2 - focus moved to our bubble between the reads - a LOWER overlay is never returned`() = bothKinds { kind ->
        // rootInActiveWindow still says DoorDash (window 3, layer 12); the enumeration flags our
        // bubble. The overlay (layer 9) is BELOW DoorDash — the old own-active waiver returned it.
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

    /** Emits [seq] (virtual-time offset ms → event) into a CONTENT pipeline and collects every frame. */
    private fun collectSequence(h: Harness, seq: List<Pair<Long, AccEvent>>): List<TreeSnapshot> {
        val emitted = mutableListOf<TreeSnapshot>()
        runTest {
            val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                output(Kind.CONTENT, h.source, h.prefs, h.stats).collect { emitted += it }
            }
            advanceUntilIdle()
            var now = 0L
            for ((at, e) in seq) {
                advanceTimeBy(at - now)
                runCurrent()
                now = at
                assertTrue("test harness: event must enter the shared flow", h.events.tryEmit(e))
                runCurrent()
            }
            advanceTimeBy(1_000)
            runCurrent()
            job.cancel()
        }
        return emitted
    }

    private fun content(windowId: Int, pkg: String) = event(Kind.CONTENT.type, windowId, pkg)

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
    fun `CC4 - an enabled application sheet ABOVE the overlay - the overlay is not frontmost - the active root`() = bothKinds { kind ->
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
}
