package cloud.trotter.log.gig.feature.bubble

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.ui.graphics.toArgb
import cloud.trotter.log.gig.core.designsystem.theme.darkAppColors
import cloud.trotter.log.gig.feature.bubble.hud.PayBarLayout
import cloud.trotter.log.gig.feature.bubble.hud.PayBarModel
import cloud.trotter.log.gig.feature.bubble.hud.payBarMoney

/** Localised at the caller; drawing needs no Context. */
data class PayBarLabels(val profit: String, val loss: String, val car: String, val distanceUnknown: String)

fun payBarBitmap(model: PayBarModel, widthPx: Int, heightPx: Int, density: Float, labels: PayBarLabels): Bitmap? {
    if (model == PayBarModel.Unscored) return null
    val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val colors = darkAppColors()
    val width = widthPx.toFloat()
    val height = heightPx.toFloat()
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    canvas.clipPath(Path().apply {
        addRoundRect(RectF(0f, 0f, width, height), 9f * density, 9f * density, Path.Direction.CW)
    })
    canvas.drawColor(colors.surface3.toArgb())
    when (model) {
        is PayBarModel.Split -> {
            val (left, right) = PayBarLayout.split(width, 6f * density, model.profitFraction)
            paint.color = colors.good.copy(alpha = 0.28f).toArgb()
            canvas.drawRect(0f, 0f, left, height, paint)
            paint.color = colors.warn.copy(alpha = 0.28f).toArgb()
            canvas.drawRect(left, 0f, left + right, height, paint)
        }
        is PayBarModel.Loss -> canvas.drawColor(colors.bad.copy(alpha = 0.22f).toArgb())
        else -> Unit
    }

    fun textPaint(size: Float, ink: Int, bold: Boolean = false) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ink
        textSize = size * density
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }
    fun text(text: String, x: Float, p: Paint) {
        canvas.drawText(text, x, height / 2f - (p.ascent() + p.descent()) / 2f, p)
    }
    fun pill(value: String, label: String, ink: Int, primary: Boolean) {
        val valuePaint = textPaint(if (primary) 16f else 13f, ink, bold = true)
        val labelPaint = textPaint(11f, ink)
        val padding = 7f * density
        val gap = 4f * density
        val pillWidth = padding * 2 + valuePaint.measureText(value) + gap + labelPaint.measureText(label)
        val x = if (primary) 5f * density else width - 5f * density - pillWidth
        val bounds = RectF(x, (height - 24f * density) / 2f, x + pillWidth, (height + 24f * density) / 2f)
        paint.style = Paint.Style.FILL
        paint.color = colors.bg.copy(alpha = 0.72f).toArgb()
        canvas.drawRoundRect(bounds, 12f * density, 12f * density, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f * density
        paint.color = ink
        val inset = paint.strokeWidth / 2f
        bounds.inset(inset, inset)
        canvas.drawRoundRect(bounds, 12f * density - inset, 12f * density - inset, paint)
        text(value, x + padding, valuePaint)
        text(label, x + padding + valuePaint.measureText(value) + gap, labelPaint)
    }
    when (model) {
        is PayBarModel.Split -> {
            pill(payBarMoney(model.profitCents), labels.profit, colors.good.toArgb(), true)
            pill(payBarMoney(model.carCents), labels.car, colors.warn.toArgb(), false)
        }
        is PayBarModel.Loss -> {
            pill(payBarMoney(model.lossCents), labels.loss, colors.bad.toArgb(), true)
            pill(payBarMoney(model.carCents), labels.car, colors.warn.toArgb(), false)
        }
        PayBarModel.DistanceUnknown -> text(labels.distanceUnknown, 10f * density, textPaint(14f, colors.neutral.toArgb()))
        PayBarModel.Unscored -> Unit
    }
    return bitmap
}
