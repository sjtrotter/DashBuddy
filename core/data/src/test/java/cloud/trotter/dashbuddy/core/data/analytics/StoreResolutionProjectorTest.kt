package cloud.trotter.dashbuddy.core.data.analytics

import androidx.room.Room
import cloud.trotter.dashbuddy.core.data.event.AppEventRepo
import cloud.trotter.dashbuddy.core.data.settings.AppPreferencesRepository
import cloud.trotter.dashbuddy.core.database.DashBuddyDatabase
import cloud.trotter.dashbuddy.core.database.analytics.AnalyticsDao
import cloud.trotter.dashbuddy.core.database.analytics.AnalyticsProjectionStateEntity
import cloud.trotter.dashbuddy.core.database.analytics.PickupRecordEntity
import cloud.trotter.dashbuddy.core.database.analytics.StoreEntity
import cloud.trotter.dashbuddy.core.database.event.AppEventDao
import cloud.trotter.dashbuddy.core.database.event.AppEventEntity
import cloud.trotter.dashbuddy.domain.evaluation.OfferAction
import cloud.trotter.dashbuddy.domain.evaluation.OfferEvaluation
import cloud.trotter.dashbuddy.domain.evaluation.OfferQuality
import cloud.trotter.dashbuddy.domain.evaluation.UserEconomy
import cloud.trotter.dashbuddy.domain.model.event.AppEventCodec
import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import cloud.trotter.dashbuddy.domain.model.event.payload.AppEventPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.DeliveryAdjustmentPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.DeliveryPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.OfferPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.PickupPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.SessionEndSource
import cloud.trotter.dashbuddy.domain.model.event.payload.SessionStartPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.SessionStartSource
import cloud.trotter.dashbuddy.domain.model.event.payload.SessionStopPayload
import cloud.trotter.dashbuddy.domain.model.offer.ParsedOffer
import cloud.trotter.dashbuddy.domain.model.pay.ParsedPay
import cloud.trotter.dashbuddy.domain.model.pay.ParsedPayItem
import cloud.trotter.dashbuddy.domain.state.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * #159 — the store-resolution edges of [AnalyticsProjector] against an in-memory Room DB: fresh
 * resolution, the B1 no-downgrade + re-run-no-op guards, the B2 multi-store stack, the F1 batch-
 * boundary incremental ≡ refold guard, the H1 correction pin, and the F8 version-wipe. The pure
 * resolver/normalizer are covered in `:domain`.
 */
@RunWith(RobolectricTestRunner::class)
class StoreResolutionProjectorTest {

    private lateinit var db: DashBuddyDatabase
    private lateinit var dao: AnalyticsDao
    private lateinit var eventDao: AppEventDao
    private lateinit var repo: AppEventRepo
    private lateinit var prefs: AppPreferencesRepository

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, DashBuddyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.analyticsDao()
        eventDao = db.appEventDao()
        repo = AppEventRepo(db, eventDao, db.effectsFiredDao())
        prefs = mock { on { userEconomy } doReturn flowOf(UserEconomy()) }
    }

    @After
    fun tearDown() = db.close()

    private fun projector() = AnalyticsProjector(db, repo, eventDao, dao, prefs)

    private suspend fun insert(type: AppEventType, sessionId: String?, at: Long, payload: AppEventPayload?): Long =
        eventDao.insert(
            AppEventEntity(
                aggregateId = sessionId,
                eventType = type,
                eventPayload = payload?.let(AppEventCodec::encodePayload) ?: "{}",
                occurredAt = at,
            ),
        )

    private fun start(sid: String, at: Long) =
        SessionStartPayload(sid, "DoorDash", at, SessionStartSource.INTERACTION, "start")

    private fun stop(sid: String, at: Long) =
        SessionStopPayload(sid, at, SessionEndSource.EARLY_OFFLINE, platform = "DoorDash")

    private fun pickup(jobId: String, taskId: String, store: String, at: Long, address: String? = null) =
        PickupPayload(
            jobId = jobId, taskId = taskId, storeName = store, phaseStartedAt = at - 100,
            arrivedAt = at - 60, confirmedAt = at, activity = "PICKUP", storeAddress = address,
        )

    private fun delivery(
        jobId: String,
        taskId: String,
        store: String,
        at: Long,
        parsedPay: ParsedPay? = null,
        totalPay: Double? = null,
        dropRealizedPay: Double? = null,
        customerHash: String? = "cust-$taskId",
    ) = DeliveryPayload(
        jobId = jobId, taskId = taskId, storeName = store, customerHash = customerHash,
        addressHash = "addr-$taskId", phaseStartedAt = at - 200, arrivedAt = at - 30,
        completedAt = at, totalPay = totalPay, parsedPay = parsedPay, dropRealizedPay = dropRealizedPay,
    )

    private fun receipt(vararg tips: Pair<String, Double>) =
        ParsedPay(
            appPayComponents = listOf(ParsedPayItem("Base Pay", 5.0)),
            customerTips = tips.map { ParsedPayItem(it.first, it.second) },
        )

    private val targetKey = "doordash|target|02426"
    private val mapleKey = "doordash|maple street biscuit company|alamo ranch"

    // ── Basics: fresh fold stamps stores + pickup_records + delivery storeKey ──

    @Test
    fun `fresh fold resolves a single-store job — stores row, pickup + delivery keyed`() = runBlocking {
        insert(AppEventType.DASH_START, "S1", 1000, start("S1", 1000))
        val pu = insert(AppEventType.PICKUP_CONFIRMED, "S1", 1100, pickup("J1", "pT", "Target", 1100, "123 Main St"))
        val d = insert(
            AppEventType.DELIVERY_COMPLETED, "S1", 1200,
            delivery("J1", "dT", "Target", 1200, receipt("Target (02426)" to 3.0), totalPay = 8.0),
        )
        insert(AppEventType.DASH_STOP, "S1", 1300, stop("S1", 1300))
        projector().catchUp()

        val store = dao.store(targetKey)
        assertNotNull("Target keyed store exists", store)
        assertEquals("target", store!!.normalizedChain)
        assertEquals("02426", store.runningKey)
        assertEquals("123 Main St", store.address)
        assertEquals("Target", store.pickupNameForm)
        assertEquals(targetKey, dao.pickupRecordsForJob("J1").single { it.eventSequenceId == pu }.storeKey)
        assertEquals(targetKey, dao.deliveryRecord(d)!!.storeKey)
        // pay/net untouched by resolution.
        assertEquals(8.0, dao.deliveryRecord(d)!!.realizedPay!!, 0.001)
    }

    // ── B1: keyed drop then payout-less DASH_STOP → no downgrade; re-run is a no-op ──

    @Test
    fun `B1 — payout-less DASH_STOP does not downgrade a keyed store, and a re-run is a no-op`() = runBlocking {
        insert(AppEventType.DASH_START, "S1", 1000, start("S1", 1000))
        insert(AppEventType.PICKUP_CONFIRMED, "S1", 1100, pickup("J1", "pT", "Target", 1100))
        val d = insert(
            AppEventType.DELIVERY_COMPLETED, "S1", 1200,
            delivery("J1", "dT", "Target", 1200, receipt("Target (02426)" to 3.0), totalPay = 8.0),
        )
        insert(AppEventType.DASH_STOP, "S1", 1300, stop("S1", 1300))
        projector().catchUp()

        // Still keyed after the payout-less DASH_STOP resolution ran (session-level, no receipt).
        assertEquals(targetKey, dao.deliveryRecord(d)!!.storeKey)
        val storeBefore = dao.store(targetKey)!!

        // A from-zero refold re-runs every resolution over the row set: byte-identical (L1) — the key
        // is row-sourced, so the payout-less close recomputes the SAME key, no downgrade.
        wipeAndResetWatermark()
        projector().catchUp()
        assertEquals("store row byte-identical on refold", storeBefore, dao.store(targetKey))
        assertEquals(targetKey, dao.deliveryRecord(d)!!.storeKey)
    }

    private suspend fun wipeAndResetWatermark() {
        dao.deleteAllDeliveries(); dao.deleteAllSessions(); dao.deleteAllOffers()
        dao.deleteAllStores(); dao.deleteAllPickupRecords()
        dao.setWatermark(AnalyticsProjectionStateEntity(watermarkSequenceId = 0, projectorVersion = 5))
    }

    // ── B2: Target+Maple stack, one receipt on drop1, drop2 receipt-less, closed by DASH_STOP ──

    @Test
    fun `B2 — a multi-store stack keys BOTH stores from the single receipt`() = runBlocking {
        insert(AppEventType.DASH_START, "S1", 1000, start("S1", 1000))
        insert(AppEventType.PICKUP_CONFIRMED, "S1", 1100, pickup("J1", "pT", "Target", 1100))
        insert(AppEventType.PICKUP_CONFIRMED, "S1", 1150, pickup("J1", "pM", "Maple Street Biscuit Company", 1150))
        // drop1 carries the FULL receipt (both store lines); drop2 is receipt-less.
        val d1 = insert(
            AppEventType.DELIVERY_COMPLETED, "S1", 1200,
            delivery(
                "J1", "dT", "Target", 1200,
                parsedPay = receipt("Target (02426)" to 2.25, "Maple Street Biscuit - Alamo Ranch" to 6.50),
                dropRealizedPay = 4.5,
            ),
        )
        val d2 = insert(
            AppEventType.DELIVERY_COMPLETED, "S1", 1250,
            delivery("J1", "dM", "Maple Street Biscuit Company", 1250, dropRealizedPay = 4.25),
        )
        insert(AppEventType.DASH_STOP, "S1", 1300, stop("S1", 1300))
        projector().catchUp()

        assertNotNull("Target keyed", dao.store(targetKey))
        assertNotNull("Maple keyed (NOT chain-only) — both stores of the stack keyed from one receipt", dao.store(mapleKey))
        assertEquals(targetKey, dao.deliveryRecord(d1)!!.storeKey)
        assertEquals(mapleKey, dao.deliveryRecord(d2)!!.storeKey)
    }

    // ── F1: incremental fold with a batch boundary splitting the job ≡ from-zero refold ──

    @Test
    fun `F1 — incremental fold with the batch boundary BETWEEN the two drops equals a from-zero refold`() = runBlocking {
        // FIX 12b: the receipt rides the SECOND drop, with the batch boundary between the drops — the
        // divergence-prone shape (drop1's early resolution keys chain-only; drop2's receipt upgrades it).
        insert(AppEventType.DASH_START, "S1", 1000, start("S1", 1000))
        insert(AppEventType.PICKUP_CONFIRMED, "S1", 1100, pickup("J1", "pT", "Target", 1100))
        insert(AppEventType.PICKUP_CONFIRMED, "S1", 1150, pickup("J1", "pM", "Maple Street Biscuit Company", 1150))
        insert(AppEventType.DELIVERY_COMPLETED, "S1", 1200, delivery("J1", "dT", "Target", 1200, dropRealizedPay = 4.5))
        insert(
            AppEventType.DELIVERY_COMPLETED, "S1", 1250,
            delivery(
                "J1", "dM", "Maple Street Biscuit Company", 1250,
                parsedPay = receipt("Target (02426)" to 2.25, "Maple Street Biscuit - Alamo Ranch" to 6.50),
                dropRealizedPay = 4.25,
            ),
        )
        insert(AppEventType.DASH_STOP, "S1", 1300, stop("S1", 1300))

        // Incremental: limit 2 lands DASH_START+pT in b1, pM+dT in b2, dM+DASH_STOP in b3 — so dT and dM
        // (and their resolutions) fall in DIFFERENT batches.
        val p = projector()
        while (p.processBatch(limit = 2) != null) { /* drain */ }
        val incrementalVisible = dao.storeReportRows().first().map { it.storeKey }.toSet()
        val incrementalDeliveryKeys = listOf("dT", "dM").associateWith { dao.deliveryRecordByTask(it)?.storeKey }
        val incrementalPickupKeys = dao.pickupRecordsForJob("J1").associate { it.taskId to it.storeKey }

        // From-zero refold in ONE drain.
        wipeAndResetWatermark()
        projector().catchUp()
        val refoldVisible = dao.storeReportRows().first().map { it.storeKey }.toSet()
        val refoldDeliveryKeys = listOf("dT", "dM").associateWith { dao.deliveryRecordByTask(it)?.storeKey }
        val refoldPickupKeys = dao.pickupRecordsForJob("J1").associate { it.taskId to it.storeKey }

        // The READ-VISIBLE store set (M4 EXISTS-filtered) + ALL delivery + ALL pickup stamps converge.
        // NOTE: the raw `stores` table can differ — the incremental path leaves an unreferenced chain-only
        // orphan row (dT's early resolution before dM's receipt landed); that orphan is an ACCEPTED,
        // read-invisible residual (M4 filters it out of every read), not a divergence that surfaces.
        assertEquals("read-visible store set identical incremental vs refold", refoldVisible, incrementalVisible)
        assertEquals("delivery storeKeys identical", refoldDeliveryKeys, incrementalDeliveryKeys)
        assertEquals("pickup storeKeys identical", refoldPickupKeys, incrementalPickupKeys)
        assertEquals(targetKey, incrementalDeliveryKeys["dT"])
        assertEquals(mapleKey, incrementalDeliveryKeys["dM"])
        assertEquals(setOf(targetKey, mapleKey), incrementalVisible)
    }

    // ── H1: a disagreeing driver correction pins the row; resolution never re-keys it ──

    @Test
    fun `H1 — a store-name correction pins the row and resolution does not re-key it (pin is load-bearing)`() = runBlocking {
        insert(AppEventType.DASH_START, "S1", 1000, start("S1", 1000))
        insert(AppEventType.PICKUP_CONFIRMED, "S1", 1100, pickup("J1", "pT", "Target", 1100))
        val d = insert(
            AppEventType.DELIVERY_COMPLETED, "S1", 1200,
            delivery("J1", "dT", "Target", 1200, receipt("Target (02426)" to 3.0), totalPay = 8.0),
        )
        // FIX 12a: correct to a name that STILL brand-matches the pickup anchor ("Target Cafe" shares the
        // leading "target" token). Without the pin, resolution WOULD re-key it back to the Target entity —
        // so the pin is genuinely load-bearing here (a disagreeing name would have stayed null regardless).
        insert(
            AppEventType.DELIVERY_ADJUSTMENT, "S1", 1250,
            DeliveryAdjustmentPayload(targetEventSequenceId = d, sessionId = "S1", newStoreName = "Target Cafe"),
        )
        insert(AppEventType.DASH_STOP, "S1", 1300, stop("S1", 1300)) // session-level re-resolution
        projector().catchUp()

        val row = dao.deliveryRecord(d)!!
        assertEquals("driver name honored", "Target Cafe", row.storeName)
        assertEquals("pinned", 1, row.storeKeyPinned)
        assertNull("storeKey stays null — the pin blocks the brand-matching re-key", row.storeKey)

        // FIX 12a refold half: a from-zero refold re-derives the pin (the DELIVERY_ADJUSTMENT replays
        // after its target) → the pinned row's final state is identical to the incremental path.
        wipeAndResetWatermark()
        projector().catchUp()
        val refold = dao.deliveryRecord(d)!!
        assertEquals("Target Cafe", refold.storeName)
        assertEquals(1, refold.storeKeyPinned)
        assertNull(refold.storeKey)
    }

    // ── F8: a PROJECTOR_VERSION bump wipes stores + pickup_records ──

    @Test
    fun `F8 — a version bump wipes stale stores and pickup_records`() = runBlocking {
        // Seed a real store + one bogus stale store, and a bogus pickup row, at an OLD projector version.
        insert(AppEventType.DASH_START, "S1", 1000, start("S1", 1000))
        insert(AppEventType.PICKUP_CONFIRMED, "S1", 1100, pickup("J1", "pT", "Target", 1100))
        insert(
            AppEventType.DELIVERY_COMPLETED, "S1", 1200,
            delivery("J1", "dT", "Target", 1200, receipt("Target (02426)" to 3.0), totalPay = 8.0),
        )
        insert(AppEventType.DASH_STOP, "S1", 1300, stop("S1", 1300))
        projector().catchUp() // version 5, real store created

        val bogus = StoreEntity(
            storeKey = "doordash|ghost|999", platform = "doordash", normalizedChain = "ghost",
            chainDisplay = "Ghost", runningKey = "999", offerNameForm = null, pickupNameForm = "Ghost",
            payoutNameForm = null, address = null,
        )
        dao.upsertStore(bogus)
        // FIX 12c: also seed a POISONED pickup_records row (a fake PK no refold recreates) to prove the
        // F8 wipe clears pickup_records, not only stores.
        dao.upsertPickup(
            PickupRecordEntity(
                eventSequenceId = 99_999, sessionId = "S1", platform = "doordash", jobId = "JGHOST",
                taskId = "PTGHOST", storeName = "Ghost", storeKey = "doordash|ghost|999",
                phaseStartedAt = 1, arrivedAt = 1, confirmedAt = 2, deadlineMillis = null, activity = "PICKUP",
            ),
        )
        // Force the version-mismatch rebuild by writing an OLD projectorVersion watermark.
        dao.setWatermark(AnalyticsProjectionStateEntity(watermarkSequenceId = 999, projectorVersion = 4))

        projector().catchUp() // triggers rebuildIfVersionChanged → F8 wipe + refold

        assertNull("stale bogus store wiped", dao.store("doordash|ghost|999"))
        assertNotNull("real store re-created by the refold", dao.store(targetKey))
        assertEquals("poisoned pickup row gone after rebuild (F8)", 0, dao.pickupRecordsForJob("JGHOST").size)
    }

    // ── FIX 12d: F4 offer-link guards ──

    private fun offerAccepted(offerHash: String, merchant: String, at: Long) =
        OfferPayload(
            offerHash = offerHash,
            parsedOffer = ParsedOffer(offerHash = offerHash),
            evaluation = OfferEvaluation(
                action = OfferAction.ACCEPT, score = 80.0, qualityLevel = OfferQuality.GOOD,
                payAmount = 10.0, fuelCostEstimate = 0.0, nonFuelCostEstimate = 0.75,
                operatingCostPerMile = 0.25, netPayAmount = 9.25, distanceMiles = 3.0,
                dollarsPerMile = 3.0, dollarsPerHour = 20.0, estimatedTimeMinutes = 15.0,
                itemCount = 1.0, merchantName = merchant,
            ),
            outcome = AppEventType.OFFER_ACCEPTED, presentedAt = at - 10, decidedAt = at,
            returnFlow = Flow.Idle,
        )

    @Test
    fun `F4 — a queued next-job offer that brand-DISAGREES is not linked by the temporal fallback`() = runBlocking {
        insert(AppEventType.DASH_START, "S1", 1000, start("S1", 1000))
        // A queued NEXT-job offer accepted mid-job for a DIFFERENT brand (Whataburger), no offer hashes
        // on the job → temporal fallback only. It must NOT be linked to the Target job.
        val offSeq = insert(AppEventType.OFFER_ACCEPTED, "S1", 1050, offerAccepted("HASHW", "Whataburger", 1050))
        insert(AppEventType.PICKUP_CONFIRMED, "S1", 1100, pickup("J1", "pT", "Target", 1100))
        insert(
            AppEventType.DELIVERY_COMPLETED, "S1", 1200,
            delivery("J1", "dT", "Target", 1200, receipt("Target (02426)" to 3.0), totalPay = 8.0),
        )
        insert(AppEventType.DASH_STOP, "S1", 1300, stop("S1", 1300))
        projector().catchUp()

        val offer = dao.offerRecord(offSeq)!!
        assertNull("a brand-disagreeing queued offer is not linked (F4)", offer.linkedJobId)
    }

    @Test
    fun `FIX 1b — a job already exactly-linked does not claim a second offer on the DASH_STOP re-run`() = runBlocking {
        insert(AppEventType.DASH_START, "S1", 1000, start("S1", 1000))
        // The job's OWN accepted offer (hash carried onto the job's events → exact link).
        val ownSeq = insert(AppEventType.OFFER_ACCEPTED, "S1", 1040, offerAccepted("HASH1", "Target", 1040))
        // A SECOND accepted Target offer (a next job's), same brand → would temporally agree.
        val nextSeq = insert(AppEventType.OFFER_ACCEPTED, "S1", 1060, offerAccepted("HASH2", "Target", 1060))
        insert(AppEventType.PICKUP_CONFIRMED, "S1", 1100, pickupWithHashes("J1", "pT", "Target", 1100, listOf("HASH1")))
        insert(
            AppEventType.DELIVERY_COMPLETED, "S1", 1200,
            deliveryWithHashes("J1", "dT", "Target", 1200, receipt("Target (02426)" to 3.0), 8.0, listOf("HASH1")),
        )
        insert(AppEventType.DASH_STOP, "S1", 1300, stop("S1", 1300)) // session-level re-run, no hashes → temporal
        projector().catchUp()

        assertEquals("the exact-linked own offer is claimed", "J1", dao.offerRecord(ownSeq)!!.linkedJobId)
        assertNull("the second Target offer is NOT claimed on the DASH_STOP re-run (FIX 1b)", dao.offerRecord(nextSeq)!!.linkedJobId)
    }

    private fun pickupWithHashes(jobId: String, taskId: String, store: String, at: Long, hashes: List<String>) =
        PickupPayload(
            jobId = jobId, taskId = taskId, storeName = store, phaseStartedAt = at - 100,
            arrivedAt = at - 60, confirmedAt = at, activity = "PICKUP", jobOfferHashes = hashes,
        )

    private fun deliveryWithHashes(
        jobId: String, taskId: String, store: String, at: Long, parsedPay: ParsedPay, totalPay: Double, hashes: List<String>,
    ) = DeliveryPayload(
        jobId = jobId, taskId = taskId, storeName = store, customerHash = "cust-$taskId",
        addressHash = "addr-$taskId", phaseStartedAt = at - 200, arrivedAt = at - 30,
        completedAt = at, totalPay = totalPay, parsedPay = parsedPay, jobOfferHashes = hashes,
    )
}
