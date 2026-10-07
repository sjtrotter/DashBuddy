package cloud.trotter.dashbuddy.state.effects

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import cloud.trotter.dashbuddy.core.pipeline.accessibility.input.AccessibilitySource
import cloud.trotter.dashbuddy.core.state.AppEffect
import cloud.trotter.dashbuddy.domain.settings.PlatformPreferences
import cloud.trotter.dashbuddy.domain.state.Platform
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoMoreInteractions
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("DEPRECATION") // Verify recycling on API 30, where it is required.
class ScreenShotHandlerBoundaryTest {
    private val service = mock<AccessibilityService>()
    private val source = mock<AccessibilitySource>()
    private val context = mock<Context>()
    private val preferences = mock<PlatformPreferences>()
    private val enabled = MutableStateFlow(setOf(Platform.DoorDash, Platform.Uber))
    private val effect = AppEffect.CaptureScreenshot(filenamePrefix = "offer", category = null)

    @Test
    fun `enabled DoorDash captures only after settle and recycles root`() {
        checkCapture(root(), captures = true)
    }

    @Test
    fun `own bubble allows capture even with no enabled platforms`() {
        enabled.value = emptySet()
        checkCapture(root("cloud.trotter.dashbuddy"), captures = true)
    }

    @Test
    fun `toggle revoked during settle skips without reading root`() {
        checkCapture(root(), beforeFire = { allowed = false })
    }

    @Test
    fun `platform disabled during settle skips`() {
        checkCapture(root(), beforeFire = { enabled.value = emptySet() })
    }

    @Test
    fun `foreign front app skips`() {
        checkCapture(root("com.android.chrome"))
    }

    @Test
    fun `browser appearing during settle skips`() {
        val delivery = root()
        val browser = root("com.android.chrome")
        checkCapture(delivery, readRoot = browser, beforeFire = {
            whenever(service.rootInActiveWindow).thenReturn(browser)
        })
        verifyNoMoreInteractions(delivery)
    }

    @Test
    fun `null root skips`() = checkCapture(null)

    @Test
    fun `null package skips and recycles root`() = checkCapture(root(null))

    @Test
    fun `root read exception skips`() {
        checkCapture(null, beforeFire = {
            whenever(service.rootInActiveWindow).thenThrow(IllegalStateException("unreadable"))
        })
    }

    @Test
    fun `package read exception skips and recycles root`() {
        val root = root()
        whenever(root.packageName).thenThrow(IllegalStateException("unreadable"))
        checkCapture(root)
    }

    private var allowed = true

    private fun checkCapture(
        root: AccessibilityNodeInfo?,
        captures: Boolean = false,
        readRoot: AccessibilityNodeInfo? = root,
        beforeFire: () -> Unit = {},
    ) = runTest {
        whenever(service.rootInActiveWindow).thenReturn(root)
        whenever(source.getService()).thenReturn(service)
        whenever(context.packageName).thenReturn("cloud.trotter.dashbuddy")
        whenever(preferences.enabledPlatforms).thenReturn(enabled)
        val handler = ScreenShotHandler(context, source, preferences, StandardTestDispatcher(testScheduler))
        handler.capture(this, effect) { allowed }
        runCurrent()
        advanceTimeBy(ScreenShotHandler.SETTLE_MS - 1)
        runCurrent()
        verify(service, never()).takeScreenshot(any(), any(), any())
        verify(service, never()).rootInActiveWindow
        beforeFire()
        advanceTimeBy(2)
        runCurrent()
        if (captures) {
            verify(service).takeScreenshot(eq(Display.DEFAULT_DISPLAY), any(), any())
        } else {
            verify(service, never()).takeScreenshot(any(), any(), any())
        }
        verify(service, never()).windows
        if (allowed) {
            verify(service).rootInActiveWindow
            if (readRoot != null) {
                verify(readRoot).packageName
                verify(readRoot).recycle()
                // Package identity is the only node data read: no content or child traversal.
                verifyNoMoreInteractions(readRoot)
            }
        } else {
            verify(service, never()).rootInActiveWindow
            if (readRoot != null) verifyNoMoreInteractions(readRoot)
        }
    }

    private fun root(packageName: String? = Platform.DoorDash.packageName): AccessibilityNodeInfo {
        val root = mock<AccessibilityNodeInfo>()
        whenever(root.packageName).thenReturn(packageName)
        return root
    }
}
