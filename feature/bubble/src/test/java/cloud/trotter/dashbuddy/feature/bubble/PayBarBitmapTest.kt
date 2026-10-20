package cloud.trotter.dashbuddy.feature.bubble

import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import cloud.trotter.dashbuddy.core.designsystem.theme.darkAppColors
import cloud.trotter.dashbuddy.feature.bubble.hud.PayBarModel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PayBarBitmapTest {
    private val labels = PayBarLabels("profit", "loss", "car", "distance unknown")
    private val colors = darkAppColors()

    @Test fun `split draws the profit and car tint at the requested size`() {
        val bitmap = payBarBitmap(PayBarModel.Split(829, 346, 0.7f), 360, 34, 1f, labels)!!
        assertEquals(360, bitmap.width)
        assertEquals(34, bitmap.height)
        // Middle of the outermost columns avoids rounded corners and the pills' 5 dp inset.
        assertColorNear(colors.good.copy(alpha = 0.28f).compositeOver(colors.surface3).toArgb(), bitmap.getPixel(0, 17))
        assertColorNear(colors.warn.copy(alpha = 0.28f).compositeOver(colors.surface3).toArgb(), bitmap.getPixel(359, 17))
    }

    @Test fun `unscored omits the bitmap`() {
        assertNull(payBarBitmap(PayBarModel.Unscored, 360, 34, 1f, labels))
    }

    @Test fun `unknown distance stays neutral without good or warn pixels`() {
        val bitmap = payBarBitmap(PayBarModel.DistanceUnknown, 360, 34, 1f, labels)!!
        val forbidden = setOf(colors.good.toArgb(), colors.warn.toArgb(),
            colors.good.copy(alpha = 0.28f).compositeOver(colors.surface3).toArgb(),
            colors.warn.copy(alpha = 0.28f).compositeOver(colors.surface3).toArgb())
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            assertFalse("unexpected scored colour at $x,$y", bitmap.getPixel(x, y) in forbidden)
        }
        assertEquals(colors.surface3.toArgb(), bitmap.getPixel(0, 17))
    }

    private fun assertColorNear(expected: Int, actual: Int) {
        for (shift in listOf(0, 8, 16, 24)) {
            assertTrue("expected ${expected.toUInt().toString(16)}, got ${actual.toUInt().toString(16)}",
                kotlin.math.abs(((expected ushr shift) and 255) - ((actual ushr shift) and 255)) <= 1)
        }
    }
}
