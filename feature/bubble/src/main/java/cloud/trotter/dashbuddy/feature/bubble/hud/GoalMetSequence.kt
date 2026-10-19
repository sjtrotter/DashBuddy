package cloud.trotter.dashbuddy.feature.bubble.hud

import androidx.compose.animation.core.FastOutSlowInEasing

enum class GoalMetPhase { COLLAPSE, CLOSE, CHECK, SETTLE, DONE }
enum class GoalIcon { OUTLINED_FLAG, CHECK, FILLED_FLAG }
data class GoalMetFrame(
    val phase: GoalMetPhase,
    val textAlpha: Float,
    val collapse: Float,
    val arcSweep: Float,
    val arcAccent: Float,
    val icon: GoalIcon,
)

/** Pure frame data. Motion time comes exclusively from the Compose frame clock. */
object GoalMetSequence {
    const val COLLAPSE_MS = 300
    const val CLOSE_MS = 400
    const val CHECK_MS = 400
    const val SETTLE_MS = 100
    const val DURATION_MS = COLLAPSE_MS + CLOSE_MS + CHECK_MS + SETTLE_MS

    fun frameAt(elapsedMs: Long, startProgress: Float): GoalMetFrame {
        val elapsed = elapsedMs.coerceAtLeast(0)
        val start = startProgress.coerceIn(0f, 1f)
        fun eased(startMs: Int, duration: Int) = FastOutSlowInEasing.transform(
            ((elapsed - startMs).toFloat() / duration).coerceIn(0f, 1f))
        return when {
            elapsed < 300 -> eased(0, COLLAPSE_MS).let {
                GoalMetFrame(GoalMetPhase.COLLAPSE, 1f - it, it, start, 0f, GoalIcon.OUTLINED_FLAG)
            }
            elapsed < 700 -> eased(300, CLOSE_MS).let {
                GoalMetFrame(GoalMetPhase.CLOSE, 0f, 1f, start + (1f - start) * it, it, GoalIcon.OUTLINED_FLAG)
            }
            elapsed < 1100 -> GoalMetFrame(GoalMetPhase.CHECK, 0f, 1f, 1f, 1f, GoalIcon.CHECK)
            elapsed < DURATION_MS -> GoalMetFrame(GoalMetPhase.SETTLE, 0f, 1f, 1f, 1f, GoalIcon.FILLED_FLAG)
            else -> GoalMetFrame(GoalMetPhase.DONE, 0f, 1f, 1f, 1f, GoalIcon.FILLED_FLAG)
        }
    }
}
