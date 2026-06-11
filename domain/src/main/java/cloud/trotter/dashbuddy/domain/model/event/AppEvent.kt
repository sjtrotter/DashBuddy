package cloud.trotter.dashbuddy.domain.model.event

import cloud.trotter.dashbuddy.domain.model.event.payload.AppEventPayload

/**
 * A domain event in the append-only app-event log (#354).
 *
 * This is the model the state layer EMITS (via `AppEffect.LogEvent`) and the
 * UI CONSUMES (via `AppEventRepo` / `FlowCardMapper`). Storage concerns —
 * auto-increment sequence, JSON payload column, device metadata — live on the
 * Room entity in :core:database and are mapped at the repository boundary.
 *
 * [occurredAt] is the OBSERVATION timestamp of the transition that emitted the
 * event, not the wall clock at execution time. That makes the event (and the
 * idempotency key derived from it) identical between live execution and
 * crash-recovery replay (#300).
 */
data class AppEvent(
    val type: AppEventType,
    val occurredAt: Long,
    /** The dash session this event belongs to (the entity's aggregateId). */
    val sessionId: String?,
    val payload: AppEventPayload? = null,
)
