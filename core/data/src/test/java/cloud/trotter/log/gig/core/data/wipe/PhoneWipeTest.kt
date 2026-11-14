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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
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
 * #1579: [PhoneWipe]'s actor over [WipeFlow], #1622's real [CensusDataDeletion] and a transport the test answers.
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
    private var held: Flow<Boolean> = flowOf(false)
    private var credential: suspend () -> CensusCredentialStore.Credential? = { ENROLLED.takeIf { installId.value != null } }
    private val credentials: CensusCredentialStore = mock()
    private val intentFile by lazy { File(tmp.root, WipeIntent.FILE_NAME) }
    private var intent: () -> WipeIntent = { WipeIntent(intentFile) }
    /** Completed to let the enrollment read return; null reads at once. */
    private var enrollmentGate: CompletableDeferred<Unit>? = null
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
        val ids: Flow<String?> = flow {
            enrollmentGate?.await()
            if (storeUnreadable) error("unreadable") else emit(installId.value)
        }
        whenever(credentials.installId).thenReturn(ids)
        whenever(credentials.held).thenReturn(held)
        runBlocking { whenever(credentials.current()).doSuspendableAnswer { credential() } }
        val deletion = CensusDataDeletion(
            credentials, mock<CensusSpool>(), mock<CensusEnvelopeSink>(), mock<PersistentHealthSink>(), preferences,
            scheduler, lock,
            object : CensusApiFactory(CensusSentObserver.NONE) {
                override fun create(baseUrl: String): CensusTransport = transport.also { clients++ }
            },
            backgroundScope, "test",
        )
        return PhoneWipe(credentials, deletion, preferences, lock, sealer, clearer, intent(), backgroundScope)
    }

    /** The wipe runs on the background (application) scope, which advanceUntilIdle does not wait for. */
    private fun TestScope.settle(millis: Long = 60_000) {
        advanceTimeBy(millis)
        runCurrent()
    }

    private fun TestScope.confirmed(): PhoneWipe = wipe().apply {
        open()
        runCurrent()
        confirm()
    }

    private val wiped = listOf("key locked=true", "clear locked=true committed=true")

    private fun choosing(reason: WipeFlow.Reason, requestOpen: Boolean = false) = WipeFlow.State.Choosing(reason, requestOpen)

    @Test fun `a phone with no census ID wipes under the lock without a network call`() = runTest {
        val wipe = confirmed()
        settle()

        assertEquals(wiped, events)
        assertEquals("no census client is created", 0, clients)
        assertEquals(WipeFlow.State.Done, wipe.progress.value)
    }

    @Test fun `Cancel on the confirmation ends the flow with nothing held`() = runTest {
        val wipe = wipe()
        wipe.open()
        runCurrent()
        assertEquals(WipeFlow.State.Confirming, wipe.progress.value)
        wipe.cancel()
        settle()
        assertEquals(null, wipe.progress.value)
        assertTrue(events.isEmpty())
        assertFalse(lock.mutex.isLocked)
    }

    @Test fun `a census run holding the lock is waited for, never cleared beside`() = runTest {
        val uploader = Any()
        lock.mutex.lock(uploader)
        val wipe = confirmed()
        settle(10 * 60_000)

        assertEquals(WipeFlow.State.WaitingForLock, wipe.progress.value)
        assertTrue("nothing deleted while the upload runs", events.isEmpty())

        lock.mutex.unlock(uploader)
        settle()
        assertEquals(wiped, events)
    }

    @Test fun `Cancel while waiting for an upload deletes nothing and takes no lock`() = runTest {
        val uploader = Any()
        lock.mutex.lock(uploader)
        val wipe = confirmed()
        settle()
        wipe.cancel()
        settle()
        assertEquals(null, wipe.progress.value)

        lock.mutex.unlock(uploader)
        settle()
        assertTrue(events.isEmpty())
        assertFalse(lock.mutex.isLocked)
    }

    @Test fun `Cancel during a suspended enrollment read after the lock is final`() = runTest {
        enrollmentGate = CompletableDeferred()
        val wipe = confirmed()
        settle()
        assertEquals(WipeFlow.State.ReadingEnrollment, wipe.progress.value)
        assertTrue(lock.mutex.isLocked)

        wipe.cancel()
        settle()
        enrollmentGate!!.complete(Unit) // the read answers after the cancel was accepted
        settle()
        assertEquals(null, wipe.progress.value)
        assertTrue("no wipe after an accepted cancel", events.isEmpty())
        assertFalse(intentFile.isFile)
        assertFalse(lock.mutex.isLocked)
    }

    @Test fun `enrollment is read under the lock, so a run that enrols while the wipe waits is asked about`() = runTest {
        val uploader = Any()
        lock.mutex.lock(uploader)
        confirmed()
        settle()
        installId.value = ENROLLED.installId // the run enrols this phone while the wipe waits
        lock.mutex.unlock(uploader)
        settle()

        assertEquals(listOf("delete request") + wiped, events)
    }

    @Test fun `a confirmed deletion is committed, shown, then wiped with the lock held throughout`() = runTest {
        installId.value = ENROLLED.installId
        val wipe = confirmed()
        runCurrent()

        assertEquals(WipeFlow.State.Wiping(censusConfirmed = true, requestOpen = false), wipe.progress.value)
        assertTrue("committed before Wiping is shown", intentFile.isFile)
        assertTrue(lock.mutex.isLocked)
        assertEquals(listOf("delete request"), events)

        settle(PhoneWipe.CONFIRMED_NOTICE_MILLIS + 1)
        assertEquals(listOf("delete request") + wiped, events)
    }

    @Test fun `a commitment that can't be written deletes nothing and says so`() = runTest {
        intent = { WipeIntent(File(tmp.root, "missing/${WipeIntent.FILE_NAME}")) }
        val wipe = confirmed()
        settle()

        assertEquals(WipeFlow.State.Failed(WipeFlow.Failure.COMMIT, requestOpen = false), wipe.progress.value)
        assertTrue("no key and no storage deleted", events.isEmpty())
        assertFalse(lock.mutex.isLocked)
        wipe.cancel()
        settle()
        assertEquals(null, wipe.progress.value)
    }

    @Test fun `a dialog timeout leaves the request stored pending with its retry run queued`() = runTest {
        installId.value = ENROLLED.installId
        transport.answer = { delay(10 * 60_000); DeleteResult.NotAvailable(404) }
        val wipe = confirmed()
        settle(PhoneWipe.CENSUS_WAIT_MILLIS + 1)

        assertEquals(choosing(WipeFlow.Reason.NO_ANSWER, requestOpen = true), wipe.progress.value)
        assertEquals(CensusDeletion.State.PENDING, preferences.censusDeletion.first()?.state)
        assertEquals("the retry run is queued before the network attempt", 1, scheduler.deletionRuns)
        assertTrue("the request still runs under the wipe's lock", lock.mutex.isLocked)

        wipe.waitForServer()
        settle(PhoneWipe.CENSUS_WAIT_MILLIS)
        assertEquals(null, wipe.progress.value)
        assertTrue("the lock is kept until the request ends", lock.mutex.isLocked)

        settle(10 * 60_000)
        assertFalse(lock.mutex.isLocked)
        assertEquals("Wait deletes nothing", listOf("delete request"), events)
        assertEquals(CensusDeletion.Waiting.NOT_YET, preferences.censusDeletion.first()?.waiting)
    }

    @Test fun `an answer at the very instant of the timeout is still delivered`() = runTest {
        installId.value = ENROLLED.installId
        transport.answer = { delay(PhoneWipe.CENSUS_WAIT_MILLIS); DeleteResult.Deleted(1, "2026-11-13") }
        val wipe = confirmed()
        settle(PhoneWipe.CENSUS_WAIT_MILLIS)
        assertEquals(WipeFlow.State.Wiping(censusConfirmed = true, requestOpen = false), wipe.progress.value)
        settle()
        assertEquals(listOf("delete request") + wiped, events)
    }

    @Test fun `Delete now during a late request goes to the actor that holds the lock`() = runTest {
        installId.value = ENROLLED.installId
        transport.answer = { delay(10 * 60_000); DeleteResult.Deleted(1, "2026-11-13") }
        val wipe = confirmed()
        settle(PhoneWipe.CENSUS_WAIT_MILLIS + 1)

        wipe.deleteNow()
        runCurrent()
        assertEquals(WipeFlow.State.Done, wipe.progress.value)
        assertEquals(listOf("delete request") + wiped, events)
    }

    @Test fun `a refused clear during a late request keeps the lock until the request ends`() = runTest {
        installId.value = ENROLLED.installId
        clearStarts = false
        transport.answer = { delay(10 * 60_000); DeleteResult.NotAvailable(404) }
        val wipe = confirmed()
        settle(PhoneWipe.CENSUS_WAIT_MILLIS + 1)
        wipe.deleteNow()
        runCurrent()

        assertEquals(WipeFlow.State.Failed(WipeFlow.Failure.CLEAR, requestOpen = true), wipe.progress.value)
        assertFalse("the commitment is dropped", intentFile.isFile)
        assertTrue("never released while the request runs", lock.mutex.isLocked)
        settle(10 * 60_000)
        assertFalse(lock.mutex.isLocked)
        assertEquals(WipeFlow.State.Failed(WipeFlow.Failure.CLEAR, requestOpen = false), wipe.progress.value)
    }

    @Test fun `a late confirmation while the choice is shown goes on to the wipe`() = runTest {
        installId.value = ENROLLED.installId
        transport.answer = { delay(PhoneWipe.CENSUS_WAIT_MILLIS + 5_000); DeleteResult.Deleted(1, "2026-11-13") }
        val wipe = confirmed()
        settle(PhoneWipe.CENSUS_WAIT_MILLIS + 1)
        assertEquals(choosing(WipeFlow.Reason.NO_ANSWER, requestOpen = true), wipe.progress.value)

        settle(5_000)
        assertEquals(WipeFlow.State.Wiping(censusConfirmed = true, requestOpen = false), wipe.progress.value)
        settle()
        assertEquals(listOf("delete request") + wiped, events)
    }

    @Test fun `a pending answer deletes nothing, names why, and Wait hands the lock back`() = runTest {
        installId.value = ENROLLED.installId
        for ((result, reason) in listOf(
            DeleteResult.NotAvailable(404) to WipeFlow.Reason.NOT_YET,
            DeleteResult.TransportFailure("IOException") to WipeFlow.Reason.UNREACHABLE,
            DeleteResult.Unauthorized(null) to WipeFlow.Reason.UNCONFIRMED,
        )) {
            events.clear()
            transport.answer = { result }
            val wipe = confirmed()
            settle()

            assertEquals(choosing(reason), wipe.progress.value)
            assertEquals(listOf("delete request"), events)
            assertTrue("the wipe keeps the lock while the dasher decides", lock.mutex.isLocked)
            wipe.waitForServer()
            settle()
            assertEquals(null, wipe.progress.value)
            assertFalse(lock.mutex.isLocked)
        }
    }

    @Test fun `an unreadable credential store counts as enrolled, never as not enrolled`() = runTest {
        storeUnreadable = true
        credential = { throw CensusCredentialStore.Transient() }
        confirmed().also { wipe ->
            settle()
            assertEquals(choosing(WipeFlow.Reason.UNREACHABLE), wipe.progress.value)
        }
        assertTrue(events.isEmpty())
        assertEquals(CensusDeletion.State.PENDING, preferences.censusDeletion.first()?.state)
    }

    @Test fun `a census ID that can no longer sign offers Delete now without Wait`() = runTest {
        installId.value = ENROLLED.installId
        credential = { throw CensusCredentialStore.Unusable() }
        val wipe = confirmed()
        settle()

        val progress = wipe.progress.value as WipeFlow.State.Choosing
        assertEquals(WipeFlow.Reason.NOT_POSSIBLE, progress.reason)
        assertFalse(progress.reason.canWait)
        wipe.deleteNow()
        settle()
        assertEquals(wiped, events)
    }

    @Test fun `Android refusing the clear reports it, drops the commitment and releases the lock`() = runTest {
        clearStarts = false
        val wipe = confirmed()
        settle()

        assertEquals(WipeFlow.State.Failed(WipeFlow.Failure.CLEAR, requestOpen = false), wipe.progress.value)
        assertFalse(lock.mutex.isLocked)
        assertFalse(intentFile.isFile)
        wipe.cancel()
        settle()
        assertEquals(null, wipe.progress.value)
    }

    @Test fun `the confirmation discloses the census whenever a request could be sent`() = runTest {
        val wipe = wipe()
        assertFalse(wipe.censusDisclosed.first())
        preferences.setCensusUploadEnabled(true) // an upload could enrol the phone before the wipe takes the lock
        assertTrue(wipe.censusDisclosed.first())
        preferences.setCensusUploadEnabled(false)
        held = flowOf(true)
        assertTrue("a stored identity", wipe().censusDisclosed.first())
        held = flow { error("unreadable") }
        assertTrue("an unreadable store", wipe().censusDisclosed.first())
    }

    @Test fun `a wipe committed before the process died finishes at the next start, lock first`() = runTest {
        WipeIntent(intentFile).commit()
        val wipe = wipe()

        assertTrue(wipe.resumeCommitted { error("the app must not start") })
        assertTrue("taken synchronously, before any census run can start", lock.mutex.isLocked)
        assertEquals(wiped, events)
        assertEquals(0, clients)
    }

    @Test fun `a refused clear at the next start is shown and holds everything until OK`() = runTest {
        WipeIntent(intentFile).commit()
        clearStarts = false
        var started = 0
        val wipe = wipe()

        assertTrue("the app starts nothing yet", wipe.resumeCommitted { started++ })
        assertEquals(WipeFlow.State.Failed(WipeFlow.Failure.CLEAR, requestOpen = false), wipe.progress.value)
        assertTrue("no census upload before the dasher has seen it", lock.mutex.isLocked)
        assertTrue("the next start tries again", intentFile.isFile)
        assertEquals(0, started)
        wipe.open()
        assertEquals("the failure stays on screen", WipeFlow.State.Failed(WipeFlow.Failure.CLEAR, false), wipe.progress.value)

        wipe.cancel() // OK
        assertEquals(1, started)
        assertEquals(null, wipe.progress.value)
        assertFalse(lock.mutex.isLocked)
        assertFalse(intentFile.isFile)
    }

    @Test fun `OK on a refused resume starts the app only once the commitment is gone`() = runTest {
        WipeIntent(intentFile).commit()
        clearStarts = false
        var removable = false
        intent = { WipeIntent(intentFile, remove = { file -> removable && file.delete() }) }
        var started = 0
        val wipe = wipe()
        assertTrue(wipe.resumeCommitted { started++ })

        wipe.cancel() // OK, but the commitment can't be removed
        assertEquals(WipeFlow.State.Failed(WipeFlow.Failure.CLEAR, false, commitmentStuck = true), wipe.progress.value)
        assertEquals("startup stays paused", 0, started)
        assertTrue("the lock stays held", lock.mutex.isLocked)
        assertTrue(intentFile.isFile)

        removable = true
        wipe.cancel() // Try again
        assertEquals(1, started)
        assertEquals(null, wipe.progress.value)
        assertFalse(lock.mutex.isLocked)
        assertFalse(intentFile.exists())
        wipe.cancel()
        assertEquals("starts once", 1, started)
    }

    @Test fun `a refused clear whose commitment can't be removed stays until a retry removes it`() = runTest {
        clearStarts = false
        var removable = false
        intent = { WipeIntent(intentFile, remove = { file -> removable && file.delete() }) }
        val wipe = confirmed()
        settle()
        assertEquals(WipeFlow.State.Failed(WipeFlow.Failure.CLEAR, false, commitmentStuck = true), wipe.progress.value)
        assertTrue(intentFile.isFile)

        wipe.cancel() // Try again, still failing
        settle()
        assertEquals(WipeFlow.State.Failed(WipeFlow.Failure.CLEAR, false, commitmentStuck = true), wipe.progress.value)
        removable = true
        wipe.cancel()
        settle()
        assertEquals(null, wipe.progress.value)
        assertFalse(intentFile.exists())
    }

    @Test fun `a commitment that fails after its file was created leaves nothing for the next start`() = runTest {
        for (failure in listOf("write", "sync")) {
            val broken = WipeIntent(intentFile, write = { out, bytes ->
                if (failure == "write") throw java.io.IOException("disk full")
                out.write(bytes)
                throw java.io.SyncFailedException("sync")
            })
            assertTrue(runCatching { broken.commit() }.isFailure)
            assertFalse("$failure: no final marker", intentFile.exists())
            assertFalse("$failure: no temporary file", File(tmp.root, "${WipeIntent.FILE_NAME}.tmp").exists())
            assertFalse(wipe().resumeCommitted { })
            assertTrue(events.isEmpty())
        }
    }

    @Test fun `only a whole commitment counts`() = runTest {
        intentFile.writeText("")
        assertFalse(WipeIntent(intentFile).isCommitted())
        assertFalse(wipe().resumeCommitted { })
        WipeIntent(intentFile).commit()
        assertTrue(WipeIntent(intentFile).isCommitted())
    }

    @Test fun `no commitment, no resume`() = runTest {
        val wipe = wipe()
        assertFalse(wipe.resumeCommitted { error("no resume, so no deferred start") })
        assertTrue(events.isEmpty())
        assertFalse(lock.mutex.isLocked)
    }

    @Test fun `a second confirm while one runs is ignored`() = runTest {
        installId.value = ENROLLED.installId
        transport.answer = { delay(1_000); DeleteResult.Deleted(1, "2026-11-13") }
        val wipe = confirmed()
        wipe.confirm()
        wipe.open()
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
