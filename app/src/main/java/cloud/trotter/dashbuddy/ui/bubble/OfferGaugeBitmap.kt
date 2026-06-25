package cloud.trotter.dashbuddy.ui.bubble

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.ui.graphics.toArgb
import cloud.trotter.dashbuddy.core.designsystem.theme.darkAppColors

/**
 * #583: the offer **score gauge ring**, drawn with a plain [Canvas] into a [Bitmap] for the rich
 * notification. RemoteViews can't draw a ring (no Canvas/custom View), and the in-app Compose
 * [cloud.trotter.dashbuddy.core.designsystem.component.AppGaugeRing] can't be rendered off-screen
 * from the notification-post path (it has no window/lifecycle owner there). So this is a faithful
 * Canvas re-draw of the same ring: a track circle + a `-90°` score sweep arc in the score-band color
 * + the centered score. Colors come from the fixed dark palette ([darkAppColors]); the band color
 * is the SSOT [cloud.trotter.dashbuddy.ui.formatters.offerScoreArgb]. Drawn on a filled card-colored
 * disc so it reads the same regardless of the (system-controlled) notification background.
 */
fun scoreGaugeBitmap(score: Int, ringArgb: Int, sizePx: Int): Bitmap {
    val colors = darkAppColors()
    val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val cx = sizePx / 2f
    val stroke = sizePx * 0.12f
    val pad = stroke / 2f + sizePx * 0.04f
    val rect = RectF(pad, pad, sizePx - pad, sizePx - pad)

    // Self-contained disc so the ring/number read on any notification theme.
    Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colors.surface2.toArgb() }
        .also { canvas.drawCircle(cx, cx, cx, it) }

    val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        strokeCap = Paint.Cap.ROUND
    }
    canvas.drawArc(rect, -90f, 360f, false, ring.apply { color = colors.surface3.toArgb() })
    canvas.drawArc(
        rect, -90f, 360f * (score.coerceIn(0, 100) / 100f), false,
        ring.apply { color = ringArgb },
    )

    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colors.text.toArgb()
        textAlign = Paint.Align.CENTER
        textSize = sizePx * 0.34f
        typeface = Typeface.DEFAULT_BOLD
    }
    val baseline = cx - (text.descent() + text.ascent()) / 2f
    canvas.drawText(score.toString(), cx, baseline, text)
    return bmp
}
