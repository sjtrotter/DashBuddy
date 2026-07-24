package cloud.trotter.dashbuddy.feature.bubble

import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * #583: guards the Canvas-drawn score gauge that backs the rich offer notification. The bitmap is
 * pushed into a RemoteViews `ImageView` via `setImageViewBitmap`, so it must (a) come out at the
 * requested pixel size, and (b) never throw on out-of-range scores (the score is clamped 0..100).
 * (Pixel fidelity of the drawn arc is Android-platform behavior; Robolectric's Canvas doesn't
 * reliably rasterize, so this guards the contract that matters: a correctly-sized, crash-free bitmap.)
 */
@RunWith(RobolectricTestRunner::class)
class OfferGaugeBitmapTest {

    @Test
    fun `bitmap is produced at the requested size`() {
        val bmp = scoreGaugeBitmap(score = 72, ringArgb = Color.GREEN, sizePx = 120)
        assertEquals(120, bmp.width)
        assertEquals(120, bmp.height)
    }

    @Test
    fun `out-of-range scores do not throw and still render`() {
        // Below 0 and above 100 are clamped internally; the call must not crash.
        for (score in listOf(-50, 0, 50, 100, 250)) {
            val bmp = scoreGaugeBitmap(score = score, ringArgb = Color.RED, sizePx = 80)
            assertEquals(80, bmp.width)
        }
    }
}
