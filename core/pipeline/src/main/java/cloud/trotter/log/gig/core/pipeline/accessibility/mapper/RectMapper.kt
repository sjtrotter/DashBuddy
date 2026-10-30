package cloud.trotter.log.gig.core.pipeline.accessibility.mapper

import android.graphics.Rect
import cloud.trotter.log.gig.domain.model.accessibility.BoundingBox

fun Rect.toBoundingBox(): BoundingBox {
    return BoundingBox(left, top, right, bottom)
}