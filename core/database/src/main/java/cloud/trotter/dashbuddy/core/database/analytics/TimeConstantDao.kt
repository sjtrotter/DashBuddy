package cloud.trotter.dashbuddy.core.database.analytics

import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Lifetime evidence; group before joining so stacks cannot create fan-out or fake singletons. */
@Dao
interface TimeConstantDao {
    @Query("""
        WITH deliveries AS (
            SELECT platform, sessionId, jobId, COUNT(*) AS n
            FROM delivery_records GROUP BY platform, sessionId, jobId
        ), pickups AS (
            SELECT platform, sessionId, jobId, COUNT(*) AS n, MIN(eventSequenceId) AS soleId
            FROM pickup_records GROUP BY platform, sessionId, jobId
        ), accepted AS (
            SELECT platform, sessionId, linkedJobId, COUNT(*) AS n
            FROM offer_records WHERE outcome = :acceptedOutcome
            GROUP BY platform, sessionId, linkedJobId
        ), matches AS (
            SELECT platform, sessionId, linkedJobId, offerHash, COUNT(*) AS n, MIN(eventSequenceId) AS soleId
            FROM offer_records WHERE outcome = :acceptedOutcome
            GROUP BY platform, sessionId, linkedJobId, offerHash
        )
        SELECT d.platform, d.eventSequenceId, d.phaseStartedAt, d.arrivedAt, d.completedAt,
            d.sessionId, d.sessionAssigned, d.payBasis, d.originalPayBasis, d.realizedMinutes,
            d.milesToStore, d.milesToDropoff, d.odometerAtArrival,
            p.odometerAtConfirmation AS pickupOdometerAtConfirmation,
            d.jobOfferCount, d.soleOfferHash, o.orderCount, o.isShop,
            o.outcomeResolved AS offerOutcomeResolved,
            dc.n AS deliveryCount, COALESCE(pc.n, 0) AS pickupCount,
            COALESCE(ac.n, 0) AS acceptedOfferCount, COALESCE(mc.n, 0) AS matchingOfferCount,
            o.decidedAt AS offerDecidedAt, p.phaseStartedAt AS pickupPhaseStartedAt,
            p.arrivedAt AS pickupArrivedAt, p.confirmedAt AS pickupConfirmedAt,
            p.activity AS pickupActivity
        FROM delivery_records d
        JOIN deliveries dc ON dc.platform = d.platform AND dc.sessionId IS d.sessionId AND dc.jobId = d.jobId
        LEFT JOIN pickups pc ON pc.platform = d.platform AND pc.sessionId = d.sessionId AND pc.jobId = d.jobId
        LEFT JOIN pickup_records p ON pc.n = 1 AND p.eventSequenceId = pc.soleId
        LEFT JOIN accepted ac ON ac.platform = d.platform AND ac.sessionId = d.sessionId AND ac.linkedJobId = d.jobId
        LEFT JOIN matches mc ON mc.platform = d.platform AND mc.sessionId = d.sessionId
            AND mc.linkedJobId = d.jobId AND mc.offerHash = d.soleOfferHash
        LEFT JOIN offer_records o ON mc.n = 1 AND o.eventSequenceId = mc.soleId
        ORDER BY d.eventSequenceId DESC
    """)
    fun observations(acceptedOutcome: String): Flow<List<TimeConstantRow>>
}
