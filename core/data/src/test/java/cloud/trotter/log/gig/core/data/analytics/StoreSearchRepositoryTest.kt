package cloud.trotter.log.gig.core.data.analytics

import cloud.trotter.log.gig.core.database.analytics.DeliveryRecordEntity
import cloud.trotter.log.gig.core.database.analytics.OfferRecordEntity
import cloud.trotter.log.gig.core.database.analytics.PickupRecordEntity
import cloud.trotter.log.gig.core.database.analytics.StoreNameRow
import cloud.trotter.log.gig.core.database.analytics.StoreSearchDao
import cloud.trotter.log.gig.domain.analytics.recap.DayShift
import cloud.trotter.log.gig.domain.analytics.search.StoreQuery
import cloud.trotter.log.gig.domain.state.Platform
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

/** #1540: an unrelated write re-emits the name reads, but the matched shifts do not change, so the page is not re-read. */
@OptIn(ExperimentalCoroutinesApi::class)
class StoreSearchRepositoryTest {

    private val shift = DayShift("s1", Platform.DoorDash, 1_000L, 2_000L, 0.0, 0.0, false)
    private val offerNames = MutableStateFlow(listOf(StoreNameRow(1, "s1", "Wingstop")))

    private val dao = object : StoreSearchDao {
        override fun offerNames(): Flow<List<StoreNameRow>> = offerNames
        override fun deliveryNames(): Flow<List<StoreNameRow>> = flowOf(emptyList())
        override fun offersForSessions(sessionIds: List<String>): Flow<List<OfferRecordEntity>> = flowOf(emptyList())
        override fun pickupsForSessions(sessionIds: List<String>): Flow<List<PickupRecordEntity>> = flowOf(emptyList())
        override fun deliveriesForSessions(sessionIds: List<String>): Flow<List<DeliveryRecordEntity>> = flowOf(emptyList())
    }
    private val recap: RecapRepository = mock { on { allShifts() } doReturn MutableStateFlow(listOf(shift)) }

    @Test
    fun `an offer that does not match leaves the matched shifts unchanged`() = runTest {
        val repo = StoreSearchRepository(dao, recap)
        val seen = mutableListOf<List<DayShift>>()
        val job = launch { repo.matchingShifts(StoreQuery.of("wingstop")!!).collect { seen += it } }
        runCurrent()
        offerNames.value = offerNames.value + StoreNameRow(2, "s1", "Chipotle")
        runCurrent()
        assertEquals(1, seen.size)
        offerNames.value = offerNames.value + StoreNameRow(3, "s1", "Wingstop")
        runCurrent()
        assertEquals(1, seen.size)
        job.cancel()
    }
}
