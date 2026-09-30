package cloud.trotter.dashbuddy.core.pipeline.accessibility

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import cloud.trotter.dashbuddy.core.pipeline.accessibility.event.type.window.windows_changed.WindowsChangedPipeline
import cloud.trotter.dashbuddy.core.pipeline.accessibility.input.AccEvent
import cloud.trotter.dashbuddy.core.pipeline.accessibility.input.AccessibilitySource
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #1148 review G6 — the topology path emits TRUE OVERLAYS only: enabled-package windows ABOVE the
 * active window (an Uber offer, a11y `TYPE_SYSTEM`, over DoorDash). A window beneath the active one
 * — the activity under a DoorDash sheet — is never emitted (that re-opened the F1 interleaving).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WindowsChangedOverlayTest {

    private val ddPkg = "com.doordash.driverapp"
    private val uberPkg = "com.ubercab.driver"

    private fun node(pkg: String, label: String): AccessibilityNodeInfo = mock {
        on { packageName } doReturn pkg
        on { text } doReturn label
    }

    private fun window(
        windowId: Int,
        windowLayer: Int,
        root: AccessibilityNodeInfo?,
        windowType: Int = AccessibilityWindowInfo.TYPE_APPLICATION,
        active: Boolean = false,
    ): AccessibilityWindowInfo = mock {
        on { id } doReturn windowId
        on { layer } doReturn windowLayer
        on { this.root } doReturn root
        on { isActive } doReturn active
        on { type } doReturn windowType
    }

    private fun emitted(windows: List<AccessibilityWindowInfo>, enabled: Set<String>): List<TreeSnapshot> {
        val service = mock<AccessibilityService> {
            on { this.windows } doReturn windows
            on { packageName } doReturn "cloud.trotter.dashbuddy"
        }
        val source = AccessibilitySource().apply { registerService(service) }
        val pipeline = WindowsChangedPipeline(source, FakePlatformPreferences(enabled))
        val out = mutableListOf<TreeSnapshot>()
        runTest {
            val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                pipeline.output().collect { out += it }
            }
            advanceUntilIdle()
            @Suppress("DEPRECATION")
            source.emit(AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOWS_CHANGED))
            advanceTimeBy(1_000)
            runCurrent()
            job.cancel()
        }
        return out
    }

    @Test
    fun `a DoorDash sheet active over its activity - nothing emitted`() {
        val out = emitted(
            listOf(window(7, 5, node(ddPkg, "sheet"), active = true), window(3, 2, node(ddPkg, "activity"))),
            enabled = setOf(ddPkg, uberPkg),
        )
        assertTrue("the activity beneath the active sheet is never emitted", out.isEmpty())
    }

    @Test
    fun `an enabled Uber overlay above the active DoorDash window is emitted`() {
        val out = emitted(
            listOf(
                window(3, 2, node(ddPkg, "dd"), active = true),
                window(9, 9, node(uberPkg, "uber-offer"), windowType = AccessibilityWindowInfo.TYPE_SYSTEM),
            ),
            enabled = setOf(ddPkg, uberPkg),
        )
        assertEquals(listOf("uber-offer"), out.map { it.tree.text })
        assertEquals(TreeSnapshot.Trigger.Reason.WINDOWS, out.single().trigger?.reason)
    }

    @Test
    fun `a DISABLED Uber overlay is never emitted`() {
        val out = emitted(
            listOf(
                window(3, 2, node(ddPkg, "dd"), active = true),
                window(9, 9, node(uberPkg, "uber-offer"), windowType = AccessibilityWindowInfo.TYPE_SYSTEM),
            ),
            enabled = setOf(ddPkg),
        )
        assertTrue(out.isEmpty())
    }

    @Test
    fun `no active window - nothing emitted`() {
        val out = emitted(
            listOf(window(9, 9, node(uberPkg, "uber-offer"), windowType = AccessibilityWindowInfo.TYPE_SYSTEM)),
            enabled = setOf(ddPkg, uberPkg),
        )
        assertTrue(out.isEmpty())
    }
}
