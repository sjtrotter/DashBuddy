package cloud.trotter.log.gig.core.data.wipe

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import cloud.trotter.census.contract.auth.Bearer
import cloud.trotter.census.contract.auth.InstallSecret
import cloud.trotter.log.gig.core.data.census.CensusCredentialStore
import cloud.trotter.log.gig.core.data.census.CensusDataDeletion
import cloud.trotter.log.gig.core.data.census.CensusSpool
import cloud.trotter.log.gig.core.data.census.CensusUploadLock
import cloud.trotter.log.gig.core.data.census.KeystoreSealer
import cloud.trotter.log.gig.core.data.census.PersistentHealthSink
import cloud.trotter.log.gig.core.data.settings.DevSettingsRepository
import cloud.trotter.log.gig.core.datastore.settings.DevSettingsDataSource
import cloud.trotter.log.gig.core.network.census.CensusApiFactory
import cloud.trotter.log.gig.core.network.census.CensusSentObserver
import cloud.trotter.log.gig.core.network.census.CensusTransport
import cloud.trotter.log.gig.core.network.census.DeleteResult
import cloud.trotter.log.gig.core.network.census.EnrolResult
import cloud.trotter.log.gig.core.network.census.HealthResult
import cloud.trotter.log.gig.core.network.census.PolicyResult
import cloud.trotter.log.gig.core.network.census.UploadResult
import cloud.trotter.log.gig.domain.capture.CensusEnvelopeSink
import cloud.trotter.log.gig.domain.census.CensusDeletion
import cloud.trotter.log.gig.domain.census.CensusUploadScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File

/**
 * #1579: the wipe's three invariants, over #1622's real [CensusDataDeletion] and a transport the test answers.
 * (A) nothing is deleted unless the wipe holds the census upload lock, and a census run holding it is waited for;
 * (B) enrollment is decided under the lock, an unreadable store counts as enrolled, and the request is stored pending
 * with its retry queued before the network, whatever the dialog's 20 s does; (C) a committed wipe is stored first
 * and resumed at the next start before anything else.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PhoneWipeTest {
    @get:Rule val tmp = TemporaryFolder()

    private class MemoryPreferences : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(data.value).also { data.value = it }
    }

    /** Answers deletion with [answer]; any other call but the policy read is a test failure. */
    private inner class Transport : CensusTransport {
        var answer: suspend () -> DeleteResult = { DeleteResult.Deleted(rows = 4, deletedDay = "2026-11-13") }
        override suspend fun deleteData(cred: Bearer.Credential): DeleteResult {
            events += "delete request"
            return answer()
        }
        override suspend fun policy(): PolicyResult = error("no policy in this test")
        override suspend fun enrol(cred: Bearer.Credential, appVersion: String, schemaIds: List<String>): EnrolResult = error("enrol")
        override suspend fun postHealth(cred: Bearer.Credential, reportsJson: List<String>): HealthResult = error("health")
        override suspend fun uploadSkeletons(cred: Bearer.Credential, batchId: String, itemsJson: List<String>): UploadResult = error("upload")
        override suspend fun uploadEnvelopes(cred: Bearer.Credential, batchId: String, itemsJson: List<String>): UploadResult = error("upload")
    }

    private class Scheduler : CensusUploadScheduler {
        var deletionRuns = 0
        override fun enqueueNow(wifiOnly: Boolean, replaceQueued: Boolean) = Unit
        override fun scheduleDaily(runAtMillis: Long, wifiOnly: Boolean) = Unit
        override fun cancelManual() = Unit
        override fun enqueueDeletion() {
            deletionRuns++
        }
    }

    private val events = mutableListOf<String>()
    private val lock = CensusUploadLock()
    private val transport = Transport()
    private var clients = 0
    private val scheduler = Scheduler()
    private val preferences = DevSettingsRepository(DevSettingsDataSource(MemoryPreferences()), true, Dispatchers.Unconfined, "test")
    private val installId = MutableStateFlow<String?>(null)
    private var storeUnreadable = false
    private var credential: suspend () -> CensusCredentialStore.Credential? = { ENROLLED.takeIf { installId.value != null } }
    private val credentials: CensusCredentialStore = mock()
    private val intentFile by lazy { File(tmp.root, WipeIntent.FILE_NAME) }
    private val sealer = object : KeystoreSealer() {
        override fun reset() {
            events += "key locked=${lock.mutex.isLocked}"
        }
    }
    private var clearStarts = true
    private val clearer = AppStorageClearer {
        events += "clear locked=${lock.mutex.isLocked} committed=${intentFile.isFile}"
        clearStarts
    }

    private fun TestScope.wipe(): PhoneWipe {
        val ids: Flow<String?> = flow { if (storeUnreadable) error("unreadable") else emit(installId.value) }
        whenever(credentials.installId).thenReturn(ids)
        runBlocking { whenever(credentials.current()).doSuspendableAnswer { credential() } }
        val deletion = CensusDataDeletion(
            credentials, mock<CensusSpool>(), mock<CensusEnvelopeSink>(), mock<PersistentHealthSink>(), preferences,
            scheduler, lock,
            object : CensusApiFactory(CensusSentObserver.NONE) {
                override fun create(baseUrl: String): CensusTransport = transport.also { clients++ }
            },
            backgroundScope, "test",
        )
        return PhoneWipe(credentials, deletion, lock, sealer, clearer, WipeIntent(intentFile), backgroundScope)
    }

    /** The wipe runs on the background (application) scope, which advanceUntilIdle does not wait for. */
    private fun TestScope.settle(millis: Long = 60_000) {
        advanceTimeBy(millis)
        runCurrent()
    }

    private val wiped = listOf("key locked=true", "clear locked=true committed=true")

    @Test fun `a phone with no census ID wipes under the lock without a network call`() = runTest {
        val wipe = wipe()
        wipe.start()
        settle()

        assertEquals(wiped, events)
        assertEquals("no census client is created", 0, clients)
        assertEquals(WipeProgress.Wiping(censusConfirmed = false), wipe.progress.value)
    }

    @Test fun `a census run holding the lock is waited for, never cleared beside`() = runTest {
        val uploader = Any()
        lock.mutex.lock(uploader)
        val wipe = wipe()
        wipe.start()
        settle(10 * 60_000)

        assertEquals(WipeProgress.WaitingForUpload, wipe.progress.value)
        assertTrue("nothing deleted while the upload runs", events.isEmpty())

        lock.mutex.unlock(uploader)
        settle()
        assertEquals(wiped, events)
    }

    @Test fun `Cancel while waiting for an upload deletes nothing and takes no lock`() = runTest {
        val uploader = Any()
        lock.mutex.lock(uploader)
        val wipe = wipe()
        wipe.start()
        settle()
        wipe.dismiss()
        settle()
        assertEquals(WipeProgress.Idle, wipe.progress.value)

        lock.mutex.unlock(uploader)
        settle()
        assertTrue(events.isEmpty())
        assertFalse(lock.mutex.isLocked)
    }

    @Test fun `enrollment is read under the lock, so a run that enrols while the wipe waits is asked about`() = runTest {
        val uploader = Any()
        lock.mutex.lock(uploader)
        val wipe = wipe()
        wipe.start()
        settle()
        installId.value = ENROLLED.installId // the run enrols this phone while the wipe waits
        lock.mutex.unlock(uploader)
        settle()

        assertEquals(listOf("delete request") + wiped, events)
    }

    @Test fun `a confirmed deletion shows the confirmation then wipes with the lock held throughout`() = runTest {
        installId.value = ENROLLED.installId
        val wipe = wipe()
        wipe.start()
        runCurrent()

        assertEquals(WipeProgress.Wiping(censusConfirmed = true), wipe.progress.value)
        assertTrue("committed before the notice", intentFile.isFile)
        assertTrue(lock.mutex.isLocked)
        assertEquals(listOf("delete request"), events)

        settle(PhoneWipe.CONFIRMED_NOTICE_MILLIS + 1)
        assertEquals(listOf("delete request") + wiped, events)
    }

    @Test fun `a dialog timeout leaves the request stored pending with its retry run queued`() = runTest {
        installId.value = ENROLLED.installId
        transport.answer = { delay(10 * 60_000); DeleteResult.NotAvailable(404) }
        val wipe = wipe()
        wipe.start()
        settle(PhoneWipe.CENSUS_WAIT_MILLIS + 1)

        assertEquals(WipeProgress.CensusNotConfirmed(WipeProgress.Reason.NO_ANSWER), wipe.progress.value)
        assertEquals(CensusDeletion.State.PENDING, preferences.censusDeletion.first()?.state)
        assertEquals("the retry run is queued before the network attempt", 1, scheduler.deletionRuns)
        assertTrue("the request still runs under the wipe's lock", lock.mutex.isLocked)

        wipe.dismiss() // Wait
        settle(PhoneWipe.CENSUS_WAIT_MILLIS)
        assertEquals(WipeProgress.Idle, wipe.progress.value)
        assertTrue("the lock is kept until the request ends", lock.mutex.isLocked)

        settle(10 * 60_000)
        assertFalse(lock.mutex.isLocked)
        assertEquals("Wait deletes nothing", listOf("delete request"), events)
        assertEquals(CensusDeletion.Waiting.NOT_YET, preferences.censusDeletion.first()?.waiting)
    }

    @Test fun `Delete now during a late request hands off to the coordinator that holds the lock`() = runTest {
        installId.value = ENROLLED.installId
        transport.answer = { delay(10 * 60_000); DeleteResult.Deleted(1, "2026-11-13") }
        val wipe = wipe()
        wipe.start()
        settle(PhoneWipe.CENSUS_WAIT_MILLIS + 1)

        wipe.wipeNow()
        runCurrent()
        assertEquals(WipeProgress.Wiping(censusConfirmed = false), wipe.progress.value)
        assertEquals(listOf("delete request") + wiped, events)
    }

    @Test fun `a late confirmation while the choice is shown goes on to the wipe`() = runTest {
        installId.value = ENROLLED.installId
        transport.answer = { delay(PhoneWipe.CENSUS_WAIT_MILLIS + 5_000); DeleteResult.Deleted(1, "2026-11-13") }
        val wipe = wipe()
        wipe.start()
        settle(PhoneWipe.CENSUS_WAIT_MILLIS + 1)
        assertEquals(WipeProgress.CensusNotConfirmed(WipeProgress.Reason.NO_ANSWER), wipe.progress.value)

        settle(5_000)
        assertEquals(WipeProgress.Wiping(censusConfirmed = true), wipe.progress.value)
        settle()
        assertEquals(listOf("delete request") + wiped, events)
    }

    @Test fun `a pending answer deletes nothing and names why`() = runTest {
        installId.value = ENROLLED.installId
        for ((result, reason) in listOf(
            DeleteResult.NotAvailable(404) to WipeProgress.Reason.NOT_YET,
            DeleteResult.TransportFailure("IOException") to WipeProgress.Reason.UNREACHABLE,
            DeleteResult.Unauthorized(null) to WipeProgress.Reason.UNCONFIRMED,
        )) {
            events.clear()
            transport.answer = { result }
            val wipe = wipe()
            wipe.start()
            settle()

            assertEquals(WipeProgress.CensusNotConfirmed(reason), wipe.progress.value)
            assertEquals(listOf("delete request"), events)
            assertTrue("the wipe keeps the lock while the dasher decides", lock.mutex.isLocked)
            wipe.dismiss()
            settle()
            assertEquals(WipeProgress.Idle, wipe.progress.value)
            assertFalse("Wait hands the lock back to the census runs", lock.mutex.isLocked)
        }
    }

    @Test fun `an unreadable credential store counts as enrolled, never as not enrolled`() = runTest {
        storeUnreadable = true
        credential = { throw CensusCredentialStore.Transient() }
        val wipe = wipe()
        assertTrue(wipe.censusEnrolled())
        wipe.start()
        settle()

        assertEquals(WipeProgress.CensusNotConfirmed(WipeProgress.Reason.UNREACHABLE), wipe.progress.value)
        assertTrue(events.isEmpty())
        assertEquals(CensusDeletion.State.PENDING, preferences.censusDeletion.first()?.state)
    }

    @Test fun `a census ID that can no longer sign offers Delete now without Wait`() = runTest {
        installId.value = ENROLLED.installId
        credential = { throw CensusCredentialStore.Unusable() }
        val wipe = wipe()
        wipe.start()
        settle()

        val progress = wipe.progress.value as WipeProgress.CensusNotConfirmed
        assertEquals(WipeProgress.Reason.NOT_POSSIBLE, progress.reason)
        assertFalse(progress.reason.canWait)
        wipe.wipeNow()
        settle()
        assertEquals(wiped, events)
    }

    @Test fun `Android refusing the clear reports it, drops the commitment and releases the lock`() = runTest {
        clearStarts = false
        val wipe = wipe()
        wipe.start()
        settle()

        assertEquals(WipeProgress.Failed, wipe.progress.value)
        assertFalse(lock.mutex.isLocked)
        assertFalse(intentFile.isFile)
        wipe.dismiss()
        assertEquals(WipeProgress.Idle, wipe.progress.value)
    }

    @Test fun `a wipe committed before the process died finishes at the next start, lock first`() = runTest {
        WipeIntent(intentFile).commit()
        val wipe = wipe()

        assertTrue(wipe.resumeCommitted())
        assertTrue("taken synchronously, before any census run can start", lock.mutex.isLocked)
        assertEquals(wiped, events)
        assertEquals(0, clients)
    }

    @Test fun `no commitment, no resume`() = runTest {
        val wipe = wipe()
        assertFalse(wipe.resumeCommitted())
        assertTrue(events.isEmpty())
        assertFalse(lock.mutex.isLocked)
    }

    @Test fun `a second start while one runs is ignored`() = runTest {
        installId.value = ENROLLED.installId
        transport.answer = { delay(1_000); DeleteResult.Deleted(1, "2026-11-13") }
        val wipe = wipe()
        wipe.start()
        wipe.start()
        settle()
        assertEquals(1, events.count { it == "delete request" })
        assertEquals(1, events.count { it.startsWith("clear") })
    }

    private companion object {
        val ENROLLED = CensusCredentialStore.Credential(
            "12345678-1234-4123-8123-123456789abc", InstallSecret.encode(ByteArray(32)), enrolled = true,
        )
    }
}
