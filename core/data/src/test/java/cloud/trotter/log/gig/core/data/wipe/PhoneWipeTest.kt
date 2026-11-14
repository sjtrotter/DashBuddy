package cloud.trotter.log.gig.core.data.wipe

import cloud.trotter.log.gig.core.data.census.CensusCredentialStore
import cloud.trotter.log.gig.core.data.census.CensusDataDeletion
import cloud.trotter.log.gig.core.data.census.CensusUploadLock
import cloud.trotter.log.gig.core.data.census.KeystoreSealer
import cloud.trotter.log.gig.domain.census.CensusDeletion
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * #1579: the wipe asks the census server first only for an enrolled census ID, wipes on a confirmation or when there
 * is nothing to ask, and on anything short of a confirmation deletes nothing until the dasher picks Delete now.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PhoneWipeTest {
    private val events = mutableListOf<String>()
    private val lock = CensusUploadLock()
    private val credentials: CensusCredentialStore = mock()
    private val deletion: CensusDataDeletion = mock()
    private val sealer = object : KeystoreSealer() {
        override fun reset() {
            events += "key"
        }
    }
    private var clearStarts = true
    private val clearer = AppStorageClearer {
        events += "clear locked=${lock.mutex.isLocked}"
        clearStarts
    }

    private fun TestScope.wipe(enrolled: Boolean): PhoneWipe {
        whenever(credentials.installId).thenReturn(flowOf(if (enrolled) ID else null))
        return PhoneWipe(credentials, deletion, lock, sealer, clearer, backgroundScope)
    }

    /** The wipe runs on the background (application) scope, which advanceUntilIdle does not wait for. */
    private fun TestScope.settle() {
        advanceTimeBy(60_000)
        runCurrent()
    }

    private suspend fun answers(request: CensusDeletion, afterMillis: Long = 0) {
        whenever(deletion.requestHoldingLock()).doSuspendableAnswer {
            events += "census"
            delay(afterMillis)
            request
        }
    }

    @Test fun `a phone with no census ID wipes without asking the census server`() = runTest {
        val wipe = wipe(enrolled = false)
        wipe.start()
        settle()

        verify(deletion, never()).requestHoldingLock()
        verify(deletion, never()).delete()
        assertEquals(listOf("key", "clear locked=true"), events)
        assertEquals(WipeProgress.Wiping(censusConfirmed = false), wipe.progress.value)
    }

    @Test fun `a confirmed deletion shows the confirmation then wipes with no census run in between`() = runTest {
        answers(CensusDeletion(1L, CensusDeletion.State.DELETED, localCleared = true, installId = ID, rows = 4))
        val wipe = wipe(enrolled = true)
        wipe.start()
        runCurrent()

        assertEquals(WipeProgress.Wiping(censusConfirmed = true), wipe.progress.value)
        assertEquals(listOf("census"), events)
        assertTrue("a census run cannot take the lock before the clear", lock.mutex.isLocked)

        advanceTimeBy(PhoneWipe.CONFIRMED_NOTICE_MILLIS + 1)
        assertEquals(listOf("census", "key", "clear locked=true"), events)
    }

    @Test fun `a deletion that sent nothing wipes without claiming a confirmation`() = runTest {
        answers(CensusDeletion(1L, CensusDeletion.State.NOTHING_SENT, localCleared = true))
        val wipe = wipe(enrolled = true)
        wipe.start()
        runCurrent()

        assertEquals(WipeProgress.Wiping(censusConfirmed = false), wipe.progress.value)
        assertEquals(listOf("census", "key", "clear locked=true"), events)
    }

    @Test fun `a pending deletion deletes nothing and Wait leaves everything as it is`() = runTest {
        for ((waiting, reason) in listOf(
            CensusDeletion.Waiting.NOT_YET to WipeProgress.Reason.NOT_YET,
            CensusDeletion.Waiting.UNREACHABLE to WipeProgress.Reason.UNREACHABLE,
            CensusDeletion.Waiting.UNCONFIRMED to WipeProgress.Reason.UNCONFIRMED,
        )) {
            answers(CensusDeletion(1L, CensusDeletion.State.PENDING, localCleared = true, installId = ID, waiting = waiting))
            events.clear() // Re-stubbing a suspend function runs the previous stub once.
            val wipe = wipe(enrolled = true)
            wipe.start()
            settle()

            assertEquals(WipeProgress.CensusNotConfirmed(reason), wipe.progress.value)
            assertTrue(reason.canWait)
            assertEquals("nothing is deleted while the dasher decides", listOf("census"), events)
            assertFalse("the census keeps running while the request waits", lock.mutex.isLocked)

            wipe.dismiss()
            settle()
            assertEquals(WipeProgress.Idle, wipe.progress.value)
            assertEquals(listOf("census"), events)
        }
    }

    @Test fun `Delete now after a pending deletion wipes`() = runTest {
        answers(CensusDeletion(1L, CensusDeletion.State.PENDING, localCleared = true, installId = ID,
            waiting = CensusDeletion.Waiting.NOT_YET))
        val wipe = wipe(enrolled = true)
        wipe.start()
        settle()

        wipe.wipeNow()
        settle()
        assertEquals(WipeProgress.Wiping(censusConfirmed = false), wipe.progress.value)
        assertEquals(listOf("census", "key", "clear locked=true"), events)
    }

    @Test fun `no answer in time offers the choice and keeps the lock until the request ends`() = runTest {
        answers(
            CensusDeletion(1L, CensusDeletion.State.DELETED, localCleared = true, installId = ID, rows = 1),
            afterMillis = PhoneWipe.CENSUS_WAIT_MILLIS + 10_000,
        )
        val wipe = wipe(enrolled = true)
        wipe.start()
        advanceTimeBy(PhoneWipe.CENSUS_WAIT_MILLIS + 1)

        assertEquals(WipeProgress.CensusNotConfirmed(WipeProgress.Reason.NO_ANSWER), wipe.progress.value)
        assertTrue("the late request still runs under the lock", lock.mutex.isLocked)
        settle()
        assertFalse(lock.mutex.isLocked)
        assertEquals(listOf("census"), events)
    }

    @Test fun `a census ID that can no longer sign offers Delete now without Wait`() = runTest {
        answers(CensusDeletion(1L, CensusDeletion.State.NOT_POSSIBLE, localCleared = true, installId = ID))
        val wipe = wipe(enrolled = true)
        wipe.start()
        settle()

        val progress = wipe.progress.value as WipeProgress.CensusNotConfirmed
        assertEquals(WipeProgress.Reason.NOT_POSSIBLE, progress.reason)
        assertFalse(progress.reason.canWait)
    }

    @Test fun `Android refusing the clear reports it and releases the lock`() = runTest {
        clearStarts = false
        val wipe = wipe(enrolled = false)
        wipe.start()
        settle()

        assertEquals(WipeProgress.Failed, wipe.progress.value)
        assertFalse(lock.mutex.isLocked)
        wipe.dismiss()
        assertEquals(WipeProgress.Idle, wipe.progress.value)
    }

    @Test fun `a second start while one runs is ignored`() = runTest {
        answers(CensusDeletion(1L, CensusDeletion.State.DELETED, localCleared = true, installId = ID), afterMillis = 1_000)
        val wipe = wipe(enrolled = true)
        wipe.start()
        wipe.start()
        settle()
        assertEquals(1, events.count { it == "census" })
        assertEquals(1, events.count { it.startsWith("clear") })
    }

    private companion object {
        const val ID = "12345678-1234-4123-8123-123456789abc"
    }
}
