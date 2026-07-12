package cloud.trotter.dashbuddy.core.data.event

import androidx.room.withTransaction
import cloud.trotter.dashbuddy.core.database.DashBuddyDatabase
import cloud.trotter.dashbuddy.core.database.effects.EffectsFiredDao
import cloud.trotter.dashbuddy.core.database.effects.EffectsFiredEntity
import cloud.trotter.dashbuddy.core.database.event.AppEventDao
import cloud.trotter.dashbuddy.core.database.event.AppEventEntity
import cloud.trotter.dashbuddy.domain.model.event.AppEvent
import cloud.trotter.dashbuddy.domain.model.event.AppEventCodec
import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import cloud.trotter.dashbuddy.domain.model.event.EventMetadata
import cloud.trotter.dashbuddy.domain.model.event.SequencedAppEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository boundary for the app-event log (#354). Consumers read and write
 * domain [AppEvent]s; the Room entity (auto-increment sequence, JSON payload
 * column, device metadata) is assembled and decoded HERE, nowhere else.
 */
@Singleton
class AppEventRepo @Inject constructor(
    private val db: DashBuddyDatabase,
    private val dao: AppEventDao,
    private val effectsFiredDao: EffectsFiredDao,
) {

    /**
     * Insert [event] and write its `effects_fired` idempotency mark in ONE
     * transaction (#351/#354). Either both commit or neither does — crash
     * recovery can no longer find an inserted-but-unmarked event (double-run)
     * or a marked-but-uninserted one (silent loss).
     */
    suspend fun insertAndMark(
        event: AppEvent,
        metadataJson: String?,
        effectKey: String,
        correlationVersion: Long,
    ) {
        db.withTransaction {
            dao.insert(event.toEntity(metadataJson))
            effectsFiredDao.markFired(
                EffectsFiredEntity(
                    effectKey = effectKey,
                    firedAt = System.currentTimeMillis(),
                    correlationVersion = correlationVersion,
                )
            )
        }
    }

    /**
     * Append a user-initiated event (a #650 correction — `MANUAL_DELIVERY` / `PAY_ADJUSTMENT`) with a
     * plain insert and NO `effects_fired` idempotency mark. The mark exists only for state-machine
     * effect idempotency (crash-recovery double-run protection of effect-driven `LogEvent`s keyed by an
     * effect key); a user-initiated correction has no effect key and is never replayed by the effect
     * engine, so a bare insert is the correct — and complete — write. The analytics projector's
     * `maxSequenceId()` drain picks it up automatically.
     */
    suspend fun appendUserEvent(event: AppEvent, metadataJson: String? = null) {
        dao.insert(event.toEntity(metadataJson))
    }

    fun getAllEvents(): Flow<List<AppEvent>> =
        dao.getAllEvents().map { rows -> rows.map { it.toDomain() } }

    fun getEventsForSession(dashId: String): Flow<List<AppEvent>> =
        dao.getEventsForSession(dashId).map { rows -> rows.map { it.toDomain() } }

    /** Durable "last completed dash" fallback for the bubble HUD (#459). */
    fun getMostRecentSessionId(): Flow<String?> = dao.getMostRecentSessionId()

    fun getEventsByType(type: AppEventType): Flow<List<AppEvent>> =
        dao.getEventsByType(type).map { rows -> rows.map { it.toDomain() } }

    fun getEventsInTimeRange(startTime: Long, endTime: Long): Flow<List<AppEvent>> =
        dao.getEventsInTimeRange(startTime, endTime).map { rows -> rows.map { it.toDomain() } }

    /**
     * A page of [SequencedAppEvent]s after [after] (exclusive), oldest-first, for
     * the analytics projector's paged fold (#314). Unlike the other reads, this
     * carries the entity `sequenceId` and decoded [EventMetadata] the fold needs.
     * Malformed payload OR metadata degrades to null at this edge (WARN), never a
     * throw — same fail-null contract as [toDomain].
     */
    suspend fun getEventsAfter(after: Long, limit: Int): List<SequencedAppEvent> =
        dao.getEventsAfter(after, limit).map { it.toSequenced() }

    // ── Entity ↔ domain mapping ─────────────────────────────────────────

    // #732: this is the event-append path — `occurredAt` below is carried through verbatim
    // from the domain [AppEvent] (which for a graced destructive commit is `pend.since`, the
    // grace-ARM time, not "now"), while `sequenceId` is minted by Room on THIS insert. See
    // AppEventEntity's class KDoc ("sequenceId vs occurredAt") for the resulting ordering
    // invariant and which column each consumer must key on.
    private fun AppEvent.toEntity(metadataJson: String?) = AppEventEntity(
        aggregateId = sessionId,
        eventType = type,
        eventPayload = payload?.let(AppEventCodec::encodePayload) ?: "{}",
        occurredAt = occurredAt,
        metadata = metadataJson,
    )

    private fun AppEventEntity.toDomain() = AppEvent(
        type = eventType,
        occurredAt = occurredAt,
        sessionId = aggregateId,
        payload = try {
            AppEventCodec.decodePayload(eventType, eventPayload)
        } catch (e: Exception) {
            // LOUD at the edge (#353), null to the consumer — the fold treats a
            // missing payload exactly like a legacy empty row.
            Timber.w(e, "AppEventRepo: failed to decode %s payload", eventType)
            null
        },
    )

    private fun AppEventEntity.toSequenced() = SequencedAppEvent(
        sequenceId = sequenceId,
        event = toDomain(),
        metadata = metadata?.let { decodeMetadata(it, eventType) },
    )

    /**
     * Decode a metadata JSON blob written by Gson (`{"odometer":…}` or the
     * test-mode `{"test_mode":true}` shape). `ignoreUnknownKeys` + all-default
     * fields make both shapes parse; a malformed blob fails to null (WARN), never
     * a throw — a missing odometer must not sink the whole fold batch.
     */
    private fun decodeMetadata(json: String, eventType: AppEventType): EventMetadata? =
        try {
            metadataJson.decodeFromString<EventMetadata>(json)
        } catch (e: Exception) {
            Timber.w(e, "AppEventRepo: failed to decode %s metadata", eventType)
            null
        }

    private companion object {
        private val metadataJson = Json { ignoreUnknownKeys = true }
    }
}
