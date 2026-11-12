package cloud.trotter.log.gig.core.data.analytics

import cloud.trotter.log.gig.core.database.analytics.DeliveryRecordEntity
import cloud.trotter.log.gig.core.database.analytics.ReceiptMatchRowEntity
import cloud.trotter.log.gig.core.database.analytics.ReceiptRecordEntity
import cloud.trotter.log.gig.core.database.analytics.SessionRecordEntity
import cloud.trotter.log.gig.core.database.analytics.SessionWithCashRow
import cloud.trotter.log.gig.core.database.analytics.ShiftPayRow
import cloud.trotter.log.gig.domain.analytics.MilesBasis
import cloud.trotter.log.gig.domain.analytics.DeliveryRecord
import cloud.trotter.log.gig.domain.analytics.SessionRecord
import cloud.trotter.log.gig.domain.analytics.SessionMilesRule
import cloud.trotter.log.gig.domain.analytics.SessionReportRule
import cloud.trotter.log.gig.domain.analytics.receipts.ActiveMatchRow
import cloud.trotter.log.gig.domain.analytics.receipts.SavedReceipt
import cloud.trotter.log.gig.domain.model.event.payload.ReceiptMatchRole
import cloud.trotter.log.gig.domain.state.Platform
import cloud.trotter.log.gig.domain.state.ReceiptScope

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
    payBasis = payBasis,
    netProfit = netProfit,
    realizedMiles = realizedMiles,
    realizedMinutes = realizedMinutes,
    tip = tip,
    basePay = basePay,
    cashTip = cashTip,
    milesToStore = milesToStore,
    milesToDropoff = milesToDropoff,
    milesBasis = try { MilesBasis.valueOf(milesBasis) } catch (_: IllegalArgumentException) { MilesBasis.UNKNOWN },
    sessionAssigned = sessionAssigned == 1,
    receiptRepriced = receiptRepricedAt != null,
    driverAdjustedAt = driverAdjustedAt,
    jobOfferHashes = StringListColumn.decode(jobOfferHashes) ?: listOfNotNull(soleOfferHash),
)

/** #1599: a saved receipt as the matching rules read it. */
internal fun ReceiptRecordEntity.toSaved(): SavedReceipt = SavedReceipt(
    evidenceId = evidenceId,
    jobId = jobId,
    subjectTaskId = subjectTaskId,
    firstSeenAt = firstSeenAt,
    totalCents = totalCents,
    basePayCents = basePayCents,
    tipCents = tipCents,
    proven = scope == ReceiptScope.PROVEN_SINGLE.name,
)

/** #1599: one row an active receipt match wrote. */
internal fun ReceiptMatchRowEntity.toActive(): ActiveMatchRow = ActiveMatchRow(
    commandId = commandId,
    evidenceId = evidenceId,
    deliveryEventSequenceId = deliveryEventSequenceId,
    role = ReceiptMatchRole.valueOf(role),
    newPayCents = newPayCents,
    matchedAt = matchedAt,
)

/**
 * Session row → domain. [deliveredPay] is the session's recorded pay ([SessionReportRule.recordedPayOf], or the
 * SQL `d.deliveredPay`), REQUIRED so every caller supplies the input the effective report's #1459 floor needs.
 */
internal fun SessionRecordEntity.toDomain(deliveredPay: Double?): SessionRecord = SessionRecord(
    sessionId = sessionId,
    platform = Platform.fromWire(platform) ?: Platform.Unknown,
    startedAt = startedAt,
    endedAt = endedAt,
    reportedEarnings = SessionReportRule.effectiveReported(
        reportedEarnings, endSource, reportOverrideMode, reportOverride, deliveredPay,
    ),
    machineReportedEarnings = reportedEarnings,
    reportOverrideMode = reportOverrideMode,
    reportOverride = reportOverride,
    reportCorrectedAt = reportCorrectedAt,
    endSource = endSource,
    reportedDurationMillis = reportedDurationMillis,
    miles = SessionMilesRule.effective(startOdometer, lastOdometer, milesOverride),
    machineMiles = SessionMilesRule.machine(startOdometer, lastOdometer),
    milesOverride = milesOverride,
    deliveries = deliveries,
    jobsCompleted = jobsCompleted,
    offersReceived = offersReceived,
    offersAccepted = offersAccepted,
    offersDeclined = offersDeclined,
    offersTimeout = offersTimeout,
)

/**
 * Recent-dashes row → domain (#688 F7): the embedded session row mapped as usual, with its Σ cash
 * tips carried onto [SessionRecord.cashTips] for the additive "+cash" marker.
 */
internal fun SessionWithCashRow.toDomain(): SessionRecord = session.toDomain(deliveredPay).copy(cashTips = cash)

/** Shift row with its recorded pay → domain (#1443/#1459): the recorded pay floors the effective report. */
internal fun ShiftPayRow.toDomain(): SessionRecord = session.toDomain(recordedPay)

