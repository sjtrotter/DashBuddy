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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
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

    fun getAllEvents(): Flow<List<AppEvent>> =
        dao.getAllEvents().map { rows -> rows.map { it.toDomain() } }

    fun getEventsForDash(dashId: String): Flow<List<AppEvent>> =
        dao.getEventsForDash(dashId).map { rows -> rows.map { it.toDomain() } }

    fun getEventsByType(type: AppEventType): Flow<List<AppEvent>> =
        dao.getEventsByType(type).map { rows -> rows.map { it.toDomain() } }

    fun getEventsInTimeRange(startTime: Long, endTime: Long): Flow<List<AppEvent>> =
        dao.getEventsInTimeRange(startTime, endTime).map { rows -> rows.map { it.toDomain() } }

    // ── Entity ↔ domain mapping ─────────────────────────────────────────

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
}
