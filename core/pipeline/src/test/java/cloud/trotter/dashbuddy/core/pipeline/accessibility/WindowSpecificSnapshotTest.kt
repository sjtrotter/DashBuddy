package cloud.trotter.dashbuddy.core.pipeline.accessibility

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import cloud.trotter.dashbuddy.core.pipeline.accessibility.event.type.window.content_changed.ContentChangedPipeline
import cloud.trotter.dashbuddy.core.pipeline.accessibility.event.type.window.state_changed.StateChangedPipeline
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
 * readable window IN FRONT: `TYPE_APPLICATION` windows only (never our own bubble; system-layer
 * windows are never candidates — H1, overlays are #1152), by layer, the first candidate deciding —
 * an unreadable or non-enabled one refuses the frame.
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

    private fun node(pkg: String, label: String): AccessibilityNodeInfo = mock {
        on { packageName } doReturn pkg
        on { text } doReturn label
    }

    private fun window(
        windowId: Int,
        windowLayer: Int,
        root: AccessibilityNodeInfo?,
        windowType: Int = app,
        active: Boolean = false,
        pip: Boolean = false,
    ): AccessibilityWindowInfo = mock {
        on { isInPictureInPictureMode } doReturn pip
        on { id } doReturn windowId
        on { layer } doReturn windowLayer
        on { this.root } doReturn root
        on { isActive } doReturn active
        on { type } doReturn windowType
    }

    private class Harness(
        val service: AccessibilityService,
        val source: AccessibilitySource,
        val events: MutableSharedFlow<AccEvent>,
        val prefs: FakePlatformPreferences,
    )

    private fun harness(
        activeRoot: AccessibilityNodeInfo?,
        windows: List<AccessibilityWindowInfo>,
        enabled: Set<String> = setOf(ddPkg, uberPkg),
    ): Harness {
        val service = mock<AccessibilityService> {
            on { rootInActiveWindow } doReturn activeRoot
            on { this.windows } doReturn windows
            on { packageName } doReturn ownPkg
        }
        val events = MutableSharedFlow<AccEvent>(extraBufferCapacity = 4)
        val source = spy(AccessibilitySource().apply { registerService(service) })
        doReturn(events).whenever(source).events
        return Harness(service, source, events, FakePlatformPreferences(enabled))
    }

    private fun event(type: Int, windowId: Int) = AccEvent(
        type = type,
        windowId = windowId,
        packageName = ddPkg,
        className = "android.widget.FrameLayout",
        contentChangeTypes = 1,
        eventTimeMs = 0L,
    )

    private enum class Kind(val type: Int) {
        CONTENT(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED),
        STATE(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED),
    }

    private fun output(kind: Kind, source: AccessibilitySource, prefs: FakePlatformPreferences): Flow<TreeSnapshot> =
        when (kind) {
            Kind.CONTENT -> ContentChangedPipeline(source, prefs).output()
            Kind.STATE -> StateChangedPipeline(source, prefs).output()
        }

    private fun collect(h: Harness, kind: Kind, windowId: Int = 3): List<TreeSnapshot> =
        collectWith(h.events, output(kind, h.source, h.prefs), event(kind.type, windowId))

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
    }

    @Test
    fun `watched sheet active over its activity - the active root only`() = bothKinds { kind ->
        val sheet = node(ddPkg, "dd-sheet")
        val activity = node(ddPkg, "dd-activity")
        val h = harness(activeRoot = sheet, windows = listOf(window(7, 5, sheet, active = true), window(3, 2, activity)))

        val emitted = collect(h, kind, windowId = 3) // fired by the hidden activity

        assertEquals(listOf("dd-sheet"), emitted.map { it.tree.text })
        verify(h.source, never()).foregroundWindow(any())
        verify(h.source, never()).getWindowSnapshot(any(), any(), any())
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
    fun `a system-layer window is never a candidate and never has its root fetched (H1)`() = bothKinds { kind ->
        // Even an ENABLED platform's own system-layer window (a transient toast, an overlay — #1152).
        val bubble = node(ownPkg, "bubble")
        val dd = node(ddPkg, "dd")
        val uber = node(uberPkg, "uber-offer")
        val uberWindow = window(9, 9, uber, windowType = system)
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
    fun `post-map package re-check still drops a swapped root`() {
        val events = MutableSharedFlow<AccEvent>(extraBufferCapacity = 4)
        val bubble = node(ownPkg, "bubble")
        val located = AccessibilitySource.LocatedWindow(mock(), mock(), 2)
        val source = mock<AccessibilitySource> {
            on { this.events } doReturn events
            on { getLiveNativeRoot() } doReturn bubble
            on { foregroundWindow(any()) } doReturn located
            on { getWindowSnapshot(any(), any(), any()) } doReturn
                AccessibilitySource.RootSnapshot(tree = UiNode(text = "bubble"), packageName = ownPkg)
        }

        val emitted = collectWith(
            events, ContentChangedPipeline(source, FakePlatformPreferences(setOf(ddPkg))).output(),
            event(Kind.CONTENT.type, windowId = 3),
        )

        assertTrue("a root that swapped to our own package must never be emitted", emitted.isEmpty())
    }
}
