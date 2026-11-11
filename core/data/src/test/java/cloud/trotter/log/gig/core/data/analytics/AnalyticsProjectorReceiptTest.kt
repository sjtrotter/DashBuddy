package cloud.trotter.log.gig.core.data.analytics

import androidx.room.Room
import cloud.trotter.log.gig.core.data.event.AppEventRepo
import cloud.trotter.log.gig.core.data.settings.AppPreferencesRepository
import cloud.trotter.log.gig.core.database.GigLogDatabase
import cloud.trotter.log.gig.core.database.analytics.AnalyticsProjectionStateEntity
import cloud.trotter.log.gig.core.database.analytics.ReceiptRecordEntity
import cloud.trotter.log.gig.core.database.event.AppEventEntity
import cloud.trotter.log.gig.domain.analytics.AnalyticsEventFixtures.acceptedOffer
import cloud.trotter.log.gig.domain.analytics.AnalyticsEventFixtures.evaluation
import cloud.trotter.log.gig.domain.evaluation.UserEconomy
import cloud.trotter.log.gig.domain.model.event.AppEventCodec
import cloud.trotter.log.gig.domain.model.event.AppEventType
import cloud.trotter.log.gig.domain.model.event.payload.AppEventPayload
import cloud.trotter.log.gig.domain.model.event.payload.DeliveryPayload
import cloud.trotter.log.gig.domain.model.event.payload.ReceiptObservedPayload
import cloud.trotter.log.gig.domain.model.event.payload.SessionEndSource
import cloud.trotter.log.gig.domain.model.event.payload.SessionStartPayload
import cloud.trotter.log.gig.domain.model.event.payload.SessionStartSource
import cloud.trotter.log.gig.domain.model.event.payload.SessionStopPayload
import cloud.trotter.log.gig.domain.model.pay.ParsedPay
import cloud.trotter.log.gig.domain.model.pay.ParsedPayItem
import cloud.trotter.log.gig.domain.state.Platform
import cloud.trotter.log.gig.domain.state.ReceiptScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * #1598 — `RECEIPT_OBSERVED` folds into `receipt_records` and nowhere else, the version wipe clears and refolds it,
 * and a pre-marker stream rebuilds to the same delivery pay. A class of its own rather than more of
 * `AnalyticsProjectorTest` (already past 2 200 lines, principle 3).
 */
@RunWith(RobolectricTestRunner::class)
class AnalyticsProjectorReceiptTest {

    private class Harness {
        val db: GigLogDatabase = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), GigLogDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        private val prefs: AppPreferencesRepository = mock { on { userEconomy } doReturn flowOf(UserEconomy()) }
        val projector = AnalyticsProjector(db, AppEventRepo(db, db.appEventDao(), db.effectsFiredDao()), db.appEventDao(), db.analyticsDao(), prefs)

        suspend fun insert(type: AppEventType, sessionId: String?, at: Long, payload: AppEventPayload?, odometer: Double? = null): Long =
            db.appEventDao().insert(
                AppEventEntity(
                    aggregateId = sessionId,
                    eventType = type,
                    eventPayload = payload?.let(AppEventCodec::encodePayload) ?: "{}",
                    occurredAt = at,
                    metadata = odometer?.let { """{"odometer":$it}""" },
                ),
            )

        suspend fun deliveries() = db.analyticsDao().deliveriesBetween(0, Long.MAX_VALUE)
        suspend fun sessions() = db.analyticsDao().sessionsBetween(0, Long.MAX_VALUE)
        suspend fun offers(sid: String) = db.analyticsDao().offersForSession(sid).first()
        suspend fun receipts(sid: String) = db.receiptDao().receiptsInSession(sid)
    }

    private val harnesses = mutableListOf<Harness>()
    private fun harness() = Harness().also { harnesses += it }

    @After
    fun tearDown() = harnesses.forEach { it.db.close() }

    private val itemized = ParsedPay(
        appPayComponents = listOf(ParsedPayItem("Base Pay", 9.70)),
        customerTips = listOf(ParsedPayItem("Store A", 7.00)),
    )

    private fun observed(revision: Int, at: Long, expanded: Boolean) = ReceiptObservedPayload(
        evidenceId = "receipt:T1:4000", revision = revision, platform = Platform.DoorDash.wire, jobId = "J1",
        subjectTaskId = "T1", firstSeenAt = 4_000L, observedAt = at, totalPay = 16.70,
        parsedPay = itemized.takeIf { expanded }, isExpanded = expanded, scope = ReceiptScope.PROVEN_SINGLE,
    )

    /**
     * One shift with two evidence revisions. With [evidence] false each `RECEIPT_OBSERVED` is replaced by a
     * session-less `SCREEN_VIEWED` (folded as nothing at all), so every other event keeps its sequence id and the
     * two projections compare byte for byte.
     */
    private suspend fun Harness.seed(evidence: Boolean): List<Long> {
        val seqs = mutableListOf<Long>()
        suspend fun receiptOr(revision: Int, at: Long, expanded: Boolean, odo: Double) {
            seqs += if (evidence) {
                insert(AppEventType.RECEIPT_OBSERVED, "S1", at, observed(revision, at, expanded), odometer = odo)
            } else {
                insert(AppEventType.SCREEN_VIEWED, null, at, null)
            }
        }
        insert(
            AppEventType.DASH_START, "S1", 1_000,
            SessionStartPayload("S1", Platform.DoorDash.name, 1_000, SessionStartSource.INTERACTION, "x"),
            odometer = 100.0,
        )
        insert(
            AppEventType.OFFER_ACCEPTED, "S1", 2_000,
            acceptedOffer(2_000, evaluation(net = 9.0, dist = 3.0, opCpm = 0.25, payAmount = 12.0, dollarsPerMile = 3.0)),
        )
        receiptOr(1, 4_000, expanded = false, odo = 104.0)
        insert(
            AppEventType.DELIVERY_COMPLETED, "S1", 4_500,
            DeliveryPayload(
                jobId = "J1", taskId = "T1", storeName = "Store A", customerHash = "c1",
                phaseStartedAt = 3_000, arrivedAt = 3_800, completedAt = 4_500, totalPay = 16.70,
                receiptScope = ReceiptScope.PROVEN_SINGLE, receiptEvidenceId = "receipt:T1:4000",
            ),
            odometer = 105.0,
        )
        receiptOr(2, 5_000, expanded = true, odo = 107.0)
        insert(
            AppEventType.DASH_STOP, "S1", 6_000,
            SessionStopPayload("S1", endedAt = 6_000, source = SessionEndSource.SUMMARY_SCREEN, totalEarnings = 16.70),
            odometer = 110.0,
        )
        return seqs
    }

    @Test
    fun `two revisions of one receipt fold into one row holding the latest, first seen at the first`() = runBlocking {
        val h = harness()
        val seqs = h.seed(evidence = true)
        h.projector.catchUp()
        val row = h.receipts("S1").single()
        assertEquals(
            ReceiptRecordEntity(
                evidenceId = "receipt:T1:4000", sessionId = "S1", platform = Platform.DoorDash.wire, jobId = "J1",
                subjectTaskId = "T1", firstSeenAt = 4_000L, lastSeenAt = 5_000L, totalCents = 1_670L,
                basePayCents = 970L, tipCents = 700L, isExpanded = true, scope = "PROVEN_SINGLE", proofGap = null,
                revision = 2, lastEventSequenceId = seqs.last(),
            ),
            row,
        )
    }

    @Test
    fun `the evidence events change no delivery, session or offer row`() = runBlocking {
        val with = harness().also { it.seed(evidence = true); it.projector.catchUp() }
        val without = harness().also { it.seed(evidence = false); it.projector.catchUp() }
        assertEquals(without.deliveries(), with.deliveries())
        assertEquals(without.sessions(), with.sessions())
        assertEquals(without.offers("S1"), with.offers("S1"))
        assertTrue(without.receipts("S1").isEmpty())
        assertEquals(1, with.receipts("S1").size)
    }

    @Test
    fun `a version rebuild wipes receipt_records and the refold restores them`() = runBlocking {
        val h = harness()
        h.seed(evidence = true)
        h.projector.catchUp()
        val folded = h.receipts("S1")
        val ghost = folded.single().copy(evidenceId = "receipt:GHOST:1", subjectTaskId = "GHOST")
        h.db.receiptDao().upsert(ghost)
        assertEquals(2, h.receipts("S1").size)

        h.db.analyticsDao().setWatermark(AnalyticsProjectionStateEntity(watermarkSequenceId = 6, projectorVersion = 99))
        h.projector.catchUp()
        assertEquals("the ghost is wiped and the fold restored", folded, h.receipts("S1"))
    }

    @Test
    fun `a rebuild of a pre-marker stream gives the same delivery pay as the incremental fold`() = runBlocking {
        val h = harness()
        h.insert(
            AppEventType.DASH_START, "S2", 1_000,
            SessionStartPayload("S2", Platform.DoorDash.name, 1_000, SessionStartSource.INTERACTION, "x"),
            odometer = 0.0,
        )
        // A pre-#1598 stacked job: two shares of one receipt, then a sole drop's bare receipt total.
        h.insert(
            AppEventType.DELIVERY_COMPLETED, "S2", 2_000,
            DeliveryPayload(
                jobId = "J1", taskId = "T1", storeName = "Store A", customerHash = "c1", phaseStartedAt = 1_500,
                completedAt = 2_000, totalPay = 20.0, parsedPay = itemized, dropRealizedPay = 10.0,
            ),
            odometer = 3.0,
        )
        h.projector.catchUp()
        h.insert(
            AppEventType.DELIVERY_COMPLETED, "S2", 3_000,
            DeliveryPayload(
                jobId = "J1", taskId = "T2", storeName = "Store B", customerHash = "c2", phaseStartedAt = 2_500,
                completedAt = 3_000, totalPay = 20.0, parsedPay = itemized, dropRealizedPay = 10.0,
            ),
            odometer = 6.0,
        )
        h.insert(
            AppEventType.DELIVERY_COMPLETED, "S2", 4_000,
            DeliveryPayload(
                jobId = "J2", taskId = "T3", storeName = "Store C", customerHash = "c3", phaseStartedAt = 3_500,
                completedAt = 4_000, totalPay = 8.25,
            ),
            odometer = 9.0,
        )
        h.projector.catchUp()
        val incremental = h.deliveries()
        assertEquals(listOf(10.0, 10.0, 8.25), incremental.map { it.realizedPay })

        h.db.analyticsDao().setWatermark(AnalyticsProjectionStateEntity(watermarkSequenceId = 4, projectorVersion = 99))
        h.projector.catchUp()
        assertEquals(incremental, h.deliveries())
    }
}
