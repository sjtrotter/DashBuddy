package cloud.trotter.dashbuddy.core.pipeline.accessibility

import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
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
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.robolectric.RobolectricTestRunner

/**
 * #435 item 3 + #1148 — pins the check-before-map skip in the two event-driven window pipelines.
 * The resolver IGNORES the event's window: it reads the active root ONCE, and when that root's
 * package is NOT an enabled platform (our bubble overlay, the launcher) and no readable enabled
 * window is in front, it must never map anything — mapping is one binder IPC per node, the exact
 * work the pre-map check exists to skip. When the active root IS enabled, that same already-fetched
 * root is mapped ([AccessibilitySource.getCurrentRootSnapshot] with the node) — the positive
 * control per pipeline proves the harness actually flows. The foreground-window cases live in
 * [WindowSpecificSnapshotTest].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PreMapPackageSkipTest {

    private val nonTargetPkg = "com.android.launcher3"
    private val targetPkg = "com.doordash.driverapp" // Platform.watchedPackages member

    private fun event(type: Int) = AccEvent(
        type = type,
        windowId = -1,
        packageName = nonTargetPkg,
        className = "android.widget.FrameLayout",
        contentChangeTypes = 0,
        eventTimeMs = 0L,
    )

    private fun sourceWith(
        events: MutableSharedFlow<AccEvent>,
        activePkg: String,
        snapshot: AccessibilitySource.RootSnapshot?,
    ): AccessibilitySource {
        val activeRoot = mock<AccessibilityNodeInfo> { on { packageName } doReturn activePkg }
        return mock {
            on { this.events } doReturn events
            on { getLiveNativeRoot() } doReturn activeRoot
            on { getCurrentRootSnapshot(any<AccessibilityNodeInfo>()) } doReturn snapshot
        }
    }

    private val prefs = FakePlatformPreferences(setOf(targetPkg))

    private fun snapshotOf(pkg: String) =
        AccessibilitySource.RootSnapshot(tree = UiNode(text = "root"), packageName = pkg)

    /** Emit [event] into a collecting [pipelineOutput], return everything emitted. */
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

    // ── ContentChangedPipeline ──────────────────────────────────────────

    @Test
    fun `content-changed - non-target active window is skipped BEFORE mapping`() {
        val events = MutableSharedFlow<AccEvent>(extraBufferCapacity = 4)
        val source = sourceWith(events, activePkg = nonTargetPkg, snapshot = snapshotOf(nonTargetPkg))

        val emitted = collectWith(
            events, ContentChangedPipeline(source, prefs).output(),
            event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED),
        )

        verify(source, never()).getCurrentRootSnapshot(any<AccessibilityNodeInfo>())
        verify(source, never()).getWindowSnapshot(any(), any(), any())
        assertTrue("non-target window must emit nothing", emitted.isEmpty())
    }

    @Test
    fun `content-changed - target active window still maps and emits (control)`() {
        val events = MutableSharedFlow<AccEvent>(extraBufferCapacity = 4)
        val source = sourceWith(events, activePkg = targetPkg, snapshot = snapshotOf(targetPkg))

        val emitted = collectWith(
            events, ContentChangedPipeline(source, prefs).output(),
            event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED),
        )

        verify(source, times(1)).getCurrentRootSnapshot(any<AccessibilityNodeInfo>())
        assertEquals("target window must flow through", 1, emitted.size)
        assertEquals(targetPkg, emitted.single().packageName)
    }

    // ── StateChangedPipeline ────────────────────────────────────────────

    @Test
    fun `state-changed - non-target active window is skipped BEFORE mapping`() {
        val events = MutableSharedFlow<AccEvent>(extraBufferCapacity = 4)
        val source = sourceWith(events, activePkg = nonTargetPkg, snapshot = snapshotOf(nonTargetPkg))

        val emitted = collectWith(
            events, StateChangedPipeline(source, prefs).output(),
            event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED),
        )

        verify(source, never()).getCurrentRootSnapshot(any<AccessibilityNodeInfo>())
        verify(source, never()).getWindowSnapshot(any(), any(), any())
        assertTrue("non-target window must emit nothing", emitted.isEmpty())
    }

    @Test
    fun `state-changed - target active window still maps and emits (control)`() {
        val events = MutableSharedFlow<AccEvent>(extraBufferCapacity = 4)
        val source = sourceWith(events, activePkg = targetPkg, snapshot = snapshotOf(targetPkg))

        val emitted = collectWith(
            events, StateChangedPipeline(source, prefs).output(),
            event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED),
        )

        verify(source, times(1)).getCurrentRootSnapshot(any<AccessibilityNodeInfo>())
        assertEquals("target window must flow through", 1, emitted.size)
        assertEquals(targetPkg, emitted.single().packageName)
    }
}
