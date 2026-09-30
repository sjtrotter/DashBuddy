package cloud.trotter.dashbuddy.core.pipeline.accessibility

import android.content.res.Resources
import android.graphics.Rect
import android.util.DisplayMetrics
import android.view.accessibility.AccessibilityWindowInfo
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * #1152 fixtures — the Pixel 7 geometry from the #248 on-device record: a 1080×2400 display, the
 * Uber offer overlay at `Rect(0,136 – 1080,2347)`, the Uber puck at `Rect(938,831 – 1080,973)`.
 */
internal object OverlayGeometry {
    const val DISPLAY_W = 1080
    const val DISPLAY_H = 2400
    val UBER_OFFER = Rect(0, 136, 1080, 2347)
    val UBER_PUCK = Rect(938, 831, 1080, 973)
    val STATUS_BAR = Rect(0, 0, 1080, 136)
    val FULL_SCREEN = Rect(0, 0, 1080, 2400)
}

/** Stubs [w]'s `getBoundsInScreen` (a void out-param call) to write [bounds]. Returns [w]. */
internal fun withBounds(w: AccessibilityWindowInfo, bounds: Rect): AccessibilityWindowInfo {
    doAnswer { (it.arguments[0] as Rect).set(bounds); null }.whenever(w).getBoundsInScreen(any())
    return w
}

/** A `Resources` whose display metrics are [w]×[h] — for a mocked service's `resources`. */
internal fun displayResources(
    w: Int = OverlayGeometry.DISPLAY_W,
    h: Int = OverlayGeometry.DISPLAY_H,
): Resources {
    val metrics = DisplayMetrics().apply {
        widthPixels = w
        heightPixels = h
    }
    return mock { on { displayMetrics } doReturn metrics }
}
