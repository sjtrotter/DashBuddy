package cloud.trotter.log.gig.notice

import cloud.trotter.log.gig.domain.census.contributor.ContributorEnd
import cloud.trotter.log.gig.domain.census.contributor.ContributorRecord
import cloud.trotter.log.gig.domain.census.contributor.ShiftActivity
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #1625: the invitation is asked for only when a running shift ends: never at app start, never while a shift runs.
 * Whether it then posts is [cloud.trotter.log.gig.core.data.census.contributor.CensusContributor.inviteAfterShift]'s.
 */
class ContributorInvitationNotifierTest {
    private val noShifts = object : ShiftActivity { override val shiftLive = emptyFlow<Boolean>() }

    @Test fun `asks only on a running-to-ended change and posts what it is given`() = runTest {
        var asks = 0
        val posted = mutableListOf<Boolean>()
        val answers = ArrayDeque(listOf(ContributorRecord(), null, ContributorRecord(ended = ContributorEnd.EXPIRED)))
        val notifier = ContributorInvitationNotifier(noShifts, { asks++; answers.removeFirst() }, backgroundScope) { posted += it }

        notifier.watch(flowOf(false, false, true, true, false, false, true, false, true, false))

        assertEquals(3, asks)
        // The first offer, nothing on the second (already offered), then the renewal after an expired grant.
        assertEquals(listOf(false, true), posted)
    }

    @Test fun `app start with no shift asks nothing`() = runTest {
        var asks = 0
        val notifier = ContributorInvitationNotifier(noShifts, { asks++; ContributorRecord() }, backgroundScope) { }
        notifier.watch(flowOf(false))
        assertEquals(0, asks)
    }

    @Test fun `a refused post does not stop the watch`() = runTest {
        var asks = 0
        val notifier = ContributorInvitationNotifier(noShifts, { asks++; ContributorRecord() }, backgroundScope) {
            throw SecurityException("notifications off")
        }
        notifier.watch(flowOf(true, false, true, false))
        assertEquals(2, asks)
    }
}
