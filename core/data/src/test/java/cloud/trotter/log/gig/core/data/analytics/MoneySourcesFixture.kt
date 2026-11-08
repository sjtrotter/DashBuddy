package cloud.trotter.log.gig.core.data.analytics

import cloud.trotter.log.gig.core.database.analytics.AnalyticsDao
import cloud.trotter.log.gig.core.database.analytics.DeliveryRecordEntity
import cloud.trotter.log.gig.core.database.analytics.SessionRecordEntity
import cloud.trotter.log.gig.domain.analytics.PayBasis
import cloud.trotter.log.gig.domain.analytics.PeriodEconomics
import cloud.trotter.log.gig.domain.model.event.payload.SessionEndSource
import cloud.trotter.log.gig.domain.model.event.payload.SessionReportOperation
import cloud.trotter.log.gig.domain.state.Platform
import org.junit.Assert.assertEquals

/** Shared #1135 fixture: every source, a payless row, cash, and a null-session delivery on each platform. */
internal suspend fun seedMoneySources(dao: AnalyticsDao, start: Long): List<DeliveryRecordEntity> {
    val deliveries = mutableListOf<DeliveryRecordEntity>()
    var seq = 0L
    for ((index, platform) in listOf(Platform.DoorDash, Platform.Uber).withIndex()) {
        val scale = index + 1.0
        fun session(name: String, report: Double?, source: String? = SessionEndSource.SUMMARY_SCREEN) = SessionRecordEntity(
            sessionId = "${platform.wire}-$name", platform = platform.wire, startedAt = start,
            endedAt = start + 1000, lastEventAt = start + 1000, endSource = source,
            startOdometer = null, lastOdometer = null, reportedEarnings = report?.times(scale),
            reportedDurationMillis = 1000, offersReceived = 0, offersAccepted = 0, offersDeclined = 0,
            offersTimeout = 0, deliveries = 1, jobsCompleted = 1,
        )
        val sessions = listOf(
            session("summary", 30.0),
            session("zero", 0.0),
            session("counter", 20.0, SessionEndSource.EARLY_OFFLINE),
            // #1459: an early end whose stale running total (12) is below its recorded pay (16).
            session("stale", 12.0, SessionEndSource.EARLY_OFFLINE),
            session("set", 99.0, SessionEndSource.EARLY_OFFLINE).copy(
                reportOverrideMode = SessionReportOperation.SET, reportOverride = 25.0 * scale),
            session("clear", 99.0).copy(reportOverrideMode = SessionReportOperation.CLEAR),
            session("unreported", 0.0, SessionEndSource.EARLY_OFFLINE),
            session("empty", null, null),
        )
        sessions.forEach { dao.upsertSession(it) }
        fun delivery(name: String?, pay: Double?, basis: String, base: Double? = null, tip: Double? = null, cash: Double? = null): DeliveryRecordEntity {
            seq++
            return DeliveryRecordEntity(
                eventSequenceId = seq, sessionId = name?.let { "${platform.wire}-$it" }, platform = platform.wire,
                jobId = "job-$seq", taskId = "task-$seq", storeName = null, customerHash = null, addressHash = null,
                phaseStartedAt = start, arrivedAt = null, completedAt = start + 500, deadlineMillis = null,
                realizedPay = pay?.times(scale), payBasis = basis, basePay = base?.times(scale), tip = tip?.times(scale),
                cashTip = cash?.times(scale), odometerAtCompletion = null, realizedMiles = null, realizedMinutes = null,
                frozenCostPerMile = null, netProfit = pay?.times(scale), costBasis = "NONE",
            )
        }
        deliveries += listOf(
            delivery("summary", 25.0, PayBasis.RECEIPT_TOTAL, base = 10.0, tip = 15.0, cash = 3.0),
            delivery("zero", 4.0, PayBasis.RECEIPT_TOTAL, base = 1.0, tip = 3.0),
            delivery("counter", 18.0, PayBasis.OFFER_PAY),
            delivery("stale", 16.0, PayBasis.DROP_SHARE),
            delivery("set", 20.0, PayBasis.RECEIPT_TOTAL),
            delivery("clear", 7.0, PayBasis.OFFER_PAY),
            delivery("unreported", 11.0, PayBasis.DROP_SHARE),
            delivery("empty", null, PayBasis.NONE),
            delivery(null, 13.0, PayBasis.RECEIPT_TOTAL, base = 5.0, tip = 8.0, cash = 2.0),
        )
    }
    deliveries.forEach { dao.upsertDelivery(it) }
    return deliveries
}

/** The gross sources [seedMoneySources] seeds, at [scale] per platform and [copies] platforms. */
internal fun assertMoneySources(e: PeriodEconomics, scale: Double, copies: Int = 1) {
    val sources = e.grossSources
    assertEquals(30.0 * scale, sources.summaryReported, 0.001)
    assertEquals(2 * copies, sources.summaryDashes)
    assertEquals(20.0 * scale, sources.counterReported, 0.001)
    assertEquals(copies, sources.counterDashes)
    assertEquals(16.0 * scale, sources.recordedPayReported, 0.001)
    assertEquals(copies, sources.recordedPayDashes)
    assertEquals(25.0 * scale, sources.driverSetReported, 0.001)
    assertEquals(copies, sources.driverSetDashes)
    assertEquals(18.0 * scale, sources.recordedWithoutReport, 0.001)
    assertEquals(2 * copies, sources.unreportedDashesWithPay)
    assertEquals(copies, sources.unreportedDashesNoPay)
    assertEquals(3.0 * scale, sources.sessionCash, 0.001)
    assertEquals(15.0 * scale, e.noSessionPay, 0.001)
    assertEquals(127.0 * scale, e.grossEarnings, 0.001)
    assertEquals(e.grossEarnings, sources.sum(e.noSessionPay), 0.001)
}
