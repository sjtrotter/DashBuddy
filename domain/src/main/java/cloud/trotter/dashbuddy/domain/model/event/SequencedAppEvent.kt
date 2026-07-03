package cloud.trotter.dashbuddy.domain.model.event

/**
 * An [AppEvent] paired with its storage-layer sequence id and device [metadata]
 * (#314). The domain [AppEvent] deliberately carries neither — those are Room
 * entity concerns mapped at the repository boundary — but the analytics projector
 * needs both: the [sequenceId] is its watermark/idempotency key, and [metadata]
 * (odometer) is the only durable mileage signal.
 *
 * Assembled by `AppEventRepo.getEventsAfter` at the same decode edge as `AppEvent`,
 * so a malformed payload/metadata keeps the existing WARN-and-null contract.
 */
data class SequencedAppEvent(
    val sequenceId: Long,
    val event: AppEvent,
    val metadata: EventMetadata? = null,
)
