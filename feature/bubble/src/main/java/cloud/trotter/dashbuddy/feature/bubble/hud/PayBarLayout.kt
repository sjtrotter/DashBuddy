package cloud.trotter.dashbuddy.feature.bubble.hud

object PayBarLayout {
    fun split(widthPx: Float, floorPx: Float, profitFraction: Float): Pair<Float, Float> {
        val width = widthPx.coerceAtLeast(0f)
        val floor = floorPx.coerceIn(0f, width / 2f)
        val profit = (width * profitFraction.coerceIn(0f, 1f)).coerceIn(floor, width - floor)
        return profit to width - profit
    }
}
