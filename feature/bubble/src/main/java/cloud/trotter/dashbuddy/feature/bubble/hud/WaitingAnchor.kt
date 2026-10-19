package cloud.trotter.dashbuddy.feature.bubble.hud

import cloud.trotter.dashbuddy.domain.model.cards.CardStack
import cloud.trotter.dashbuddy.domain.model.cards.FlowCardSnapshot
import cloud.trotter.dashbuddy.domain.state.Task
import cloud.trotter.dashbuddy.domain.state.TaskPhase

object WaitingAnchor {
    /** Resume is a future seam; S2b deliberately lets the wait run through a pause. */
    fun resolve(sessionStartedAt: Long?, tasks: List<Task>, stack: CardStack, resumedAt: Long? = null): Long? {
        val start = sessionStartedAt ?: return null
        val ownJobs = tasks.mapTo(HashSet()) { it.jobId }
        val completions = tasks.asSequence()
            .mapNotNull { task ->
                task.unassignedAt ?: task.completedAt?.takeIf { task.phase == TaskPhase.DROPOFF }
            } + stack.completed.asSequence()
            .filterIsInstance<FlowCardSnapshot.PostTask>()
            // A merged two-dash stack carries the other dash's receipts; only a receipt for a job this
            // region knows (its own tasks) moves this wait.
            .filter { it.jobId in ownJobs }
            .mapNotNull { it.phaseEndedAt }
        return (completions + sequenceOf(start) + listOfNotNull(resumedAt).asSequence())
            .filter { it >= start }.maxOrNull()
    }
}
