package cloud.trotter.dashbuddy.domain.state

import cloud.trotter.dashbuddy.domain.model.ratings.RatingsSnapshot

/**
 * Region 2+ — per-platform durable state.
 *
 * Mode lives HERE, not globally. Each platform has its own session lifecycle,
 * active job/task, and healing confidence tracker.
 *
 * A platform region only steps when an observation's [Platform] matches.
 */
data class PlatformRegion(
    val platform: Platform,
    val mode: Mode = Mode.Offline,
    val session: Session? = null,
    val activeJob: Job? = null,
    val activeTask: Task? = null,
    val recentTasks: List<Task> = emptyList(),
    val confidence: ModeConfidence = ModeConfidence.EMPTY,
    val zoneName: String? = null,
    val sessionType: SessionType? = null,
    val ratings: RatingsSnapshot? = null,
    val surgeMultiplier: Double? = null,
    val lastPostTaskPayHash: Int? = null,
    val lastObservedAt: Long = 0,
)

/**
 * Tracks confidence for implausible mode transitions. When the pipeline
 * observes a flow that implies a mode change that doesn't make sense given
 * the current state (e.g., observe pickup while mode is Offline), the
 * stepper accrues confidence instead of immediately transitioning.
 *
 * Default threshold: 2 supporting observations within 10s, OR 1 high-weight
 * signal (like an explicit mode-defining screen).
 */
data class ModeConfidence(
    val pendingMode: Mode? = null,
    val pendingFlow: Flow? = null,
    val supportingObservations: Int = 0,
    val firstSeenAt: Long? = null,
) {
    companion object {
        val EMPTY = ModeConfidence()

        const val DEFAULT_OBSERVATION_THRESHOLD = 2
        const val DEFAULT_TIME_WINDOW_MS = 10_000L
    }
}
