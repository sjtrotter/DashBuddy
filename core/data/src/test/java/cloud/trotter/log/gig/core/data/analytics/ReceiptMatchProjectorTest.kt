package cloud.trotter.log.gig.core.data.analytics

import androidx.room.Room
import cloud.trotter.log.gig.core.data.event.AppEventRepo
import cloud.trotter.log.gig.core.data.settings.AppPreferencesRepository
import cloud.trotter.log.gig.core.database.GigLogDatabase
import cloud.trotter.log.gig.core.database.analytics.AnalyticsProjectionStateEntity
import cloud.trotter.log.gig.core.database.analytics.DeliveryRecordEntity
import cloud.trotter.log.gig.core.database.event.AppEventEntity
import cloud.trotter.log.gig.domain.analytics.AnalyticsEventFixtures.acceptedOffer
import cloud.trotter.log.gig.domain.analytics.AnalyticsEventFixtures.evaluation
import cloud.trotter.log.gig.domain.analytics.PayBasis
import cloud.trotter.log.gig.domain.analytics.checks.ShiftCheck
import cloud.trotter.log.gig.domain.analytics.receipts.ReceiptStatus
import cloud.trotter.log.gig.domain.analytics.receipts.ShiftReceipts
import cloud.trotter.log.gig.domain.evaluation.UserEconomy
import cloud.trotter.log.gig.domain.model.event.AppEventCodec
import cloud.trotter.log.gig.domain.model.event.AppEventType
import cloud.trotter.log.gig.domain.model.event.payload.AppEventPayload
import cloud.trotter.log.gig.domain.model.event.payload.DeliveryAdjustmentPayload
import cloud.trotter.log.gig.domain.model.event.payload.DeliveryPayload
import cloud.trotter.log.gig.domain.model.event.payload.DeliveryReceiptRepricePayload
import cloud.trotter.log.gig.domain.model.event.payload.ManualDeliveryPayload
import cloud.trotter.log.gig.domain.model.event.payload.ReceiptMatchLine
import cloud.trotter.log.gig.domain.model.event.payload.ReceiptMatchRole
import cloud.trotter.log.gig.domain.model.event.payload.ReceiptMatchUndonePayload
import cloud.trotter.log.gig.domain.model.event.payload.ReceiptMatchedPayload
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * #1599: the projector applies the dasher's receipt match and its undo all or nothing, at the match's log position,
 * and folds the same in one batch, in many and from zero. Store names are invented.
 */
@RunWith(RobolectricTestRunner::class)
class ReceiptMatchProjectorTest {

    private class Harness {
        val db: GigLogDatabase = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), GigLogDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        private val prefs: AppPreferencesRepository = mock { on { userEconomy } doReturn flowOf(UserEconomy()) }
        val projector = AnalyticsProjector(db, AppEventRepo(db, db.appEventDao(), db.effectsFiredDao()), db.appEventDao(), db.analyticsDao(), prefs)
        private var odometer = 100.0

        suspend fun insert(type: AppEventType, sid: String?, at: Long, payload: AppEventPayload?, odo: Double? = null): Long =
            db.appEventDao().insert(
                AppEventEntity(
                    aggregateId = sid,
                    eventType = type,
                    eventPayload = payload?.let(AppEventCodec::encodePayload) ?: "{}",
                    occurredAt = at,
                    metadata = odo?.let { """{"odometer":$it}""" },
                ),
            )

        suspend fun start(sid: String, at: Long) = insert(
            AppEventType.DASH_START, sid, at, SessionStartPayload(sid, Platform.DoorDash.name, at, SessionStartSource.INTERACTION, "x"),
            odo = odometer,
        )

        suspend fun accept(sid: String, at: Long, hash: String) = insert(
            AppEventType.OFFER_ACCEPTED, sid, at,
            acceptedOffer(at, evaluation(net = 9.0, dist = 3.0, opCpm = 0.25, payAmount = 12.0, dollarsPerMile = 3.0), hash = hash),
        )

        /** An unproven drop: [offerShare] folds OFFER_PAY, null folds NONE. */
        suspend fun drop(sid: String, at: Long, jobId: String, taskId: String, hashes: List<String>, offerShare: Double?): Long {
            odometer += 2.0
            return insert(
                AppEventType.DELIVERY_COMPLETED, sid, at,
                DeliveryPayload(
                    jobId = jobId, taskId = taskId, storeName = "Store A", customerHash = "c-$taskId", phaseStartedAt = at - 500,
                    arrivedAt = at - 100, completedAt = at, offerPayShare = offerShare, jobOfferHashes = hashes,
                    receiptScope = ReceiptScope.UNPROVEN, receiptEvidenceId = "receipt:$taskId:$at",
                ),
                odo = odometer,
            )
        }

        suspend fun observed(sid: String, at: Long, jobId: String, subject: String, total: Double, parsed: ParsedPay? = null) = insert(
            AppEventType.RECEIPT_OBSERVED, sid, at,
            ReceiptObservedPayload(
                evidenceId = evidence(subject, at), revision = 1, platform = Platform.DoorDash.wire, jobId = jobId,
                subjectTaskId = subject, firstSeenAt = at, observedAt = at, totalPay = total, parsedPay = parsed,
                isExpanded = parsed != null, scope = ReceiptScope.UNPROVEN,
            ),
        )

        suspend fun stop(sid: String, at: Long, total: Double) = insert(
            AppEventType.DASH_STOP, sid, at,
            SessionStopPayload(sid, endedAt = at, source = SessionEndSource.SUMMARY_SCREEN, totalEarnings = total),
            odo = odometer,
        )

        suspend fun match(sid: String, at: Long, cmd: String, evidence: String, cents: Long, vararg lines: ReceiptMatchLine) = insert(
            AppEventType.RECEIPT_MATCHED, sid, at, ReceiptMatchedPayload(cmd, sid, evidence, cents, lines.toList()),
        )

        suspend fun undo(sid: String, at: Long, cmd: String) =
            insert(AppEventType.RECEIPT_MATCH_UNDONE, sid, at, ReceiptMatchUndonePayload(sid, cmd))

        suspend fun payEdit(sid: String, at: Long, target: Long, pay: Double) = insert(
            AppEventType.DELIVERY_ADJUSTMENT, sid, at, DeliveryAdjustmentPayload(targetEventSequenceId = target, sessionId = sid, newPay = pay),
        )

        suspend fun row(seq: Long): DeliveryRecordEntity = db.analyticsDao().deliveryRecord(seq)!!
        suspend fun rows(sid: String) = db.analyticsDao().deliveriesForSession(sid).first()
        suspend fun allRows() = db.analyticsDao().deliveriesBetween(0, Long.MAX_VALUE)
        suspend fun active(sid: String) = db.receiptMatchDao().activeRowsInSession(sid)
        suspend fun deliveryPay(sid: String) = rows(sid).sumOf { it.realizedPay ?: 0.0 }

        /** Every match row, undone ones included, as text (the DAO reads active rows only). */
        fun matchRowDump(): List<String> = db.openHelper.readableDatabase
            .query("SELECT * FROM receipt_match_rows ORDER BY commandId, deliveryEventSequenceId").use { c ->
                buildList {
                    while (c.moveToNext()) add((0 until c.columnCount).joinToString("|") { c.getString(it) ?: "null" })
                }
            }

        suspend fun shiftReceipts(sid: String): ShiftReceipts {
            val read = ReceiptMatchRepository(db.receiptDao(), db.receiptMatchDao()).read(sid).first()
            return ShiftReceipts.of(read.receipts, rows(sid).map { it.toDomain() }, read.active)
        }

        suspend fun checks(sid: String): List<ShiftCheck> = ShiftChecksRepository(
            AnalyticsRepository(db.analyticsDao(), db.appEventDao(), db.hudShiftDao()),
            ShiftTimelineRepository(db.analyticsDao()),
            ReceiptMatchRepository(db.receiptDao(), db.receiptMatchDao()),
        ).checks(sid).first()

        /** Fold one event per batch until drained; returns the summed counted skips. */
        suspend fun foldOneByOne(): Int {
            var skips = 0
            while (true) skips += (projector.processBatch(limit = 1) ?: break).adjustmentSkips
            return skips
        }
    }

    private val harnesses = mutableListOf<Harness>()
    private fun harness() = Harness().also { harnesses += it }

    @After
    fun tearDown() = harnesses.forEach { it.db.close() }

    private fun covered(seq: Long, cents: Long, prior: Long? = null, basis: String = PayBasis.NONE) =
        ReceiptMatchLine(seq, ReceiptMatchRole.COVERED, prior, basis, cents)

    private fun cleared(seq: Long, prior: Long?) = ReceiptMatchLine(seq, ReceiptMatchRole.CLEARED, prior, PayBasis.OFFER_PAY, null)

    /** The columns a match or its undo may write; everything else on the row must stay as it was. */
    private fun DeliveryRecordEntity.moneyOff(like: DeliveryRecordEntity) = copy(
        realizedPay = like.realizedPay, payBasis = like.payBasis, netProfit = like.netProfit, tip = like.tip,
        basePay = like.basePay, driverAdjustedAt = like.driverAdjustedAt,
    )

    /** A one-offer stack: Y1 (NONE, or an OFFER_PAY estimate) and Y2 OFFER_PAY $9.00, then a $20.50 receipt on Y2. */
    private class Stack(val y1: Long, val y2: Long, val evidence: String)

    private suspend fun Harness.stack(sid: String = "S1", base: Long = 1_000, y1Estimate: Double? = null): Stack {
        start(sid, base)
        accept(sid, base + 100, "hY-$sid")
        val y1 = drop(sid, base + 300, "J-$sid", "Y1-$sid", listOf("hY-$sid"), y1Estimate)
        val y2 = drop(sid, base + 400, "J-$sid", "Y2-$sid", listOf("hY-$sid"), 9.0)
        observed(sid, base + 400, "J-$sid", "Y2-$sid", 20.50)
        stop(sid, base + 500, 20.50)
        return Stack(y1, y2, evidence("Y2-$sid", base + 400))
    }

    @Test
    fun `P1 an ordinary two-drop match pays each delivery half the receipt and nothing else moves`() = runBlocking { // non-vacuous
        val h = harness()
        val s = h.stack()
        h.projector.catchUp()
        val before = listOf(h.row(s.y1), h.row(s.y2))
        assertEquals(1, h.checks("S1").count { it is ShiftCheck.ReceiptNotMatched })

        h.match("S1", 7_000, "M1", s.evidence, 2050, covered(s.y1, 1025), covered(s.y2, 1025, 900, PayBasis.OFFER_PAY))
        assertEquals(0, h.projector.catchUp().adjustmentSkips)

        for ((prior, seq) in before.zip(listOf(s.y1, s.y2))) {
            val after = h.row(seq)
            assertEquals(10.25, after.realizedPay!!, 0.0)
            assertEquals(PayBasis.RECEIPT_MATCHED, after.payBasis)
            assertEquals(7_000L, after.driverAdjustedAt)
            assertNotNull(after.realizedMiles)
            assertNotNull(after.frozenCostPerMile)
            val miles = after.realizedMiles!!
            val cpm = after.frozenCostPerMile!!
            assertEquals(10.25 - miles * cpm, after.netProfit!!, 1e-9)
            // originalPayBasis, cash, miles, minutes, store key, legs, offer links: every other column as it was.
            assertEquals(prior, after.moneyOff(prior))
        }
        val matchRows = h.active("S1")
        assertEquals(listOf(s.y1, s.y2), matchRows.map { it.deliveryEventSequenceId })
        assertEquals(listOf(null, 9.0), matchRows.map { it.priorRealizedPay })
        assertEquals(listOf(PayBasis.NONE, PayBasis.OFFER_PAY), matchRows.map { it.priorPayBasis })
        assertEquals(listOf(1025L, 1025L), matchRows.map { it.newPayCents })
        assertEquals(ReceiptStatus.MATCHED, h.shiftReceipts("S1").items.single().status)
        assertTrue(h.checks("S1").none { it is ShiftCheck.ReceiptNotMatched })
    }

    @Test
    fun `P2 the 09-20 sequence - two offers, two receipts, each matched to its own delivery`() = runBlocking { // non-vacuous
        val h = harness()
        h.start("S1", 1_000)
        h.accept("S1", 1_100, "hA")
        h.accept("S1", 1_200, "hB")
        val a = h.drop("S1", 1_300, "J1", "A", listOf("hA", "hB"), 15.0)
        h.observed("S1", 1_300, "J1", "A", 28.51)
        val b = h.drop("S1", 1_400, "J1", "B", listOf("hA", "hB"), 15.0)
        h.observed("S1", 1_400, "J1", "B", 20.50)
        h.stop("S1", 1_500, 49.01)
        h.projector.catchUp()
        assertEquals(listOf(PayBasis.OFFER_PAY, PayBasis.OFFER_PAY), listOf(h.row(a).payBasis, h.row(b).payBasis))
        assertEquals(2, h.checks("S1").count { it is ShiftCheck.ReceiptNotMatched })

        h.match("S1", 7_000, "MA", evidence("A", 1_300), 2851, covered(a, 2851, 1500, PayBasis.OFFER_PAY))
        h.projector.catchUp()
        assertEquals(28.51, h.row(a).realizedPay!!, 0.0)
        assertEquals("two offers: the other estimate stays", 15.0, h.row(b).realizedPay!!, 0.0)
        assertEquals(PayBasis.OFFER_PAY, h.row(b).payBasis)

        h.match("S1", 7_100, "MB", evidence("B", 1_400), 2050, covered(b, 2050, 1500, PayBasis.OFFER_PAY))
        assertEquals(0, h.projector.catchUp().adjustmentSkips)
        assertEquals(49.01, h.deliveryPay("S1"), 1e-9)
        assertTrue(h.checks("S1").none { it is ShiftCheck.ReceiptNotMatched || it is ShiftCheck.MoneyDiffers })
    }

    @Test
    fun `P3 the 10-11 sequence - four offers, five drops, the last drop's receipt moves only that row`() = runBlocking { // non-vacuous
        val h = harness()
        val hashes = listOf("h1", "h2", "h3", "h4")
        h.start("S1", 1_000)
        hashes.forEachIndexed { i, hash -> h.accept("S1", 1_100L + i, hash) }
        val drops = (1..5).map { i -> h.drop("S1", 1_200L + i * 100, "J1", "D$i", hashes, 17.0) }
        h.observed("S1", 1_700, "J1", "D5", 10.40)
        h.stop("S1", 1_800, 85.0)
        h.projector.catchUp()
        val before = drops.dropLast(1).map { h.row(it) }

        h.match("S1", 7_000, "M1", evidence("D5", 1_700), 1040, covered(drops.last(), 1040, 1700, PayBasis.OFFER_PAY))
        h.projector.catchUp()
        assertEquals(10.40, h.row(drops.last()).realizedPay!!, 0.0)
        assertEquals(PayBasis.RECEIPT_MATCHED, h.row(drops.last()).payBasis)
        assertEquals(before, drops.dropLast(1).map { h.row(it) })
    }

    @Test
    fun `P4 the X-Y sequence - a matched read settles its repeat, which can then never pay the same delivery`() = runBlocking { // non-vacuous
        val h = harness()
        h.start("S1", 1_000)
        h.accept("S1", 1_100, "hX")
        h.accept("S1", 1_200, "hY")
        val hashes = listOf("hX", "hY")
        val x = h.drop("S1", 1_300, "J1", "X", hashes, 12.0)
        h.observed("S1", 1_300, "J1", "X", 10.0)
        val y1 = h.drop("S1", 1_400, "J1", "Y1", hashes, 12.0)
        h.observed("S1", 1_400, "J1", "Y1", 20.0)
        val y2 = h.drop("S1", 1_500, "J1", "Y2", hashes, 12.0)
        h.observed("S1", 1_500, "J1", "Y2", 20.0)
        h.stop("S1", 1_600, 30.0)
        h.projector.catchUp()
        assertEquals(3, h.shiftReceipts("S1").open.size)

        h.match("S1", 7_000, "MY", evidence("Y1", 1_400), 2000, covered(y1, 1000, 1200, PayBasis.OFFER_PAY), covered(y2, 1000, 1200, PayBasis.OFFER_PAY))
        h.projector.catchUp()
        val statuses = h.shiftReceipts("S1").items.associate { it.receipt.subjectTaskId to it.status }
        assertEquals(mapOf("X" to ReceiptStatus.OPEN, "Y1" to ReceiptStatus.MATCHED, "Y2" to ReceiptStatus.SETTLED), statuses)

        val y2Row = h.row(y2)
        h.match("S1", 7_100, "MY2", evidence("Y2", 1_500), 2000, covered(y2, 2000, 1000, PayBasis.RECEIPT_MATCHED))
        assertEquals("the repeated read is refused: Y2 is covered", 1, h.projector.catchUp().adjustmentSkips)
        assertEquals(y2Row, h.row(y2))

        h.match("S1", 7_200, "MX", evidence("X", 1_300), 1000, covered(x, 1000, 1200, PayBasis.OFFER_PAY))
        h.projector.catchUp()
        assertEquals(30.0, h.deliveryPay("S1"), 1e-9)
        assertTrue(h.shiftReceipts("S1").open.isEmpty())
    }

    @Test
    fun `P5 matching one drop of a one-offer stack clears its sibling's estimate`() = runBlocking { // non-vacuous
        val h = harness()
        val s = h.stack(y1Estimate = 9.0)
        h.projector.catchUp()
        val y1Before = h.row(s.y1)

        h.match("S1", 7_000, "M1", s.evidence, 2050, covered(s.y2, 2050, 900, PayBasis.OFFER_PAY), cleared(s.y1, 900))
        assertEquals(0, h.projector.catchUp().adjustmentSkips)
        val y1 = h.row(s.y1)
        assertNull(y1.realizedPay)
        assertEquals(PayBasis.NONE, y1.payBasis)
        assertEquals(-y1.realizedMiles!! * y1.frozenCostPerMile!!, y1.netProfit!!, 1e-9)
        assertNull("a cleared row stays open to a later match", y1.driverAdjustedAt)
        assertEquals(y1Before, y1.moneyOff(y1Before))
        assertEquals(
            listOf(ReceiptMatchRole.CLEARED.name, ReceiptMatchRole.COVERED.name),
            h.active("S1").sortedBy { it.deliveryEventSequenceId }.map { it.role },
        )
        assertEquals(20.50, h.deliveryPay("S1"), 1e-9)
    }

    @Test
    fun `P6 a double tap writes once, in one batch or across two`() = runBlocking { // non-vacuous
        for (acrossBatches in listOf(false, true)) {
            val h = harness()
            val s = h.stack()
            h.projector.catchUp()
            val lines = arrayOf(covered(s.y1, 1025), covered(s.y2, 1025, 900, PayBasis.OFFER_PAY))
            h.match("S1", 7_000, "M1", s.evidence, 2050, *lines)
            val first = if (acrossBatches) h.projector.catchUp().adjustmentSkips else 0
            val after = listOf(h.row(s.y1), h.row(s.y2))
            h.match("S1", 7_001, "M1", s.evidence, 2050, *lines)
            val skips = first + h.projector.catchUp().adjustmentSkips
            assertEquals("across=$acrossBatches", 1, skips)
            assertEquals(2, h.active("S1").size)
            if (acrossBatches) assertEquals(after, listOf(h.row(s.y1), h.row(s.y2)))
            assertEquals(7_000L, h.row(s.y1).driverAdjustedAt)
        }
    }

    @Test
    fun `P7 a covered delivery outside the shift, or sequenced after the match, refuses the whole match`() = runBlocking { // non-vacuous
        // Another shift's delivery.
        val h = harness()
        val other = h.stack("S2", base = 100)
        val s = h.stack("S1", base = 1_000)
        h.projector.catchUp()
        val before = h.allRows()
        h.match("S1", 7_000, "M1", s.evidence, 2050, covered(s.y2, 1025, 900, PayBasis.OFFER_PAY), covered(other.y1, 1025))
        assertEquals(1, h.projector.catchUp().adjustmentSkips)
        assertEquals(before, h.allRows())
        assertTrue(h.matchRowDump().isEmpty())

        // A delivery the log records after the match (a late completion of the same shift), in the same batch.
        val late = harness()
        val t = late.stack()
        val matchSeq = late.match("S1", 7_000, "M1", t.evidence, 2050, covered(t.y2, 1025, 900, PayBasis.OFFER_PAY), covered(t.y2 + 100, 1025))
        // Fill sequence ids up to the late drop's planned id with inert events, then add it.
        while (late.insert(AppEventType.SCREEN_VIEWED, null, 7_050, null) < t.y2 + 99) Unit
        val lateDrop = late.drop("S1", 7_100, "J-S1", "Y3-S1", listOf("hY-S1"), null)
        assertEquals(t.y2 + 100, lateDrop)
        assertTrue(lateDrop > matchSeq)
        assertEquals(1, late.projector.catchUp().adjustmentSkips)
        assertEquals(PayBasis.OFFER_PAY, late.row(t.y2).payBasis)
        assertEquals(PayBasis.NONE, late.row(lateDrop).payBasis)
        assertTrue(late.matchRowDump().isEmpty())
    }

    @Test
    fun `P8 a stale preview is refused whole`() = runBlocking { // non-vacuous
        // The dasher fixed Y1 by hand before the match.
        val edited = harness()
        val s = edited.stack()
        edited.payEdit("S1", 6_000, s.y1, 8.0)
        edited.projector.catchUp()
        val y2Before = edited.row(s.y2)
        edited.match("S1", 7_000, "M1", s.evidence, 2050, covered(s.y1, 1025), covered(s.y2, 1025, 900, PayBasis.OFFER_PAY))
        assertEquals(1, edited.projector.catchUp().adjustmentSkips)
        assertEquals(y2Before, edited.row(s.y2))
        assertEquals(8.0, edited.row(s.y1).realizedPay!!, 0.0)
        assertTrue(edited.matchRowDump().isEmpty())

        // The payload's receipt total is not the recorded one.
        val total = harness()
        val t = total.stack()
        total.projector.catchUp()
        val rowsBefore = total.allRows()
        total.match("S1", 7_000, "M1", t.evidence, 2000, covered(t.y1, 1000), covered(t.y2, 1000, 900, PayBasis.OFFER_PAY))
        assertEquals(1, total.projector.catchUp().adjustmentSkips)
        assertEquals(rowsBefore, total.allRows())

        // A second receipt's match on rows the first match already covers.
        val twice = harness()
        val u = twice.stack()
        twice.observed("S1", 1_450, "J-S1", "Y1-S1", 20.50)
        twice.match("S1", 7_000, "M1", u.evidence, 2050, covered(u.y1, 1025), covered(u.y2, 1025, 900, PayBasis.OFFER_PAY))
        twice.match("S1", 7_100, "M2", evidence("Y1-S1", 1_450), 2050, covered(u.y1, 1025, 1025, PayBasis.RECEIPT_MATCHED), covered(u.y2, 1025, 1025, PayBasis.RECEIPT_MATCHED))
        assertEquals(1, twice.projector.catchUp().adjustmentSkips)
        assertEquals(setOf("M1"), twice.active("S1").map { it.commandId }.toSet())
        assertEquals(7_000L, twice.row(u.y1).driverAdjustedAt)
    }

    @Test
    fun `P9 undo restores every row exactly, only while the match still holds, last in first out`() = runBlocking { // non-vacuous
        val h = harness()
        val s = h.stack()
        h.projector.catchUp()
        val before = listOf(h.row(s.y1), h.row(s.y2))
        h.match("S1", 7_000, "M1", s.evidence, 2050, covered(s.y1, 1025), covered(s.y2, 1025, 900, PayBasis.OFFER_PAY))
        h.undo("S1", 7_100, "M1")
        assertEquals(0, h.projector.catchUp().adjustmentSkips)
        assertEquals(before, listOf(h.row(s.y1), h.row(s.y2)))
        assertTrue(h.active("S1").isEmpty())
        assertTrue(h.matchRowDump().all { it.endsWith("|7100|${h.matchUndoSeq()}") })
        assertEquals(ReceiptStatus.OPEN, h.shiftReceipts("S1").items.single().status)
        h.undo("S1", 7_200, "M1")
        assertEquals("a second undo is a counted skip", 1, h.projector.catchUp().adjustmentSkips)
        assertEquals(before, listOf(h.row(s.y1), h.row(s.y2)))

        // A "Fix this delivery" pay edit after the match: the undo is refused, the dasher's edit stays.
        val edited = harness()
        val e = edited.stack()
        edited.match("S1", 7_000, "M1", e.evidence, 2050, covered(e.y1, 1025), covered(e.y2, 1025, 900, PayBasis.OFFER_PAY))
        edited.payEdit("S1", 7_050, e.y1, 12.0)
        edited.projector.catchUp()
        val held = listOf(edited.row(e.y1), edited.row(e.y2))
        edited.undo("S1", 7_100, "M1")
        assertEquals(1, edited.projector.catchUp().adjustmentSkips)
        assertEquals(held, listOf(edited.row(e.y1), edited.row(e.y2)))
        assertEquals(PayBasis.USER_CORRECTED, edited.row(e.y1).payBasis)
        assertEquals(2, edited.active("S1").size)

        // LIFO: M1 clears Y1, M2 covers Y1; M1 cannot be undone first, then M2 and M1 both restore.
        val lifo = harness()
        val l = lifo.stack(y1Estimate = 9.0)
        lifo.observed("S1", 1_450, "J-S1", "Y1-S1", 6.00)
        lifo.projector.catchUp()
        val original = listOf(lifo.row(l.y1), lifo.row(l.y2))
        lifo.match("S1", 7_000, "M1", l.evidence, 2050, covered(l.y2, 2050, 900, PayBasis.OFFER_PAY), cleared(l.y1, 900))
        lifo.match("S1", 7_100, "M2", evidence("Y1-S1", 1_450), 600, covered(l.y1, 600))
        lifo.undo("S1", 7_200, "M1")
        assertEquals(1, lifo.projector.catchUp().adjustmentSkips)
        assertEquals(6.0, lifo.row(l.y1).realizedPay!!, 0.0)
        lifo.undo("S1", 7_300, "M2")
        lifo.undo("S1", 7_400, "M1")
        assertEquals(0, lifo.projector.catchUp().adjustmentSkips)
        assertEquals(original, listOf(lifo.row(l.y1), lifo.row(l.y2)))
    }

    private suspend fun Harness.matchUndoSeq(): Long =
        db.appEventDao().getEventsAfter(0, Int.MAX_VALUE).first { it.eventType == AppEventType.RECEIPT_MATCH_UNDONE }.sequenceId

    @Test
    fun `P10 a machine re-price never overwrites a matched delivery`() = runBlocking { // non-vacuous
        val h = harness()
        val s = h.stack()
        h.match("S1", 7_000, "M1", s.evidence, 2050, covered(s.y1, 1025), covered(s.y2, 1025, 900, PayBasis.OFFER_PAY))
        h.projector.catchUp()
        val matched = h.row(s.y2)
        h.insert(
            AppEventType.DELIVERY_RECEIPT_REPRICE, "S1", 7_100,
            DeliveryReceiptRepricePayload(
                jobId = "J-S1", taskId = "Y2-S1", totalPay = 20.50,
                parsedPay = ParsedPay(listOf(ParsedPayItem("Base Pay", 13.50)), listOf(ParsedPayItem("Store A", 7.00))),
                dropRealizedPay = 11.0,
            ),
        )
        assertEquals(1, h.projector.catchUp().adjustmentSkips)
        assertEquals(matched, h.row(s.y2))
    }

    /** P1 + P5 + P9's shapes in one log over three shifts. */
    private suspend fun Harness.mixedLog() {
        val a = stack("S1", base = 1_000)
        match("S1", 7_000, "MA", a.evidence, 2050, covered(a.y1, 1025), covered(a.y2, 1025, 900, PayBasis.OFFER_PAY))
        val b = stack("S2", base = 10_000, y1Estimate = 9.0)
        match("S2", 17_000, "MB", b.evidence, 2050, covered(b.y2, 2050, 900, PayBasis.OFFER_PAY), cleared(b.y1, 900))
        val c = stack("S3", base = 20_000, y1Estimate = 9.0)
        observed("S3", 20_450, "J-S3", "Y1-S3", 6.00)
        match("S3", 27_000, "MC1", c.evidence, 2050, covered(c.y2, 2050, 900, PayBasis.OFFER_PAY), cleared(c.y1, 900))
        match("S3", 27_100, "MC2", evidence("Y1-S3", 20_450), 600, covered(c.y1, 600))
        undo("S3", 27_200, "MC1") // refused: MC2 covers the row MC1 cleared
        undo("S3", 27_300, "MC2")
        undo("S3", 27_350, "MC1")
        match("S3", 27_400, "MC3", c.evidence, 2050, covered(c.y1, 1025), covered(c.y2, 1025, 900, PayBasis.OFFER_PAY))
    }

    @Test
    fun `P11 one event per batch, one batch and a version rebuild fold the same rows`() = runBlocking { // non-vacuous
        val incremental = harness().apply { mixedLog() }
        val skips = incremental.foldOneByOne()
        val single = harness().apply { mixedLog() }
        assertEquals(skips, single.projector.catchUp().adjustmentSkips)
        assertEquals(1, skips) // MC1's undo, refused while MC2 covers its cleared row
        assertEquals(single.allRows(), incremental.allRows())
        assertEquals(single.matchRowDump(), incremental.matchRowDump())
        assertEquals(PayBasis.RECEIPT_MATCHED, single.allRows().first { it.taskId == "Y1-S1" }.payBasis)

        incremental.db.analyticsDao().setWatermark(AnalyticsProjectionStateEntity(watermarkSequenceId = 99, projectorVersion = 1))
        incremental.projector.catchUp()
        assertEquals(single.allRows(), incremental.allRows())
        assertEquals(single.matchRowDump(), incremental.matchRowDump())
    }

    @Test
    fun `P12 a delivery logged after the match folds the same in its batch or a later one`() = runBlocking { // non-vacuous
        suspend fun Harness.log(foldBeforeLater: Boolean) {
            val s = stack(y1Estimate = 9.0)
            match("S1", 7_000, "M1", s.evidence, 2050, covered(s.y2, 2050, 900, PayBasis.OFFER_PAY), cleared(s.y1, 900))
            if (foldBeforeLater) projector.catchUp()
            insert(
                AppEventType.MANUAL_DELIVERY, "S1", 7_100,
                ManualDeliveryPayload(sessionId = "S1", storeName = "Sample Tacos", pay = 5.0, completedAt = 1_450),
            )
            // A late completion of the stack's job with its own estimate: open to the match only if the
            // apply read rows logged after it.
            drop("S1", 7_200, "J-S1", "Y3-S1", listOf("hY-S1"), 9.0)
            projector.catchUp()
        }
        val sameBatch = harness().apply { log(foldBeforeLater = false) }
        val laterBatch = harness().apply { log(foldBeforeLater = true) }
        assertEquals(laterBatch.allRows(), sameBatch.allRows())
        assertEquals(laterBatch.matchRowDump(), sameBatch.matchRowDump())
        assertEquals(PayBasis.OFFER_PAY, sameBatch.allRows().first { it.taskId == "Y3-S1" }.payBasis)
        assertEquals(2, sameBatch.matchRowDump().size)
    }

    private companion object {
        fun evidence(subject: String, at: Long) = "receipt:$subject:$at"
    }
}
