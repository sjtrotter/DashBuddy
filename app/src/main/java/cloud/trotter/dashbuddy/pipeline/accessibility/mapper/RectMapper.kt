package cloud.trotter.dashbuddy.pipeline.accessibility.mapper

import android.graphics.Rect
import cloud.trotter.dashbuddy.domain.model.accessibility.BoundingBox

fun Rect.toBoundingBox(): BoundingBox {
    return BoundingBox(left, top, right, bottom)
}