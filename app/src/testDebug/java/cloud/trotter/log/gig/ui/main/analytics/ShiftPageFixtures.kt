package cloud.trotter.log.gig.ui.main.analytics

import cloud.trotter.log.gig.domain.analytics.DeliveryRecord
import cloud.trotter.log.gig.domain.analytics.OfferOutcome
import cloud.trotter.log.gig.domain.analytics.PayBasis
import cloud.trotter.log.gig.domain.analytics.SessionDetail
import cloud.trotter.log.gig.domain.analytics.SessionRecord
import cloud.trotter.log.gig.domain.analytics.checks.ShiftChecks
import cloud.trotter.log.gig.domain.analytics.receipts.ActiveMatchRow
import cloud.trotter.log.gig.domain.analytics.receipts.SavedReceipt
import cloud.trotter.log.gig.domain.analytics.receipts.ShiftReceipts
import cloud.trotter.log.gig.domain.analytics.timeline.ShiftTimeline
import cloud.trotter.log.gig.domain.analytics.timeline.TimelineDelivery
import cloud.trotter.log.gig.domain.analytics.timeline.TimelineOffer
import cloud.trotter.log.gig.domain.analytics.timeline.TimelinePickup
import cloud.trotter.log.gig.domain.model.event.payload.OfferOutcomeResolution
import cloud.trotter.log.gig.domain.model.event.payload.ReceiptMatchRole
import cloud.trotter.log.gig.domain.model.event.payload.SessionEndSource
import cloud.trotter.log.gig.domain.model.offer.OfferQuoteBasis
import cloud.trotter.log.gig.domain.model.offer.OfferScope
import cloud.trotter.log.gig.domain.state.Platform
import java.time.Instant

/**
 * Shift pages for the Compose tests and the screenshots, folded through the real [ShiftTimeline]. Times are UTC
 * on Tuesday 2026-10-27, the day before the screenshots' clock. Store names are sample merchants.
 */
internal object ShiftPageFixtures {
    private val start = Instant.parse("2026-10-27T16:58:00Z").toEpochMilli()
    private fun at(minutes: Int) = start + minutes * 60_000L

    /** Sequence ids of the stack's two delivery rows, so a test can name the delivery a Fix opens. */
    const val STACK_FIRST_DROP = 101L
    const val STACK_SECOND_DROP = 102L
    const val STACK_KEY = "offer:13"

    private fun offer(seq: Long, minutes: Int, outcome: OfferOutcome, store: String, pay: Double, miles: Double,
                      scope: OfferScope? = OfferScope.STANDALONE, basis: OfferQuoteBasis? = OfferQuoteBasis.TOTAL) =
        TimelineOffer(seq, "h$seq", outcome, at(minutes), pay, miles, store, scope, basis, linkedJobId = null)

    /** The orphan shift's accepted offer with no delivery, its run before it, and when it was accepted. */
    const val ORPHAN_OFFER = 13L
    const val ORPHAN_KEY = "offer:13"
    const val ORPHAN_RUN_KEY = "run:12"
    val orphanAcceptedAt: Long get() = at(97)
    val orphanUnassignedAt: Long get() = at(104)

    /** What became of the orphan shift's accepted offer with no delivery. */
    enum class OrphanAnswer { OPEN, UNASSIGNED_RECORDED, MARKED_UNASSIGNED, MISSED_DELIVERY_ADDED }

    private fun record(seq: Long, job: String, store: String, minutes: Int, pay: Double?, basis: String,
                       net: Double?, cash: Double? = null, hashes: List<String> = emptyList(), adjustedAt: Long? = null) = DeliveryRecord(
        eventSequenceId = seq, sessionId = "shift-1", platform = Platform.DoorDash, jobId = job, taskId = "t$seq",
        storeName = store, completedAt = at(minutes), realizedPay = pay, payBasis = basis, netProfit = net,
        realizedMiles = 3.0, realizedMinutes = 12.0, tip = null, basePay = null, cashTip = cash,
        driverAdjustedAt = adjustedAt, jobOfferHashes = hashes,
    )

    private fun session(endedMinutes: Int?, reported: Double?, endSource: String?, miles: Double, deliveries: Int) =
        SessionRecord(
            sessionId = "shift-1", platform = Platform.DoorDash, startedAt = start, endedAt = endedMinutes?.let(::at),
            reportedEarnings = reported, endSource = endSource, reportedDurationMillis = null, miles = miles,
            deliveries = deliveries, jobsCompleted = 2, offersReceived = 9, offersAccepted = 3, offersDeclined = 5,
            offersTimeout = 1,
        )

    /**
     * A stack (Five Guys and Chipotle, two drops, cash on the second), an add-on job, and runs of passed offers.
     * [store] renames every merchant ([sampleStackAndAddOn]).
     */
    fun stackAndAddOn(running: Boolean = false, store: (String) -> String = { it }): SessionDetailUiState {
        val offers = listOf(
            offer(10, 7, OfferOutcome.DECLINED, store("Chick-fil-A"), 6.50, 4.1),
            offer(11, 14, OfferOutcome.DECLINED, store("Panda Express"), 4.00, 6.0),
            offer(12, 22, OfferOutcome.TIMED_OUT, store("Wingstop"), 3.50, 5.2),
            offer(13, 32, OfferOutcome.ACCEPTED, store("Five Guys & Chipotle"), 9.75, 3.1),
            offer(14, 97, OfferOutcome.ACCEPTED, store("Sample Burgers"), 6.25, 2.4),
            offer(20, 104, OfferOutcome.ACCEPTED, store("Sample Tacos"), 4.00, 1.8, OfferScope.ADD_ON, OfferQuoteBasis.INCREMENTAL),
            offer(30, 150, OfferOutcome.DECLINED, store("Sample Pizza"), 5.00, 7.3),
        )
        val records = listOf(
            record(STACK_FIRST_DROP, "job-1", store("Five Guys"), 72, 6.25, PayBasis.DROP_SHARE, 3.10),
            record(STACK_SECOND_DROP, "job-1", store("Chipotle"), 84, 5.50, PayBasis.DROP_SHARE, 2.40, cash = 5.0),
            record(103, "job-2", store("Sample Burgers"), 128, 7.00, PayBasis.DROP_SHARE, 3.80),
            record(104, "job-2", store("Sample Tacos"), 136, 4.00, PayBasis.OFFER_PAY, 1.90),
        )
        val pickups = listOf(
            TimelinePickup(201, "job-1", store("Five Guys"), at(42), at(48), listOf("h13")),
            TimelinePickup(202, "job-1", store("Chipotle"), at(52), at(57), listOf("h13")),
        )
        val deliveries = records.map { r ->
            TimelineDelivery(
                r.eventSequenceId, r.jobId, r.storeName, arrivedAt = r.completedAt - 120_000L, completedAt = r.completedAt,
                realizedPay = r.realizedPay, payBasis = r.payBasis, cashTip = r.cashTip,
                jobOfferHashes = if (r.jobId == "job-1") listOf("h13") else listOf("h14", "h20"), soleOfferHash = null,
            )
        }
        val detail = SessionDetail(
            session(if (running) null else 366, 25.50, SessionEndSource.SUMMARY_SCREEN, 41.0, records.size), records,
        )
        return state(detail, offers, pickups, deliveries, running)
    }

    /** The stack's merchants under made-up names: the Play listing's shift page shows no real store. */
    val SAMPLE_STORES = mapOf(
        "Chick-fil-A" to "Sample Grocery",
        "Panda Express" to "Noodle House",
        "Wingstop" to "Sample Pharmacy",
        "Five Guys & Chipotle" to "Burger Stand & Corner Cafe",
        "Five Guys" to "Burger Stand",
        "Chipotle" to "Corner Cafe",
    )

    /** [stackAndAddOn] with [SAMPLE_STORES]; every other merchant in it is already a "Sample …" one. */
    fun sampleStackAndAddOn(): SessionDetailUiState = stackAndAddOn { SAMPLE_STORES[it] ?: it }

    /**
     * The heaviest field shape: 47 offers and no deliveries. Two accepted offers that never delivered split the
     * passed ones into three runs of declined and expired offers.
     */
    fun heavyShift(): SessionDetailUiState {
        val offers = (0 until 47).map { i ->
            val outcome = when {
                i == 15 || i == 31 -> OfferOutcome.ACCEPTED
                i % 4 == 3 -> OfferOutcome.TIMED_OUT
                else -> OfferOutcome.DECLINED
            }
            offer(1_000L + i, 3 + i * 7, outcome, "Sample Store ${i % 6 + 1}", 3.0 + (i % 5), 2.0 + (i % 7))
        }
        val detail = SessionDetail(session(366, null, SessionEndSource.SUMMARY_SCREEN, 58.0, 0), emptyList())
        return state(detail, offers, emptyList(), emptyList(), running = false)
    }

    /**
     * An ended shift with one delivered job and, last, an accepted offer with no delivery ([ORPHAN_OFFER], Sample
     * Tacos, $7.50), as [answer] left it. The app's total is $13.75 against $6.25 of delivery pay until a missed
     * delivery is added.
     */
    fun orphanShift(answer: OrphanAnswer = OrphanAnswer.OPEN): SessionDetailUiState {
        val orphan = offer(ORPHAN_OFFER, 97, OfferOutcome.ACCEPTED, "Sample Tacos", 7.50, 2.6).let {
            when (answer) {
                OrphanAnswer.UNASSIGNED_RECORDED -> it.copy(unassignedAt = orphanUnassignedAt)
                OrphanAnswer.MARKED_UNASSIGNED -> it.copy(outcomeResolved = OfferOutcomeResolution.UNASSIGNED_ATTESTED)
                else -> it
            }
        }
        val offers = listOf(
            offer(10, 7, OfferOutcome.DECLINED, "Chick-fil-A", 6.50, 4.1),
            offer(11, 20, OfferOutcome.ACCEPTED, "Sample Burgers", 6.25, 2.4),
            offer(12, 60, OfferOutcome.DECLINED, "Sample Pizza", 5.00, 7.3),
            orphan,
        )
        val records = buildList {
            add(record(101, "job-1", "Sample Burgers", 45, 6.25, PayBasis.DROP_SHARE, 3.10))
            if (answer == OrphanAnswer.MISSED_DELIVERY_ADDED) {
                add(record(102, "manual:102", "Sample Tacos", 97, 7.50, PayBasis.MANUAL, 5.90))
            }
        }
        val deliveries = records.map { r ->
            TimelineDelivery(
                r.eventSequenceId, r.jobId, r.storeName, arrivedAt = null, completedAt = r.completedAt,
                realizedPay = r.realizedPay, payBasis = r.payBasis, cashTip = r.cashTip,
                jobOfferHashes = if (r.jobId == "job-1") listOf("h11") else null, soleOfferHash = null,
                sourceOfferSeq = if (r.payBasis == PayBasis.MANUAL) ORPHAN_OFFER else null,
            )
        }
        val detail = SessionDetail(session(366, 13.75, SessionEndSource.SUMMARY_SCREEN, 22.0, records.size), records)
        return state(detail, offers, emptyList(), deliveries, running = false)
    }

    /** #1599: the receipt shift's one-offer stack, its proven delivery, its unproven receipt and its match. */
    const val RECEIPT_Y1 = 101L
    const val RECEIPT_Y2 = 102L
    const val RECEIPT_PROVEN = 103L
    const val RECEIPT_STACK_KEY = "offer:40"
    const val RECEIPT_EVIDENCE = "receipt:t102:shift-1"
    const val RECEIPT_MATCH_COMMAND = "match-1"
    val receiptSeenAt: Long get() = at(74)
    const val RECEIPT_OTHER_EVIDENCE = "receipt:t105:shift-1"

    /**
     * An ended shift (#1599): a proven single delivery (Store C, Receipt $28.51), a one-offer stack at Store A ([RECEIPT_Y1]
     * with no pay, [RECEIPT_Y2] an offer estimate of $9.00), a two-offer job at Store D (two $7.00 estimates), an
     * unproven $20.50 receipt read after [RECEIPT_Y2], and the app's total. [matched] adds the dasher's match of the
     * receipt over the stack, even split; [editedAfter] then a "Fix this delivery" pay edit on [RECEIPT_Y1], so the
     * match can no longer be undone. [y2Only] instead matches the receipt to [RECEIPT_Y2] alone, which leaves
     * [RECEIPT_Y1] cleared, with no pay; [otherOpen] adds an unproven $7.50 receipt of the Store D job, still open.
     */
    fun receiptShift(
        matched: Boolean = false,
        editedAfter: Boolean = false,
        y2Only: Boolean = false,
        otherOpen: Boolean = false,
    ): SessionDetailUiState {
        val matchedAt = at(400)
        val offers = listOf(
            offer(41, 5, OfferOutcome.ACCEPTED, "Store C", 9.00, 2.0),
            offer(40, 30, OfferOutcome.ACCEPTED, "Store A", 18.00, 5.0),
            offer(42, 110, OfferOutcome.ACCEPTED, "Store D", 10.00, 3.0),
            offer(43, 115, OfferOutcome.ACCEPTED, "Store D", 4.00, 1.0, OfferScope.ADD_ON, OfferQuoteBasis.INCREMENTAL),
        )
        val y1 = when {
            y2Only -> record(RECEIPT_Y1, "job-y", "Store A", 40, null, PayBasis.NONE, -1.0, hashes = listOf("h40"))
            matched && editedAfter -> record(RECEIPT_Y1, "job-y", "Store A", 40, 12.00, PayBasis.USER_CORRECTED, 11.0, hashes = listOf("h40"), adjustedAt = at(410))
            matched -> record(RECEIPT_Y1, "job-y", "Store A", 40, 10.25, PayBasis.RECEIPT_MATCHED, 9.25, hashes = listOf("h40"), adjustedAt = matchedAt)
            else -> record(RECEIPT_Y1, "job-y", "Store A", 40, null, PayBasis.NONE, -1.0, hashes = listOf("h40"))
        }
        val y2 = if (y2Only) {
            record(RECEIPT_Y2, "job-y", "Store A", 70, 20.50, PayBasis.RECEIPT_MATCHED, 19.5, hashes = listOf("h40"), adjustedAt = matchedAt)
        } else if (matched) {
            record(RECEIPT_Y2, "job-y", "Store A", 70, 10.25, PayBasis.RECEIPT_MATCHED, 9.25, hashes = listOf("h40"), adjustedAt = matchedAt)
        } else {
            record(RECEIPT_Y2, "job-y", "Store A", 70, 9.00, PayBasis.OFFER_PAY, 8.0, hashes = listOf("h40"))
        }
        val records = listOf(
            record(RECEIPT_PROVEN, "job-c", "Store C", 15, 28.51, PayBasis.RECEIPT_TOTAL, 27.0, hashes = listOf("h41")),
            y1,
            y2,
            record(105, "job-d", "Store D", 125, 7.00, PayBasis.OFFER_PAY, 6.0, hashes = listOf("h42", "h43")),
            record(106, "job-d", "Store D", 135, 7.00, PayBasis.OFFER_PAY, 6.0, hashes = listOf("h42", "h43")),
        )
        val deliveries = records.map { r ->
            TimelineDelivery(
                r.eventSequenceId, r.jobId, r.storeName, arrivedAt = r.completedAt - 120_000L, completedAt = r.completedAt,
                realizedPay = r.realizedPay, payBasis = r.payBasis, cashTip = r.cashTip, jobOfferHashes = r.jobOfferHashes,
                soleOfferHash = null,
            )
        }
        val receipt = SavedReceipt(RECEIPT_EVIDENCE, "job-y", "t$RECEIPT_Y2", receiptSeenAt, 2050, null, null, proven = false)
        val active = if (y2Only) {
            listOf(
                ActiveMatchRow(RECEIPT_MATCH_COMMAND, RECEIPT_EVIDENCE, RECEIPT_Y1, ReceiptMatchRole.CLEARED, null, matchedAt),
                ActiveMatchRow(RECEIPT_MATCH_COMMAND, RECEIPT_EVIDENCE, RECEIPT_Y2, ReceiptMatchRole.COVERED, 2050, matchedAt),
            )
        } else if (matched) {
            listOf(RECEIPT_Y1, RECEIPT_Y2).map { ActiveMatchRow(RECEIPT_MATCH_COMMAND, RECEIPT_EVIDENCE, it, ReceiptMatchRole.COVERED, 1025, matchedAt) }
        } else {
            emptyList()
        }
        val detail = SessionDetail(session(366, 63.01, SessionEndSource.SUMMARY_SCREEN, 30.0, records.size), records)
        val other = SavedReceipt(RECEIPT_OTHER_EVIDENCE, "job-d", "t105", at(127), 750, null, null, proven = false)
        val saved = if (otherOpen) listOf(receipt, other) else listOf(receipt)
        return state(detail, offers, emptyList(), deliveries, running = false, ShiftReceipts.of(saved, records, active))
    }

    /** The page's state as the ViewModel builds it: the fold, and the offers and deliveries the store search reads. */
    private fun state(
        detail: SessionDetail,
        offers: List<TimelineOffer>,
        pickups: List<TimelinePickup>,
        deliveries: List<TimelineDelivery>,
        running: Boolean,
        receipts: ShiftReceipts = ShiftReceipts.EMPTY,
    ): SessionDetailUiState {
        val timeline = ShiftTimeline.fold(offers, pickups, deliveries, running)
        return SessionDetailUiState(
            detail = detail, timeline = timeline, loading = false, checks = ShiftChecks.of(detail, timeline, receipts),
            offers = offers, deliveries = deliveries, receipts = receipts,
        )
    }
}
