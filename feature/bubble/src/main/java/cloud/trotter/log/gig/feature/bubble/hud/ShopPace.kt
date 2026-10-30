package cloud.trotter.log.gig.feature.bubble.hud

object ShopPace {
    fun itemsPerMinute(shopped: Int, arrivedAt: Long, now: Long): Double? {
        val elapsed = now - arrivedAt
        return if (elapsed < 60_000 || shopped < 0) null else shopped / (elapsed / 60_000.0)
    }

    fun segments(total: Int): Int? = total.takeIf { it in 1..40 }
}
