package cloud.trotter.dashbuddy.feature.bubble.hud

object RibbonLayout {
    fun layout(
        model: RibbonModel, now: Long, widthPx: Float, knobInsetPx: Float,
        minSegPx: Float, minMarkGapPx: Float,
    ): RibbonGeometry {
        if (now <= model.startAt || widthPx <= 0) return RibbonGeometry(emptyList(), emptyList(), null)
        val endX = (widthPx - knobInsetPx).coerceAtLeast(0f)
        fun x(at: Long) = (endX * ((at - model.startAt).toDouble() / (now - model.startAt)))
            .toFloat().coerceIn(0f, endX)
        val rects = model.segments.filter { it.startAt < now }.map { segment ->
            val right = x(segment.endAt ?: now)
            val left = x(segment.startAt).coerceAtMost((endX - minSegPx).coerceAtLeast(0f))
            RibbonRect(segment.kind, left, maxOf(right, left + minSegPx).coerceAtMost(endX), segment.endAt == null)
        }
        // Pack forwards, then shift the group back from the right edge if necessary. When
        // the lane is full the available width determines the largest possible equal gap.
        val marks = model.marks.filter { it.at <= now }.sortedBy { it.at }
        val gap = if (marks.size > 1) minOf(minMarkGapPx, endX / (marks.size - 1)) else 0f
        val xs = mutableListOf<Float>()
        marks.forEach { mark -> xs += maxOf(x(mark.at), xs.lastOrNull()?.plus(gap) ?: 0f) }
        if (xs.isNotEmpty()) {
            xs[xs.lastIndex] = xs.last().coerceAtMost(endX)
            for (i in xs.lastIndex - 1 downTo 0) xs[i] = minOf(xs[i], xs[i + 1] - gap)
        }
        return RibbonGeometry(rects, marks.mapIndexed { i, mark -> RibbonMark(xs[i], mark.accepted) },
            endX.takeIf { model.tail != null })
    }
}
