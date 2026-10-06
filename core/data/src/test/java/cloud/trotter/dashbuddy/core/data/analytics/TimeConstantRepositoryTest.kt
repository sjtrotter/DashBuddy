package cloud.trotter.dashbuddy.core.data.analytics

import androidx.room.Room
import cloud.trotter.dashbuddy.core.database.DashBuddyDatabase
import cloud.trotter.dashbuddy.domain.analytics.PayBasis
import cloud.trotter.dashbuddy.domain.evaluation.TimeConstantPair
import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import cloud.trotter.dashbuddy.domain.state.Platform
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class TimeConstantRepositoryTest {
    private lateinit var db: DashBuddyDatabase
    private val platform = Platform.entries.first { it != Platform.Unknown }
    private val accepted = AppEventType.OFFER_ACCEPTED.name
    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), DashBuddyDatabase::class.java)
            .allowMainThreadQueries().build()
    }
    @After fun tearDown() = db.close()

    private fun seed(id: Long = 1, wire: String = platform.wire, session: String = "session") {
        val sql = db.openHelper.writableDatabase
        sql.execSQL("""INSERT INTO delivery_records
            (eventSequenceId, platform, sessionId, jobId, taskId, phaseStartedAt, arrivedAt, completedAt,
             payBasis, costBasis, realizedMinutes, realizedMiles, milesToStore, milesToDropoff,
             jobOfferCount, soleOfferHash, odometerAtArrival)
            VALUES (?, ?, ?, 'job', 'drop', 540000, 1020000, 1200000, ?, 'NONE', 20, 6, 2, 4, 1, 'offer', 106)""",
            arrayOf<Any>(id, wire, session, PayBasis.NONE))
        sql.execSQL("""INSERT INTO pickup_records
            (eventSequenceId, platform, sessionId, jobId, taskId, storeName, phaseStartedAt, arrivedAt, confirmedAt, odometerAtConfirmation)
            VALUES (?, ?, ?, 'job', 'pick', 'Store', 120000, 240000, 540000, 102)""", arrayOf<Any>(id, wire, session))
        sql.execSQL("""INSERT INTO offer_records
            (eventSequenceId, platform, sessionId, offerHash, outcome, presentedAt, decidedAt, itemCount,
             linkedJobId, orderCount, orderCountProven, isShop)
            VALUES (?, ?, ?, 'offer', ?, 1000, 60000, 1, 'job', 1, 1, 0)""", arrayOf<Any>(id, wire, session, accepted))
    }
    private suspend fun learned() = TimeConstantRepository(db.timeConstantDao()).learnedTimeConstants.first()
    private suspend fun rows() = db.timeConstantDao().observations(accepted).first()

    @Test fun `Room maps nullable evidence and wire platforms without mixing session or platform jobs`() = runBlocking {
        seed()
        seed(2, Platform.entries.first { it != platform && it != Platform.Unknown }.wire)
        seed(3, session = "other-session")
        seed(4, wire = "unregistered")
        val observations = rows()
        assertEquals(listOf(4L, 3L, 2L, 1L), observations.map { it.eventSequenceId })
        assertTrue(observations.all { it.deliveryCount == 1 && it.pickupCount == 1 && it.acceptedOfferCount == 1 && it.matchingOfferCount == 1 })
        assertEquals(2, learned().getValue(platform).sampleCount)
        assertEquals(2, learned().size)
        assertEquals(TimeConstantPair(2.0, 8.0), learned().getValue(platform).median)
        val d = db.analyticsDao().deliveryRecord(1)!!
        db.analyticsDao().upsertDelivery(d.copy(jobOfferCount = null, soleOfferHash = null, odometerAtArrival = null))
        val row = rows().last()
        assertNull(row.jobOfferCount)
        assertNull(row.odometerAtArrival)
        assertNull(row.orderCount)
        assertNull(row.orderCountProven)
        assertEquals(0, row.matchingOfferCount)
        assertEquals(1, learned().getValue(platform).sampleCount)
    }

    @Test fun `late children invalidate samples and never multiply result rows`() = runBlocking {
        seed()
        assertEquals(1, learned().getValue(platform).sampleCount)
        val dao = db.analyticsDao()
        val d = dao.deliveryRecord(1)!!
        val p = dao.pickupRecordsForJob("job").single()
        val o = dao.offerRecord(1)!!
        dao.upsertPickup(p.copy(eventSequenceId = 2, taskId = "second-pickup"))
        assertEquals(1, rows().size)
        assertEquals(2, rows().single().pickupCount)
        assertNull(rows().single().pickupConfirmedAt)
        assertTrue(learned().isEmpty())
        dao.upsertDelivery(d.copy(eventSequenceId = 2, taskId = "second-drop"))
        dao.upsertOffer(o.copy(eventSequenceId = 2, offerHash = "addon"))
        assertEquals(2, rows().size)
        assertTrue(rows().all { it.deliveryCount == 2 && it.pickupCount == 2 && it.acceptedOfferCount == 2 && it.matchingOfferCount == 1 })
        dao.upsertOffer(o.copy(eventSequenceId = 3))
        assertEquals(2, rows().size)
        assertTrue(rows().all { it.matchingOfferCount == 2 && it.orderCount == null })
    }

    @Test fun `exact offer needs same platform session hash link and unresolved outcome`() = runBlocking {
        seed()
        val dao = db.analyticsDao()
        val offer = dao.offerRecord(1)!!
        val variants = listOf(offer.copy(sessionId = "elsewhere"), offer.copy(platform = Platform.Unknown.wire),
            offer.copy(offerHash = "wrong"), offer.copy(linkedJobId = "wrong"),
            offer.copy(outcome = AppEventType.OFFER_DECLINED.name),
            offer.copy(outcomeResolved = "UNASSIGNED_ATTESTED"), offer.copy(orderCount = 2),
            offer.copy(orderCountProven = null), offer.copy(orderCountProven = false),
            offer.copy(isShop = null), offer.copy(isShop = true))
        variants.forEach { bad ->
            dao.upsertOffer(bad)
            assertTrue("$bad", learned().isEmpty())
        }
        dao.upsertOffer(offer)
        assertEquals(1, learned().getValue(platform).sampleCount)
        dao.upsertOffer(offer.copy(eventSequenceId = 2, offerHash = "unclassified-addon", orderCount = null, isShop = null))
        assertEquals(2, rows().single().acceptedOfferCount)
        assertTrue(learned().isEmpty())
    }

    @Test fun `corrected pay preserves eligibility while edited miles never replace machine legs`() = runBlocking {
        seed()
        val dao = db.analyticsDao()
        val d = dao.deliveryRecord(1)!!
        dao.upsertDelivery(d.copy(payBasis = PayBasis.USER_CORRECTED, originalPayBasis = PayBasis.NONE,
            realizedPay = 50.0, realizedMiles = 999.0))
        assertEquals(TimeConstantPair(2.0, 8.0), learned().getValue(platform).median)
        dao.upsertDelivery(d.copy(milesToStore = null, realizedMiles = 6.0))
        assertTrue(learned().isEmpty())
        dao.upsertDelivery(d.copy(milesToDropoff = null, realizedMiles = 6.0))
        assertTrue(learned().isEmpty())
        dao.upsertDelivery(d.copy(milesToDropoff = 6.0))
        assertTrue(learned().isEmpty())
        dao.upsertDelivery(d.copy(payBasis = PayBasis.USER_CORRECTED, originalPayBasis = PayBasis.MANUAL))
        assertTrue(learned().isEmpty())
    }
}
