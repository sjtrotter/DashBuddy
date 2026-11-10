package cloud.trotter.log.gig.ui.main.analytics.search

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import cloud.trotter.log.gig.core.data.analytics.RecapRepository
import cloud.trotter.log.gig.core.data.analytics.RecordsStatusRepository
import cloud.trotter.log.gig.core.data.analytics.StoreSearchRepository
import cloud.trotter.log.gig.core.database.analytics.DeliveryRecordEntity
import cloud.trotter.log.gig.core.database.analytics.OfferRecordEntity
import cloud.trotter.log.gig.core.database.analytics.PickupRecordEntity
import cloud.trotter.log.gig.core.database.analytics.StoreNameRow
import cloud.trotter.log.gig.core.database.analytics.StoreSearchDao
import cloud.trotter.log.gig.domain.analytics.RecordsStatus
import cloud.trotter.log.gig.domain.analytics.recap.DayShift
import cloud.trotter.log.gig.domain.state.Platform
import cloud.trotter.log.gig.ui.main.navigation.Screen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import timber.log.Timber

/**
 * #1540: the Search screen's ViewModel over the real [StoreSearchRepository] and an in-memory DAO. 25 shifts each saw a
 * Wingstop offer: the first page folds 10, "Show more shifts" adds 10 more, a new query starts again at the first
 * page, a return after the subscription lapsed keeps the results in view, and the query never reaches an INFO+ line.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {

    private val t0 = 1_790_000_000_000L
    private val day = 86_400_000L

    /** Newest first, as `allShifts` reads them; s0 is the newest. */
    private val shifts = (0 until 25).map { i ->
        DayShift("s$i", if (i % 2 == 0) Platform.DoorDash else Platform.Uber, t0 - i * day, t0 - i * day + 3_600_000L, 0.0, 0.0, false)
    }
    private val offers = shifts.mapIndexed { i, s -> offer(1_000L + i, s.sessionId, s.startedAt + 60_000L, "Wingstop") }
    private var fail: Throwable? = null

    private val dao = object : StoreSearchDao {
        private fun <T> read(value: () -> T): Flow<T> = flow { fail?.let { throw it }; emit(value()) }
        override fun offerNames() = read { offers.map { StoreNameRow(it.eventSequenceId, it.sessionId!!, it.merchantName!!) } }
        override fun deliveryNames() = read { emptyList<StoreNameRow>() }
        override fun offersForSessions(sessionIds: List<String>) = read { offers.filter { it.sessionId in sessionIds } }
        override fun pickupsForSessions(sessionIds: List<String>) = read { emptyList<PickupRecordEntity>() }
        override fun deliveriesForSessions(sessionIds: List<String>) = read { emptyList<DeliveryRecordEntity>() }
    }
    private val recapRepository: RecapRepository = mock { on { allShifts() } doReturn flowOf(shifts) }
    private val recordsStatusRepository: RecordsStatusRepository =
        mock { on { status } doReturn MutableStateFlow(RecordsStatus.UP_TO_DATE) }

    private fun offer(seq: Long, sessionId: String, at: Long, store: String) = OfferRecordEntity(
        eventSequenceId = seq, sessionId = sessionId, platform = "doordash", offerHash = "h$seq",
        outcome = "OFFER_DECLINED", presentedAt = at, decidedAt = at, payAmount = 6.0, distanceMiles = 3.0, itemCount = 1,
        merchantName = store, score = null, action = null, quality = null, estNetPay = null, estDollarsPerHour = null,
        estDollarsPerMile = null, estTimeMinutes = null, estOperatingCostPerMile = null,
    )

    private fun viewModel(query: String?) = SearchViewModel(
        SavedStateHandle(mapOf(Screen.Search.ARG_QUERY to query)),
        StoreSearchRepository(dao, recapRepository),
        recordsStatusRepository,
    )

    private val tree = RecordingTree()

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        Timber.uproot(tree)
    }

    private fun TestScope.settle() {
        advanceTimeBy(SEARCH_DEBOUNCE_MS * 4)
        runCurrent()
    }

    @Test
    fun `a page folds ten matched shifts, newest first, and Show more adds ten`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val vm = viewModel("wingstop")
        val job = launch { vm.uiState.collect {} }
        settle()
        assertEquals((0 until 10).map { "s$it" }, vm.uiState.value.groups.map { it.shift.sessionId })
        assertTrue(vm.uiState.value.hasMore)
        // Each shift is its own group, and its app stays with it.
        assertEquals(Platform.Uber, vm.uiState.value.groups[1].shift.platform)

        vm.showMore()
        settle()
        assertEquals(20, vm.uiState.value.groups.size)
        assertEquals("s19", vm.uiState.value.groups.last().shift.sessionId)
        vm.showMore()
        settle()
        assertEquals(25, vm.uiState.value.groups.size)
        assertFalse(vm.uiState.value.hasMore)

        // A new query starts again at the first page.
        vm.onQueryChange("WINGSTOP ")
        assertEquals(25, vm.uiState.value.groups.size)
        vm.onQueryChange("wing")
        settle()
        assertEquals(10, vm.uiState.value.groups.size)
        assertEquals("wing", vm.uiState.value.resultsFor)
        job.cancel()
    }

    @Test
    fun `a blank query lists nothing, and no match is an answer with no group`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val vm = viewModel(null)
        val job = launch { vm.uiState.collect {} }
        settle()
        assertNull(vm.uiState.value.resultsFor)
        assertTrue(vm.uiState.value.groups.isEmpty())

        vm.onQueryChange("Chipotle")
        settle()
        assertEquals("chipotle", vm.uiState.value.resultsFor)
        assertTrue(vm.uiState.value.groups.isEmpty())
        job.cancel()
    }

    @Test
    fun `a return after the subscription lapsed keeps the results and the page`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val vm = viewModel("wingstop")
        val first = launch { vm.uiState.collect {} }
        settle()
        vm.showMore()
        settle()
        first.cancel()
        // The shift page was open longer than WhileSubscribed's grace: the upstream stopped.
        advanceTimeBy(10_000)

        val seen = mutableListOf<SearchUiState>()
        val again = launch { vm.uiState.collect { seen += it } }
        settle()
        assertTrue(seen.isNotEmpty())
        assertTrue("never blank on return", seen.all { it.groups.size == 20 })
        assertEquals("wingstop", vm.query)
        again.cancel()
    }

    @Test
    fun `after a Retry, a return after the subscription lapsed still keeps the results`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        fail = IllegalStateException("read failed")
        val vm = viewModel("wingstop")
        val first = launch { vm.uiState.collect {} }
        settle()
        assertTrue(vm.uiState.value.failed)
        fail = null
        vm.retry()
        settle()
        assertEquals(10, vm.uiState.value.groups.size)
        first.cancel()
        advanceTimeBy(10_000)

        val seen = mutableListOf<SearchUiState>()
        val again = launch { vm.uiState.collect { seen += it } }
        settle()
        assertTrue("never blank on return", seen.isNotEmpty() && seen.all { it.groups.size == 10 })
        again.cancel()
    }

    @Test
    fun `the query never reaches an INFO or higher line, a failure included`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        Timber.plant(tree)
        val query = "Zqxwingstop"
        val vm = viewModel(null)
        val job = launch { vm.uiState.collect {} }
        vm.onQueryChange(query)
        settle()
        fail = IllegalStateException("no such table near $query")
        vm.retry()
        settle()
        assertTrue(vm.uiState.value.failed)
        job.cancel()

        val shipped = tree.lines.filter { it.priority >= Log.INFO }
        assertEquals("one WARN for the failure", 1, shipped.count { it.priority == Log.WARN })
        for (line in shipped) {
            val text = listOfNotNull(line.message, line.error?.message, line.error?.toString()).joinToString(" ")
            assertFalse(text, text.contains(query, ignoreCase = true))
        }
    }

    private class RecordingTree : Timber.Tree() {
        data class Line(val priority: Int, val message: String, val error: Throwable?)

        val lines = mutableListOf<Line>()

        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            lines += Line(priority, message, t)
        }
    }
}
