package cloud.trotter.dashbuddy.util

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.util.TypedValue
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toDrawable

object ViewUtils {

    /**
     * Creates a Drawable containing text.
     * Use this to put "$" or "mi" inside an EditText.
     */
    fun createTextDrawable(
        context: Context,
        text: String,
        colorAttr: Int = com.google.android.material.R.attr.colorOnSurfaceVariant
    ): Drawable {
        val resources = context.resources
        val scale = resources.displayMetrics.density
        val size = (24 * scale).toInt() // Standard icon size base

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Resolve color
        val typedValue = TypedValue()
        context.theme.resolveAttribute(colorAttr, typedValue, true)
        paint.color = typedValue.data

        paint.textSize = 16 * scale
        paint.textAlign = Paint.Align.LEFT
        paint.typeface = android.graphics.Typeface.DEFAULT_BOLD

        // Measure text
        val baseline = -paint.ascent()
        val width = (paint.measureText(text) + (8 * scale)).toInt() // Add padding
        val height = (paint.descent() - paint.ascent()).toInt() + (10 * scale).toInt()

        val bitmap = createBitmap(width, height)
        val canvas = Canvas(bitmap)

        // Draw centered vertically
        canvas.drawText(text, 0f, baseline + 5, paint)

        return bitmap.toDrawable(resources)
    }
}