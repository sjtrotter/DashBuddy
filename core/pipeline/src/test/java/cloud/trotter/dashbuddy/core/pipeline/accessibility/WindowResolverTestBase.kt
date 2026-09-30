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
 * Shared harness for the event-driven window-resolution tests (#1148 D4, #1152, PR #1155 review HH7 —
 * split out of the former 1 000-line `WindowSpecificSnapshotTest`). Real [AccessibilitySource] over
 * a mocked service (spied, so the path is observable); subclasses run under Robolectric sdk 36
 * because the node mapper reads the API-36 `getChecked()`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
abstract class WindowResolverTestBase {

    protected val ownPkg = "cloud.trotter.dashbuddy"

    protected val launcherPkg = "com.android.launcher3"

    protected val systemUiPkg = "com.android.systemui"

    protected val ddPkg = "com.doordash.driverapp"

    protected val uberPkg = "com.ubercab.driver"

    protected val app = AccessibilityWindowInfo.TYPE_APPLICATION

    protected val system = AccessibilityWindowInfo.TYPE_SYSTEM

    protected fun node(pkg: String, label: String, windowId: Int = 0): AccessibilityNodeInfo = mock {
        on { packageName } doReturn pkg
        on { text } doReturn label
        on { this.windowId } doReturn windowId
    }

    protected fun window(
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
    protected fun uberOverlay(windowId: Int, windowLayer: Int, root: AccessibilityNodeInfo?, active: Boolean = false) =
        window(windowId, windowLayer, root, windowType = system, active = active, bounds = OverlayGeometry.UBER_OFFER)

    protected class Harness(
        val service: AccessibilityService,
        val source: AccessibilitySource,
        val events: MutableSharedFlow<AccEvent>,
        val prefs: FakePlatformPreferences,
        val stats: PipelineStats = PipelineStats(),
    )

    protected fun harness(
        activeRoot: AccessibilityNodeInfo?,
        windows: List<AccessibilityWindowInfo>,
        enabled: Set<String> = setOf(ddPkg, uberPkg),
        res: android.content.res.Resources? = displayResources(),
    ): Harness {
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

    protected fun event(type: Int, windowId: Int, pkg: String = ddPkg) = AccEvent(
        type = type,
        windowId = windowId,
        packageName = pkg,
        className = "android.widget.FrameLayout",
        contentChangeTypes = 1,
        eventTimeMs = 0L,
    )

    protected enum class Kind(val type: Int) {
        CONTENT(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED),
        STATE(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED),
    }

    protected fun output(
        kind: Kind,
        source: AccessibilitySource,
        prefs: FakePlatformPreferences,
        stats: PipelineStats = PipelineStats(),
    ): Flow<TreeSnapshot> =
        when (kind) {
            Kind.CONTENT -> ContentChangedPipeline(source, prefs, stats).output()
            Kind.STATE -> StateChangedPipeline(source, prefs, stats).output()
        }

    protected fun collect(h: Harness, kind: Kind, windowId: Int = 3, pkg: String = ddPkg): List<TreeSnapshot> =
        collectWith(h.events, output(kind, h.source, h.prefs, h.stats), event(kind.type, windowId, pkg))

    protected fun Harness.skipped(reason: ForegroundSkipReason) =
        assertEquals("counted as $reason (H3)", 1L, stats.foregroundSkipCount(reason))

    protected fun collectWith(
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

    protected fun bothKinds(block: (Kind) -> Unit) = Kind.entries.forEach(block)

    protected fun Harness.nothingMapped() {
        verify(source, never()).getCurrentRootSnapshot(any<AccessibilityNodeInfo>())
        verify(source, never()).getWindowSnapshot(any(), any(), any())
    }

    /** Emits [seq] (virtual-time offset ms → event) into a CONTENT pipeline and collects every frame. */
    protected fun collectSequence(h: Harness, seq: List<Pair<Long, AccEvent>>): List<TreeSnapshot> {
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

    protected fun content(windowId: Int, pkg: String) = event(Kind.CONTENT.type, windowId, pkg)

    protected fun topologyFrames(h: Harness): List<TreeSnapshot> = collectWith(
        h.events,
        cloud.trotter.dashbuddy.core.pipeline.accessibility.event.type.window.windows_changed
            .WindowsChangedPipeline(h.source, h.prefs, h.stats).output(),
        event(AccessibilityEvent.TYPE_WINDOWS_CHANGED, windowId = -1),
    )
}
