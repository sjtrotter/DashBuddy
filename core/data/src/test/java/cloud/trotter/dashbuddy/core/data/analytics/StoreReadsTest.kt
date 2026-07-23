package cloud.trotter.dashbuddy.core.data.analytics

import androidx.room.Room
import cloud.trotter.dashbuddy.core.database.DashBuddyDatabase
import cloud.trotter.dashbuddy.core.database.analytics.AnalyticsDao
import cloud.trotter.dashbuddy.core.database.analytics.DeliveryRecordEntity
import cloud.trotter.dashbuddy.core.database.analytics.PickupRecordEntity
import cloud.trotter.dashbuddy.core.database.analytics.StoreEntity
import cloud.trotter.dashbuddy.domain.analytics.AnalyticsPeriod
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * #159 read-side guards for the store report card + F9 grouping (the #315 Patterns tab consumer):
 * unresolved rows fold by `normalizedChain(storeName)` not raw text (F9), dwell percentiles are
 * computed in the repository, and a chain-only store surfaces as "location unknown" (F6).
 */
@RunWith(RobolectricTestRunner::class)
class StoreReadsTest {

    private lateinit var db: DashBuddyDatabase
    private lateinit var dao: AnalyticsDao
    private lateinit var repo: AnalyticsRepository

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, DashBuddyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.analyticsDao()
        repo = AnalyticsRepository(dao, db.appEventDao())
    }

    @After
    fun tearDown() = db.close()

    private fun deliveryRow(
        seq: Long,
        storeName: String?,
        storeKey: String?,
        pay: Double,
        net: Double,
        completedAt: Long = 1_000L,
        cashTip: Double? = null,
    ) = DeliveryRecordEntity(
        eventSequenceId = seq,
        sessionId = null, // null-session rows fold by their own completedAt (in-window for LIFETIME)
        platform = "doordash",
        jobId = "J$seq",
        taskId = "T$seq",
        storeName = storeName,
        customerHash = null,
        addressHash = null,
        phaseStartedAt = completedAt - 1000,
        arrivedAt = completedAt - 500,
        completedAt = completedAt,
        deadlineMillis = null,
        realizedPay = pay,
        payBasis = "RECEIPT_TOTAL",
        tip = null,
        basePay = null,
        odometerAtCompletion = null,
        realizedMiles = 1.0,
        realizedMinutes = 10.0,
        frozenCostPerMile = 0.25,
        netProfit = net,
        costBasis = "OFFER_FROZEN",
        cashTip = cashTip,
        storeKey = storeKey,
    )

    private fun pickupRow(seq: Long, storeKey: String?, arrivedAt: Long?, confirmedAt: Long?) =
        PickupRecordEntity(
            eventSequenceId = seq,
            sessionId = "S1",
            platform = "doordash",
            jobId = "J$seq",
            taskId = "PT$seq",
            storeName = "Target",
            storeKey = storeKey,
            phaseStartedAt = (arrivedAt ?: 0) - 100,
            arrivedAt = arrivedAt,
            confirmedAt = confirmedAt,
            deadlineMillis = null,
            activity = "PICKUP",
        )

    private fun storeRow(storeKey: String, chain: String, runningKey: String?) = StoreEntity(
        storeKey = storeKey, platform = "doordash", normalizedChain = chain, chainDisplay = "Target",
        runningKey = runningKey, offerNameForm = null, pickupNameForm = "Target", payoutNameForm = null,
        address = null,
    )

    @Test
    fun `F9 — unresolved rows fold by normalizedChain, not raw text`() = runBlocking {
        // Two unresolved (storeKey null) rows differing only in casing/whitespace → ONE chain bucket.
        dao.upsertDelivery(deliveryRow(1, "Target", null, pay = 10.0, net = 8.0))
        dao.upsertDelivery(deliveryRow(2, "  target ", null, pay = 5.0, net = 4.0))

        val stores = repo.perStoreEconomics(AnalyticsPeriod.LIFETIME).first()
        assertEquals("casing/whitespace variants fold into one bucket (F9)", 1, stores.size)
        assertEquals(15.0, stores.single().gross, 0.001)
        assertEquals(12.0, stores.single().net, 0.001)
    }

    @Test
    fun `F9 — a resolved keyed store and its unresolved chain form MERGE into one chain bucket (FIX 8)`() = runBlocking {
        dao.upsertDelivery(deliveryRow(1, "Target (02426)", "doordash|target|02426", pay = 10.0, net = 8.0))
        dao.upsertDelivery(deliveryRow(2, "Target", null, pay = 5.0, net = 4.0))

        val stores = repo.perStoreEconomics(AnalyticsPeriod.LIFETIME).first()
        assertEquals("keyed + unresolved fold to ONE chain bucket (F9/FIX 8)", 1, stores.size)
        assertEquals("doordash|target", stores.single().storeKey)
        assertEquals(15.0, stores.single().gross, 0.001)
        assertEquals(12.0, stores.single().net, 0.001)
    }

    @Test
    fun `F9 — two keyed locations of one chain roll up to a single chain bucket (FIX 8)`() = runBlocking {
        dao.upsertDelivery(deliveryRow(1, "Target (02426)", "doordash|target|02426", pay = 10.0, net = 8.0))
        dao.upsertDelivery(deliveryRow(2, "Target (99999)", "doordash|target|99999", pay = 6.0, net = 5.0))
        // A chainDisplay for the chain feeds the bucket's display name.
        dao.upsertStore(storeRow("doordash|target|02426", "target", "02426"))

        val stores = repo.perStoreEconomics(AnalyticsPeriod.LIFETIME).first()
        assertEquals(1, stores.size)
        assertEquals("doordash|target", stores.single().storeKey)
        assertEquals("Target", stores.single().storeName) // chainDisplay preferred
        assertEquals(16.0, stores.single().gross, 0.001)
    }

    @Test
    fun `FIX 4 — the report card gross and net include cash tips`() = runBlocking {
        val key = "doordash|target|02426"
        dao.upsertStore(storeRow(key, "target", "02426"))
        dao.upsertPickup(pickupRow(1, key, arrivedAt = 0, confirmedAt = 10_000))
        dao.upsertDelivery(deliveryRow(100, "Target (02426)", key, pay = 12.0, net = 9.0, cashTip = 3.0))

        val card = repo.storeReportCards().first().single()
        assertEquals("gross includes cash (#688)", 15.0, card.gross, 0.001)
        assertEquals("net includes cash (#688)", 12.0, card.net, 0.001)
    }

    @Test
    fun `FIX 11 — a negative-dwell pickup row is excluded from the dwell samples`() = runBlocking {
        val key = "doordash|target|02426"
        dao.upsertStore(storeRow(key, "target", "02426"))
        // One valid dwell (20s) and one inverted row (confirmed BEFORE arrived, #732 class).
        dao.upsertPickup(pickupRow(1, key, arrivedAt = 0, confirmedAt = 20_000))
        dao.upsertPickup(pickupRow(2, key, arrivedAt = 50_000, confirmedAt = 10_000))
        dao.upsertDelivery(deliveryRow(100, "Target (02426)", key, pay = 12.0, net = 9.0))

        val card = repo.storeReportCards().first().single()
        // Only the valid 20s sample survives — the inverted row is filtered out (no negative dwell).
        assertEquals(20_000.0, card.avgDwellMillis!!, 0.001)
        assertEquals(20_000L, card.p50DwellMillis)
    }

    @Test
    fun `report card computes dwell percentiles and surfaces a keyed location`() = runBlocking {
        val key = "doordash|target|02426"
        dao.upsertStore(storeRow(key, "target", "02426"))
        // dwells: 10s, 20s, 30s, 40s, 100s (ms).
        val dwells = listOf(10_000L, 20_000L, 30_000L, 40_000L, 100_000L)
        dwells.forEachIndexed { i, d -> dao.upsertPickup(pickupRow(i + 1L, key, arrivedAt = 0, confirmedAt = d)) }
        dao.upsertDelivery(deliveryRow(100, "Target (02426)", key, pay = 12.0, net = 9.0))

        val card = repo.storeReportCards().first().single()
        assertEquals(key, card.storeKey)
        assertTrue("keyed location known (F6)", card.locationKnown)
        assertEquals(5, card.pickups)
        assertEquals(1, card.deliveries)
        assertEquals(40_000.0, card.avgDwellMillis!!, 0.001)
        // nearest-rank: p50 of 5 samples = index ceil(0.5*5)=3 → 30_000; p95 = ceil(0.95*5)=5 → 100_000.
        assertEquals(30_000L, card.p50DwellMillis)
        assertEquals(100_000L, card.p95DwellMillis)
    }

    @Test
    fun `F6 — a chain-only store surfaces as location unknown`() = runBlocking {
        val chainOnly = "doordash|chipotle|"
        dao.upsertStore(
            StoreEntity(
                storeKey = chainOnly, platform = "doordash", normalizedChain = "chipotle",
                chainDisplay = "Chipotle", runningKey = null, offerNameForm = null,
                pickupNameForm = "Chipotle", payoutNameForm = null, address = null,
            ),
        )
        dao.upsertPickup(pickupRow(1, chainOnly, arrivedAt = 0, confirmedAt = 15_000))

        val card = repo.storeReportCards().first().single()
        assertEquals(chainOnly, card.storeKey)
        assertFalse("no running key ⇒ location unknown (F6)", card.locationKnown)
    }

    @Test
    fun `M4 — an unreferenced store row shows no phantom report entry`() = runBlocking {
        // A chain-only provisional key that was upgraded away (no visit references it) must not appear.
        dao.upsertStore(storeRow("doordash|orphan|", "orphan", null))
        val cards = repo.storeReportCards().first()
        assertTrue("orphan store with zero visits is absent (M4)", cards.none { it.storeKey == "doordash|orphan|" })
    }
}
