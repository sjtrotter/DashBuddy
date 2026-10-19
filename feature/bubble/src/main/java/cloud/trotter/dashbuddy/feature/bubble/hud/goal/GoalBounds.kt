package cloud.trotter.dashbuddy.feature.bubble.hud.goal

/** Whole-dollar UI policy. Only the separate clear action removes a goal. */
object GoalBounds {
    const val MIN = 5
    const val MAX = 999
    const val STEP = 5

    fun minus(dollars: Int?): Int? = dollars?.let { (it - STEP).coerceAtLeast(MIN) }
    fun plus(dollars: Int?): Int = dollars?.let { (it + STEP).coerceAtMost(MAX) } ?: MIN
    fun canDecrease(dollars: Int?): Boolean = dollars != null && dollars > MIN
    fun canIncrease(dollars: Int?): Boolean = dollars == null || dollars < MAX
}
