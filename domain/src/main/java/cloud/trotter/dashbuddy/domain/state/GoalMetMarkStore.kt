package cloud.trotter.dashbuddy.domain.state

import kotlinx.coroutines.flow.Flow

/** App-lifecycle mark, overwritten when a daily goal celebration starts. */
interface GoalMetMarkStore {
    val goalMetMark: Flow<String?>
    suspend fun setGoalMetMark(key: String)
}
