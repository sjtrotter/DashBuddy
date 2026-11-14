package cloud.trotter.log.gig.feature.settings

import androidx.lifecycle.SavedStateHandle
import cloud.trotter.census.contract.contributor.SlotClass
import cloud.trotter.log.gig.core.data.census.contributor.CensusContributor
import cloud.trotter.log.gig.core.data.census.contributor.ClassesState
import cloud.trotter.log.gig.core.data.census.contributor.ContributorReviewer
import cloud.trotter.log.gig.core.data.census.contributor.ContributorStatus
import cloud.trotter.log.gig.core.data.census.contributor.ReviewLoad
import cloud.trotter.log.gig.core.data.census.contributor.ReviewRow
import cloud.trotter.log.gig.core.data.census.contributor.ScreenReview
import cloud.trotter.log.gig.core.data.census.contributor.SlotValue
import cloud.trotter.log.gig.domain.census.contributor.ContributorEntry
import cloud.trotter.log.gig.domain.census.contributor.ContributorRecord
import cloud.trotter.log.gig.domain.census.contributor.DecisionVersion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * #1626 (Sol round 2 S6): the review screen's exposed state follows the grant even with no subscriber: after the
 * real 5-second stop timeout, a grant that ended while nobody watched is never shown the review it held. Fictional data.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ContributorReviewViewModelTest {
    private val review = ScreenReview(
        "r", "2026-11-13",
        listOf(ReviewRow("root/0/text", "android.widget.TextView", "text", SlotValue.Hashed, SlotClass.CONSTANT, "Pick up by")),
        ClassesState.LOADED, wordsKept = true,
    )
    private val version = DecisionVersion(0, 0, "https://census.example", null, null)
    private fun status(entry: ContributorEntry) = ContributorStatus(ContributorRecord(), entry, canAccept = false, shiftLive = false, version = version)

    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `a grant that ends with no subscriber leaves no review in the exposed state`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val statuses = MutableStateFlow(status(ContributorEntry.ON))
        val contributor = mock<CensusContributor> { on { status } doReturn statuses }
        whenever(contributor.refresh()).thenReturn(true)
        val reviewer = mock<ContributorReviewer>()
        whenever(reviewer.open(any())).thenReturn(ReviewLoad.Ready(review))
        val vm = ContributorReviewViewModel(SavedStateHandle(mapOf(ContributorReviewViewModel.ARG_ID to "r")), reviewer, contributor)

        val first = mutableListOf<ReviewUiState>()
        val watching = launch { vm.uiState.collect { first += it } }
        runCurrent()
        assertNotNull(first.last().review)
        watching.cancel()
        advanceTimeBy(6_000) // past the real WhileSubscribed(5_000) stop timeout
        runCurrent()

        statuses.value = status(ContributorEntry.OFF)
        runCurrent()
        assertNull("no cached review once nobody watches", vm.uiState.value.review)

        val again = mutableListOf<ReviewUiState>()
        val back = launch { vm.uiState.collect { again += it } }
        runCurrent()
        assertTrue(again.isNotEmpty())
        assertTrue("a returning screen never sees the old review", again.all { it.review == null && it.confirmed.isEmpty() })
        assertEquals(ReviewPhase.NOT_ALLOWED, again.last().phase)
        back.cancel()
    }

    @Test fun `a grant that ends while the screen is watched clears it at once`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val statuses = MutableStateFlow(status(ContributorEntry.ON))
        val contributor = mock<CensusContributor> { on { status } doReturn statuses }
        whenever(contributor.refresh()).thenReturn(true)
        val reviewer = mock<ContributorReviewer>()
        whenever(reviewer.open(any())).thenReturn(ReviewLoad.Ready(review))
        val vm = ContributorReviewViewModel(SavedStateHandle(mapOf(ContributorReviewViewModel.ARG_ID to "r")), reviewer, contributor)
        val seen = mutableListOf<ReviewUiState>()
        val watching = launch { vm.uiState.collect { seen += it } }
        runCurrent()
        vm.toggleConfirmed("root/0/text")
        runCurrent()
        assertEquals(setOf("root/0/text"), seen.last().confirmed)
        statuses.value = status(ContributorEntry.OFF)
        runCurrent()
        assertEquals(ReviewPhase.NOT_ALLOWED, seen.last().phase)
        assertNull(seen.last().review)
        assertTrue(seen.last().confirmed.isEmpty())
        watching.cancel()
    }
}
