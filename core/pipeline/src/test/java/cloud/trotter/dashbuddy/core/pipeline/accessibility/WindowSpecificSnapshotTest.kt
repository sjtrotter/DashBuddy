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
 * #1148 D4 as reworked by review F1 — which window a content/state frame is read from.
 *
 * The active WATCHED window is the ground truth (a DoorDash sheet over the DoorDash activity
 * included — the hidden activity keeps firing content changes with ITS window id, and must never be
 * snapshotted: that interleaves obscured frames and flaps R0). Only when a NON-watched window is
 * active (our bubble, the launcher) is the TOPMOST watched application window read instead — never
 * the event's own window. The event is a trigger only.
 *
 * Real [AccessibilitySource] over a mocked service (spied, so the resolution path is observable);
 * sdk 36 because the node mapper reads the API-36 `getChecked()`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WindowSpecificSnapshotTest {

    private val overlayPkg = "cloud.trotter.dashbuddy"
    private val launcherPkg = "com.android.launcher3"
    private val ddPkg = "com.doordash.driverapp" // Platform.watchedPackages member

    private fun node(pkg: String, label: String): AccessibilityNodeInfo = mock {
        on { packageName } doReturn pkg
        on { text } doReturn label
    }

    private fun window(
        windowId: Int,
        windowLayer: Int,
        root: AccessibilityNodeInfo?,
        active: Boolean = false,
        windowType: Int = AccessibilityWindowInfo.TYPE_APPLICATION,
    ): AccessibilityWindowInfo = mock {
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
    )

    private fun harness(activeRoot: AccessibilityNodeInfo?, windows: List<AccessibilityWindowInfo>): Harness {
        val service = mock<AccessibilityService> {
            on { rootInActiveWindow } doReturn activeRoot
            on { this.windows } doReturn windows
        }
        val events = MutableSharedFlow<AccEvent>(extraBufferCapacity = 4)
        val source = spy(AccessibilitySource().apply { registerService(service) })
        doReturn(events).whenever(source).events
        return Harness(service, source, events)
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

    private fun output(kind: Kind, source: AccessibilitySource): Flow<TreeSnapshot> = when (kind) {
        Kind.CONTENT -> ContentChangedPipeline(source).output()
        Kind.STATE -> StateChangedPipeline(source).output()
    }

    private fun collect(h: Harness, kind: Kind, windowId: Int): List<TreeSnapshot> =
        collectWith(h.events, output(kind, h.source), event(kind.type, windowId))

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

    // (a) overlay active, one DoorDash window → the frame is read from it
    private fun overlayActiveReadsTheWatchedWindow(kind: Kind) {
        val overlayRoot = node(overlayPkg, "bubble")
        val ddRoot = node(ddPkg, "dd-activity")
        val h = harness(
            activeRoot = overlayRoot,
            windows = listOf(window(1, 10, overlayRoot, active = true), window(3, 2, ddRoot)),
        )

        val emitted = collect(h, kind, windowId = 3)

        verify(h.source, never()).getCurrentRootSnapshot()
        verify(h.source, times(1)).getWindowSnapshot(any(), any(), any())
        assertEquals("the watched window must produce a frame while the bubble is active", 1, emitted.size)
        val snap = emitted.single()
        assertEquals(ddPkg, snap.packageName)
        assertEquals("dd-activity", snap.tree.text)
        assertEquals("WindowContext rides every snapshot", 3, snap.windowContext?.windowId)
        val trigger = requireNotNull(snap.trigger)
        assertEquals(
            if (kind == Kind.CONTENT) TreeSnapshot.Trigger.Reason.CONTENT else TreeSnapshot.Trigger.Reason.STATE,
            trigger.reason,
        )
        assertEquals(1, trigger.coalescedEvents)
    }

    @Test fun `content - overlay active, the watched window is snapshotted`() = overlayActiveReadsTheWatchedWindow(Kind.CONTENT)
    @Test fun `state - overlay active, the watched window is snapshotted`() = overlayActiveReadsTheWatchedWindow(Kind.STATE)

    // (b) DoorDash dialog D active, event from the OBSCURED activity A → the active root, never A
    private fun obscuredActivityIsNeverSnapshotted(kind: Kind) {
        val dialogRoot = node(ddPkg, "dd-dialog")
        val activityRoot = node(ddPkg, "dd-activity")
        val h = harness(
            activeRoot = dialogRoot,
            windows = listOf(window(7, 5, dialogRoot, active = true), window(3, 2, activityRoot)),
        )

        val emitted = collect(h, kind, windowId = 3) // the hidden activity's window id

        verify(h.source, times(1)).getCurrentRootSnapshot()
        verify(h.source, never()).topmostWindow(any())
        verify(h.source, never()).getWindowSnapshot(any(), any(), any())
        assertEquals(1, emitted.size)
        assertEquals("the active watched sheet is the ground truth", "dd-dialog", emitted.single().tree.text)
    }

    @Test fun `content - obscured activity under an active watched sheet is never snapshotted`() =
        obscuredActivityIsNeverSnapshotted(Kind.CONTENT)
    @Test fun `state - obscured activity under an active watched sheet is never snapshotted`() =
        obscuredActivityIsNeverSnapshotted(Kind.STATE)

    // (c) overlay active, two watched windows → the TOPMOST (highest layer), whatever fired
    private fun overlayActivePicksTheTopmostWatchedWindow(kind: Kind) {
        val overlayRoot = node(overlayPkg, "bubble")
        val dialogRoot = node(ddPkg, "dd-dialog")
        val activityRoot = node(ddPkg, "dd-activity")
        val h = harness(
            activeRoot = overlayRoot,
            windows = listOf(
                window(1, 10, overlayRoot, active = true),
                window(3, 2, activityRoot),
                window(7, 5, dialogRoot),
            ),
        )

        val emitted = collect(h, kind, windowId = 3) // fired by the lower activity

        assertEquals(1, emitted.size)
        assertEquals("dd-dialog", emitted.single().tree.text)
        assertEquals(7, emitted.single().windowContext?.windowId)
    }

    @Test fun `content - overlay active, topmost watched window wins`() = overlayActivePicksTheTopmostWatchedWindow(Kind.CONTENT)
    @Test fun `state - overlay active, topmost watched window wins`() = overlayActivePicksTheTopmostWatchedWindow(Kind.STATE)

    // (d) launcher active, no watched window → nothing mapped, nothing emitted
    private fun noWatchedWindowEmitsNothing(kind: Kind) {
        val launcherRoot = node(launcherPkg, "home")
        val h = harness(activeRoot = launcherRoot, windows = listOf(window(1, 1, launcherRoot, active = true)))

        val emitted = collect(h, kind, windowId = 1)

        verify(h.source, never()).getCurrentRootSnapshot()
        verify(h.source, never()).getWindowSnapshot(any(), any(), any())
        assertTrue(emitted.isEmpty())
    }

    @Test fun `content - no watched window emits nothing`() = noWatchedWindowEmitsNothing(Kind.CONTENT)
    @Test fun `state - no watched window emits nothing`() = noWatchedWindowEmitsNothing(Kind.STATE)

    // (e) post-map re-check: the located root swapped to our own package → dropped (#4)
    @Test
    fun `content - post-map package re-check still drops a swapped root`() {
        val events = MutableSharedFlow<AccEvent>(extraBufferCapacity = 4)
        val located = AccessibilitySource.LocatedWindow(mock(), mock(), 2)
        val source = mock<AccessibilitySource> {
            on { this.events } doReturn events
            on { getActiveWindowPackage() } doReturn overlayPkg
            on { topmostWindow(any()) } doReturn located
            on { getWindowSnapshot(any(), any(), any()) } doReturn
                AccessibilitySource.RootSnapshot(tree = UiNode(text = "bubble"), packageName = overlayPkg)
        }

        val emitted = collectWith(
            events, ContentChangedPipeline(source).output(),
            event(Kind.CONTENT.type, windowId = 3),
        )

        assertTrue("a root that swapped to our own package must never be emitted", emitted.isEmpty())
    }

    // (f) the topmost path enumerates windows ONCE and fetches each root ONCE per frame
    @Test
    fun `state - topmost path enumerates windows and fetches roots once per frame`() {
        val overlayRoot = node(overlayPkg, "bubble")
        val ddRoot = node(ddPkg, "dd-activity")
        val overlayWindow = window(1, 10, overlayRoot, active = true)
        val ddWindow = window(3, 2, ddRoot)
        val h = harness(activeRoot = overlayRoot, windows = listOf(overlayWindow, ddWindow))

        val emitted = collect(h, Kind.STATE, windowId = 3)

        assertEquals(1, emitted.size)
        verify(h.service, times(1)).windows
        verify(ddWindow, times(1)).root
        verify(overlayWindow, times(1)).root
    }
}
