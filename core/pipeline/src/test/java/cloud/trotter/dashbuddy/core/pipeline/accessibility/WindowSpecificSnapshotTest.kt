package cloud.trotter.dashbuddy.core.pipeline.accessibility

import android.view.accessibility.AccessibilityEvent
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
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.robolectric.RobolectricTestRunner

/**
 * #1148 D4 — the content- and state-change pipelines snapshot the EVENT's window, not the active
 * window: with our bubble active, a DoorDash change in window W must still produce a frame from W.
 * Pins the resolution order: (1) W's package pre-map → W's snapshot; (2) unavailable window or
 * `windowId = -1` → active-root fallback; a non-target W is skipped before any mapping.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class WindowSpecificSnapshotTest {

    private val bubblePkg = "cloud.trotter.dashbuddy"
    private val nonTargetPkg = "com.android.launcher3"
    private val targetPkg = "com.doordash.driverapp" // Platform.watchedPackages member
    private val windowW = 42

    private val windowContext = TreeSnapshot.WindowContext(
        windowId = windowW, windowType = 1, windowTitle = null, windowLayer = 3,
        isActive = false, isFocused = false, totalWindowCount = 3,
    )

    private fun event(type: Int, windowId: Int) = AccEvent(
        type = type,
        windowId = windowId,
        packageName = targetPkg,
        className = "android.widget.FrameLayout",
        contentChangeTypes = 1,
        windowChanges = 0,
        eventTimeMs = 0L,
    )

    private fun snapshotOf(pkg: String, ctx: TreeSnapshot.WindowContext? = null) =
        AccessibilitySource.RootSnapshot(tree = UiNode(text = "root-$pkg"), packageName = pkg, windowContext = ctx)

    private fun sourceWith(
        events: MutableSharedFlow<AccEvent>,
        activePkg: String,
        activeSnapshot: AccessibilitySource.RootSnapshot?,
        windowPkg: String?,
        windowSnapshot: AccessibilitySource.RootSnapshot?,
    ): AccessibilitySource = mock {
        on { this.events } doReturn events
        on { getActiveWindowPackage() } doReturn activePkg
        on { getCurrentRootSnapshot() } doReturn activeSnapshot
        on { getWindowPackage(any()) } doReturn windowPkg
        on { getWindowSnapshot(any()) } doReturn windowSnapshot
    }

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
            // #1148: the content pipeline now coalesces (quiet 150 ms) on timers in the
            // BACKGROUND scope, which advanceUntilIdle() does not drive — advance virtual time.
            advanceTimeBy(1_000)
            runCurrent()
            job.cancel()
        }
        return emitted
    }

    private enum class Kind(val type: Int) {
        CONTENT(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED),
        STATE(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED),
    }

    private fun output(kind: Kind, source: AccessibilitySource): Flow<TreeSnapshot> = when (kind) {
        Kind.CONTENT -> ContentChangedPipeline(source).output()
        Kind.STATE -> StateChangedPipeline(source).output()
    }

    // (a) bubble active, W is DoorDash → snapshot from W, active root never read
    private fun bubbleActiveEventWindowIsSnapshotted(kind: Kind) {
        val events = MutableSharedFlow<AccEvent>(extraBufferCapacity = 4)
        val wSnapshot = snapshotOf(targetPkg, windowContext)
        val source = sourceWith(
            events, activePkg = bubblePkg, activeSnapshot = snapshotOf(bubblePkg),
            windowPkg = targetPkg, windowSnapshot = wSnapshot,
        )

        val emitted = collectWith(events, output(kind, source), event(kind.type, windowW))

        verify(source, times(1)).getWindowSnapshot(windowW)
        verify(source, never()).getCurrentRootSnapshot()
        assertEquals("the event's window must produce a frame while the bubble is active", 1, emitted.size)
        val snap = emitted.single()
        assertEquals(targetPkg, snap.packageName)
        assertSame(wSnapshot.tree, snap.tree)
        assertEquals("WindowContext rides every snapshot", windowContext, snap.windowContext)
        val trigger = requireNotNull(snap.trigger)
        assertEquals(
            if (kind == Kind.CONTENT) TreeSnapshot.Trigger.Reason.CONTENT else TreeSnapshot.Trigger.Reason.STATE,
            trigger.reason,
        )
        assertEquals(1, trigger.coalescedEvents)
    }

    @Test fun `content - bubble active, event window W is snapshotted`() = bubbleActiveEventWindowIsSnapshotted(Kind.CONTENT)
    @Test fun `state - bubble active, event window W is snapshotted`() = bubbleActiveEventWindowIsSnapshotted(Kind.STATE)

    // (b) windowId = -1 → active-root fallback
    private fun noWindowIdFallsBackToActiveRoot(kind: Kind) {
        val events = MutableSharedFlow<AccEvent>(extraBufferCapacity = 4)
        val source = sourceWith(
            events, activePkg = targetPkg, activeSnapshot = snapshotOf(targetPkg),
            windowPkg = targetPkg, windowSnapshot = snapshotOf(targetPkg, windowContext),
        )

        val emitted = collectWith(events, output(kind, source), event(kind.type, -1))

        verify(source, never()).getWindowPackage(any())
        verify(source, never()).getWindowSnapshot(any())
        verify(source, times(1)).getCurrentRootSnapshot()
        assertEquals(1, emitted.size)
    }

    @Test fun `content - no window id falls back to the active root`() = noWindowIdFallsBackToActiveRoot(Kind.CONTENT)
    @Test fun `state - no window id falls back to the active root`() = noWindowIdFallsBackToActiveRoot(Kind.STATE)

    // (c) window W gone (snapshot null) → active-root fallback
    private fun goneWindowFallsBackToActiveRoot(kind: Kind) {
        val events = MutableSharedFlow<AccEvent>(extraBufferCapacity = 4)
        val source = sourceWith(
            events, activePkg = targetPkg, activeSnapshot = snapshotOf(targetPkg),
            windowPkg = targetPkg, windowSnapshot = null,
        )

        val emitted = collectWith(events, output(kind, source), event(kind.type, windowW))

        verify(source, times(1)).getWindowSnapshot(windowW)
        verify(source, times(1)).getCurrentRootSnapshot()
        assertEquals("fallback must still produce the frame", 1, emitted.size)
    }

    @Test fun `content - gone event window falls back to the active root`() = goneWindowFallsBackToActiveRoot(Kind.CONTENT)
    @Test fun `state - gone event window falls back to the active root`() = goneWindowFallsBackToActiveRoot(Kind.STATE)

    // (d) W's package non-target → skipped pre-map, nothing mapped at all
    private fun nonTargetWindowSkippedPreMap(kind: Kind) {
        val events = MutableSharedFlow<AccEvent>(extraBufferCapacity = 4)
        val source = sourceWith(
            events, activePkg = targetPkg, activeSnapshot = snapshotOf(targetPkg),
            windowPkg = nonTargetPkg, windowSnapshot = snapshotOf(nonTargetPkg),
        )

        val emitted = collectWith(events, output(kind, source), event(kind.type, windowW))

        verify(source, never()).getWindowSnapshot(any())
        verify(source, never()).getCurrentRootSnapshot()
        assertTrue("a non-target event window emits nothing", emitted.isEmpty())
    }

    @Test fun `content - non-target event window is skipped before mapping`() = nonTargetWindowSkippedPreMap(Kind.CONTENT)
    @Test fun `state - non-target event window is skipped before mapping`() = nonTargetWindowSkippedPreMap(Kind.STATE)

    // Post-map re-check: W's root swapped to a non-target package between the reads → dropped (#4)
    @Test
    fun `content - post-map package re-check still drops a swapped root`() {
        val events = MutableSharedFlow<AccEvent>(extraBufferCapacity = 4)
        val source = sourceWith(
            events, activePkg = targetPkg, activeSnapshot = snapshotOf(targetPkg),
            windowPkg = targetPkg, windowSnapshot = snapshotOf(bubblePkg),
        )

        val emitted = collectWith(events, output(Kind.CONTENT, source), event(Kind.CONTENT.type, windowW))

        assertTrue("a root that swapped to our own package must never be emitted", emitted.isEmpty())
    }
}
