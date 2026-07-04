package cloud.trotter.dashbuddy.core.data.analytics

import cloud.trotter.dashbuddy.core.database.analytics.DeliveryRecordEntity
import cloud.trotter.dashbuddy.core.database.analytics.SessionRecordEntity
import cloud.trotter.dashbuddy.domain.analytics.DeliveryRecord
import cloud.trotter.dashbuddy.domain.analytics.SessionRecord
import cloud.trotter.dashbuddy.domain.state.Platform

/**
 * Room-entity → domain mappers for the analytics read model (#314 PR3). The
 * repository maps at the boundary so consumers never see persistence types
 * (the `AppEventRepo.toDomain` convention). Platform is registry-resolved from the
 * stored wire string, never compared as a literal (Principle 8).
 */

internal fun DeliveryRecordEntity.toDomain(): DeliveryRecord = DeliveryRecord(
    eventSequenceId = eventSequenceId,
    sessionId = sessionId,
    platform = Platform.fromWire(platform) ?: Platform.Unknown,
    jobId = jobId,
    taskId = taskId,
    storeName = storeName,
    completedAt = completedAt,
    realizedPay = realizedPay,
    netProfit = netProfit,
    realizedMiles = realizedMiles,
    realizedMinutes = realizedMinutes,
    tip = tip,
    basePay = basePay,
)

internal fun SessionRecordEntity.toDomain(): SessionRecord = SessionRecord(
    sessionId = sessionId,
    platform = Platform.fromWire(platform) ?: Platform.Unknown,
    startedAt = startedAt,
    endedAt = endedAt,
    reportedEarnings = reportedEarnings,
    reportedDurationMillis = reportedDurationMillis,
    miles = if (startOdometer != null && lastOdometer != null) {
        (lastOdometer!! - startOdometer!!).coerceAtLeast(0.0)
    } else {
        null
    },
    deliveries = deliveries,
    jobsCompleted = jobsCompleted,
    offersReceived = offersReceived,
    offersAccepted = offersAccepted,
    offersDeclined = offersDeclined,
    offersTimeout = offersTimeout,
)
