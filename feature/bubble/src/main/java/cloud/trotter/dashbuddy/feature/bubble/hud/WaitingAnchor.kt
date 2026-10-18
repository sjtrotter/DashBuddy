package cloud.trotter.dashbuddy.feature.bubble.hud

import cloud.trotter.dashbuddy.domain.model.cards.CardStack
import cloud.trotter.dashbuddy.domain.model.cards.FlowCardSnapshot
import cloud.trotter.dashbuddy.domain.state.Task
import cloud.trotter.dashbuddy.domain.state.TaskPhase

object WaitingAnchor {
    /** Resume is a future seam; S2b deliberately lets the wait run through a pause. */
    fun resolve(sessionStartedAt: Long?, tasks: List<Task>, stack: CardStack, resumedAt: Long? = null): Long? {
        val start = sessionStartedAt ?: return null
        val completions = tasks.asSequence()
            .filter { it.phase == TaskPhase.DROPOFF && it.unassignedAt == null }
            .mapNotNull { it.completedAt } + stack.completed.asSequence()
            .filterIsInstance<FlowCardSnapshot.PostTask>().mapNotNull { it.phaseEndedAt }
        return (completions + sequenceOf(start) + listOfNotNull(resumedAt).asSequence())
            .filter { it >= start }.maxOrNull()
    }
}
