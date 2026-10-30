package cloud.trotter.log.gig.feature.bubble.hud

object RibbonLayout {
    fun layout(
        model: RibbonModel, now: Long, widthPx: Float, knobInsetPx: Float,
        minSegPx: Float, minMarkGapPx: Float,
    ): RibbonGeometry {
        if (now <= model.startAt || widthPx <= 0) return RibbonGeometry(emptyList(), emptyList(), null)
        val endX = (widthPx - knobInsetPx).coerceAtLeast(0f)
        fun x(at: Long) = (endX * ((at - model.startAt).toDouble() / (now - model.startAt)))
            .toFloat().coerceIn(0f, endX)
        // Each segment starts no earlier than the previous one ends, so a segment widened to the
        // floor is never overpainted by its neighbour; the shift accumulates along the lane.
        var cursor = 0f
        val rects = model.segments.filter { it.startAt < now }.mapNotNull { segment ->
            val left = maxOf(x(segment.startAt), cursor)
            if (left >= endX) return@mapNotNull null // no lane left; the knob already marks now
            val right = maxOf(x(segment.endAt ?: now), left + minSegPx).coerceAtMost(endX)
            cursor = right
            RibbonRect(segment.kind, left, right, segment.endAt == null)
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
