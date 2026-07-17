package cloud.trotter.dashbuddy.core.pipeline.accessibility

import android.view.accessibility.AccessibilityEvent
import cloud.trotter.dashbuddy.core.pipeline.accessibility.event.type.window.content_changed.ContentChangedPipeline
import cloud.trotter.dashbuddy.core.pipeline.accessibility.event.type.window.state_changed.StateChangedPipeline
import cloud.trotter.dashbuddy.core.pipeline.accessibility.input.AccessibilitySource
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.robolectric.RobolectricTestRunner

/**
 * #435 item 3 — pins the check-before-map skip in the two active-window
 * pipelines: when [AccessibilitySource.getActiveWindowPackage] reports a
 * NON-target package (our bubble overlay, the launcher), the pipeline must
 * never call [AccessibilitySource.getCurrentRootSnapshot] — that call maps the
 * entire tree (one binder IPC per node), which is the exact work the pre-map
 * check exists to skip. Without these pins the skip branch is untested — a
 * refactor that reorders the check after the map would ship green.
 *
 * A positive control per pipeline (target package → snapshot IS taken and a
 * TreeSnapshot emitted) proves the harness actually flows.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PreMapPackageSkipTest {

    private val nonTargetPkg = "com.android.launcher3"
    private val targetPkg = "com.doordash.driverapp" // Platform.watchedPackages member

    private fun event(type: Int): AccessibilityEvent = mock {
        on { eventType } doReturn type
        on { contentChangeTypes } doReturn 0
        on { className } doReturn "android.widget.FrameLayout"
        on { packageName } doReturn nonTargetPkg
    }

    private fun sourceWith(
        events: MutableSharedFlow<AccessibilityEvent>,
        activePkg: String,
        snapshot: AccessibilitySource.RootSnapshot?,
    ): AccessibilitySource = mock {
        on { this.events } doReturn events
        on { getActiveWindowPackage() } doReturn activePkg
        on { getCurrentRootSnapshot() } doReturn snapshot
    }

    private fun snapshotOf(pkg: String) =
        AccessibilitySource.RootSnapshot(tree = UiNode(text = "root"), packageName = pkg)

    /** Emit [event] into a collecting [pipelineOutput], return everything emitted. */
    private fun collectWith(
        events: MutableSharedFlow<AccessibilityEvent>,
        pipelineOutput: Flow<TreeSnapshot>,
        event: AccessibilityEvent,
    ): List<TreeSnapshot> {
        val emitted = mutableListOf<TreeSnapshot>()
        runTest {
            val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                pipelineOutput.collect { emitted += it }
            }
            advanceUntilIdle()
            assertTrue("test harness: event must enter the shared flow", events.tryEmit(event))
            advanceUntilIdle()
            job.cancel()
        }
        return emitted
    }

    // ── ContentChangedPipeline ──────────────────────────────────────────

    @Test
    fun `content-changed - non-target active window is skipped BEFORE mapping`() {
        val events = MutableSharedFlow<AccessibilityEvent>(extraBufferCapacity = 4)
        val source = sourceWith(events, activePkg = nonTargetPkg, snapshot = snapshotOf(nonTargetPkg))

        val emitted = collectWith(
            events, ContentChangedPipeline(source).output(),
            event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED),
        )

        verify(source, never()).getCurrentRootSnapshot()
        assertTrue("non-target window must emit nothing", emitted.isEmpty())
    }

    @Test
    fun `content-changed - target active window still maps and emits (control)`() {
        val events = MutableSharedFlow<AccessibilityEvent>(extraBufferCapacity = 4)
        val source = sourceWith(events, activePkg = targetPkg, snapshot = snapshotOf(targetPkg))

        val emitted = collectWith(
            events, ContentChangedPipeline(source).output(),
            event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED),
        )

        verify(source, times(1)).getCurrentRootSnapshot()
        assertEquals("target window must flow through", 1, emitted.size)
        assertEquals(targetPkg, emitted.single().packageName)
    }

    // ── StateChangedPipeline ────────────────────────────────────────────

    @Test
    fun `state-changed - non-target active window is skipped BEFORE mapping`() {
        val events = MutableSharedFlow<AccessibilityEvent>(extraBufferCapacity = 4)
        val source = sourceWith(events, activePkg = nonTargetPkg, snapshot = snapshotOf(nonTargetPkg))

        val emitted = collectWith(
            events, StateChangedPipeline(source).output(),
            event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED),
        )

        verify(source, never()).getCurrentRootSnapshot()
        assertTrue("non-target window must emit nothing", emitted.isEmpty())
    }

    @Test
    fun `state-changed - target active window still maps and emits (control)`() {
        val events = MutableSharedFlow<AccessibilityEvent>(extraBufferCapacity = 4)
        val source = sourceWith(events, activePkg = targetPkg, snapshot = snapshotOf(targetPkg))

        val emitted = collectWith(
            events, StateChangedPipeline(source).output(),
            event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED),
        )

        verify(source, times(1)).getCurrentRootSnapshot()
        assertEquals("target window must flow through", 1, emitted.size)
        assertEquals(targetPkg, emitted.single().packageName)
    }
}
