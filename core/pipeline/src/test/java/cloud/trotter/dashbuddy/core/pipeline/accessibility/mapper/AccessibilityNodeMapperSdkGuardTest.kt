package cloud.trotter.dashbuddy.core.pipeline.accessibility.mapper

import android.view.accessibility.AccessibilityNodeInfo
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #1161 — the mapper must convert a node on API 30 (minSdk) without touching the API-36 `getChecked()`;
 * the tri-state is read only on 36+. One class named after the file (the repo's `--tests "*FooTest"` filter
 * shape) with a per-method Robolectric `@Config(sdk = …)`.
 *
 * The API-30 methods must NOT stub or call `m.checked` (getChecked): on the API-30 Robolectric sandbox that
 * method does not exist and the test itself would throw — which is exactly what the unguarded mapper did.
 */
@RunWith(RobolectricTestRunner::class)
@Suppress("DEPRECATION")
class AccessibilityNodeMapperSdkGuardTest {

    @Test
    @Config(sdk = [30])
    fun `checked node maps to one on API 30`() {
        val m = mock<AccessibilityNodeInfo>()
        whenever(m.childCount).thenReturn(0)
        whenever(m.isChecked).thenReturn(true)
        assertEquals(1, m.toUiNode()!!.isChecked)
    }

    @Test
    @Config(sdk = [30])
    fun `unchecked node maps to zero on API 30`() {
        val m = mock<AccessibilityNodeInfo>()
        whenever(m.childCount).thenReturn(0)
        whenever(m.isChecked).thenReturn(false)
        assertEquals(0, m.toUiNode()!!.isChecked)
    }

    @Test
    @Config(sdk = [36])
    fun `partially checked node preserves the tri-state on API 36`() {
        val m = mock<AccessibilityNodeInfo>()
        whenever(m.childCount).thenReturn(0)
        whenever(m.checked).thenReturn(2)
        whenever(m.isChecked).thenReturn(true)
        assertEquals(2, m.toUiNode()!!.isChecked)
    }
}
