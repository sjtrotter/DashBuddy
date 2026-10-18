package cloud.trotter.dashbuddy.feature.bubble.hud

data class HoursMinutes(val hours: Int, val minutes: Int) {
    companion object {
        fun of(ms: Long): HoursMinutes {
            val total = ms.coerceAtLeast(0) / 60_000
            return HoursMinutes((total / 60).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), (total % 60).toInt())
        }
    }
}
