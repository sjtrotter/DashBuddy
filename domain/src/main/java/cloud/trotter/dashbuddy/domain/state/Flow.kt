package cloud.trotter.dashbuddy.domain.state

/**
 * Platform-agnostic representation of what the worker is currently doing
 * in the gig flow. Each value maps to a family of [ParsedFields] subtypes.
 *
 * Wire values are the canonical strings used in JSON rulesets and the
 * observation log. The enum is the Kotlin-side source of truth; any rule
 * emitting a wire value not in this set is rejected at load time.
 *
 * NAMING (#366, decided): this enum collides with `kotlinx.coroutines.flow.Flow`
 * and stays that way. The handful of files needing both use a fully-qualified
 * coroutines import; renaming (~21 consumers + the wire-format mental model)
 * buys nothing behavioral. Revisit only if the collision starts causing real
 * bugs rather than import friction.
 *
 * @see Mode for the orthogonal availability axis.
 */
enum class Flow(val wire: String) {
    Idle("idle"),
    OfferPresented("offer:presented"),
    TaskPickupNavigation("task:pickup:navigation"),
    TaskPickupArrived("task:pickup:arrived"),
    TaskDropoffNavigation("task:dropoff:navigation"),
    TaskDropoffArrived("task:dropoff:arrived"),
    PostTask("post:task"),
    /**
     * The platform's own authoritative statement that the active task was **abandoned** — the
     * dasher unassigned the order mid-flow (#736). A teardown signal, NOT a task subflow
     * ([isTaskFlow] stays false for it): it drives an inline abandon of the active task (marker set,
     * `completedAt` left null; the close-out sweep's `unassignedAt == null` filter — keyed on that
     * marker — is what stops a fabricated `PICKUP_CONFIRMED`), never a task update. Platform-neutral
     * by construction — any ruleset can declare
     * `task:unassigned`; DoorDash specificity lives only in the rule JSON that emits it.
     */
    TaskUnassigned("task:unassigned"),
    /**
     * "In an active job, leg unknown" — declared by a platform's **coarse** in-job surface that
     * cannot distinguish pickup from dropoff legs (e.g. a screen that only says "on trip", Uber's
     * `on_job_view`). Deliberately **phase-less**: [toTaskPhase] maps it to `null`, so it is
     * structurally inert to task mint/displace/resume (the task lifecycle early-returns on a null
     * phase). It exists to (a) **consume an accepted offer into a job** — it counts as a task flow
     * for job-lifecycle purposes ([isTaskFlow] is true), (b) **hold mode Online**, and (c) give the
     * UI an honest "active job" state.
     *
     * It is **NOT** "transit": the declaring screen may be showing while the driver stands at the
     * counter, so labelling it a leg would be dishonest. "En route to a known leg" is already
     * `<destination>:navigation`; a peer `TaskPhase.TRANSIT` was considered and rejected (see
     * ADR-0002 amendment 2026-07-15). Platform-neutral by construction — any ruleset can declare
     * `task:active`; platform specificity lives only in the rule JSON that emits it.
     */
    TaskActive("task:active"),
    SessionEnded("session:ended"),
    ;

    companion object {
        private val byWire = entries.associateBy { it.wire }

        /** Resolve a wire-format string to a [Flow], or null if unknown. */
        fun fromWire(wire: String): Flow? = byWire[wire]
    }
}
