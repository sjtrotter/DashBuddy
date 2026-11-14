package cloud.trotter.log.gig.core.data.census.contributor

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import cloud.trotter.census.contract.auth.Bearer
import cloud.trotter.log.gig.core.data.census.CensusCredentialStore
import cloud.trotter.log.gig.core.data.census.CensusDataDeletion
import cloud.trotter.log.gig.core.data.census.CensusUploadLock
import cloud.trotter.log.gig.core.data.census.KeystoreSealer
import cloud.trotter.log.gig.core.data.settings.DevSettingsRepository
import cloud.trotter.log.gig.core.datastore.census.ContributorDataSource
import cloud.trotter.log.gig.core.datastore.settings.DevSettingsDataSource
import cloud.trotter.log.gig.core.network.census.CensusApiFactory
import cloud.trotter.log.gig.core.network.census.CensusSentObserver
import cloud.trotter.log.gig.core.network.census.CensusTransport
import cloud.trotter.log.gig.core.network.census.DeleteResult
import cloud.trotter.log.gig.core.network.census.EnrolResult
import cloud.trotter.log.gig.core.network.census.GrantResult
import cloud.trotter.log.gig.core.network.census.HealthResult
import cloud.trotter.log.gig.core.network.census.PolicyResult
import cloud.trotter.log.gig.core.network.census.UploadResult
import cloud.trotter.log.gig.domain.capability.PrivacyDisclosure
import cloud.trotter.log.gig.domain.census.CensusDeletion
import cloud.trotter.log.gig.domain.census.contributor.ContributorEnd
import cloud.trotter.log.gig.domain.census.contributor.ContributorEntry
import cloud.trotter.log.gig.domain.census.contributor.GrantRefusal
import cloud.trotter.log.gig.domain.census.contributor.InvitationAnswer
import cloud.trotter.log.gig.domain.census.contributor.ShiftActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * #1625: the invitation, the grant request and the gate before contributor uploads. Eligibility itself is
 * [cloud.trotter.log.gig.domain.census.contributor.ContributorEligibility]'s (tested at each boundary there); here it is
 * a switch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class CensusContributorTest {
    private class MemoryPreferences : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(data.value).also { data.value = it }
    }

    private class XorSealer : KeystoreSealer() {
        override fun seal(bytes: ByteArray) = Sealed(byteArrayOf(1), bytes.map { (it.toInt() xor 85).toByte() }.toByteArray())
        override fun open(iv: ByteArray, ct: ByteArray) = ct.map { (it.toInt() xor 85).toByte() }.toByteArray()
        override fun reset() = Unit
    }

    private class MutableClock(var millis: Long) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = Instant.ofEpochMilli(millis)
    }

    /** Answers the grant routes only; any other census request fails the test. */
    private class GrantTransport : CensusTransport {
        val grantResults = ArrayDeque<GrantResult>()
        val stateResults = ArrayDeque<GrantResult>()
        var grantRequests = 0
        var stateReads = 0
        override suspend fun requestContributorGrant(cred: Bearer.Credential): GrantResult {
            grantRequests++
            return grantResults.removeFirst()
        }
        override suspend fun contributorGrant(cred: Bearer.Credential): GrantResult {
            stateReads++
            return stateResults.removeFirst()
        }
        override suspend fun enrol(cred: Bearer.Credential, appVersion: String, schemaIds: List<String>): EnrolResult = error("enrol")
        override suspend fun postHealth(cred: Bearer.Credential, reportsJson: List<String>): HealthResult = error("health")
        override suspend fun policy(): PolicyResult = error("policy")
        override suspend fun uploadSkeletons(cred: Bearer.Credential, batchId: String, itemsJson: List<String>): UploadResult = error("upload")
        override suspend fun uploadEnvelopes(cred: Bearer.Credential, batchId: String, itemsJson: List<String>): UploadResult = error("upload")
        override suspend fun deleteData(cred: Bearer.Credential): DeleteResult = error("delete")
    }

    private val start = Instant.parse("2026-11-13T20:00:00Z").toEpochMilli()
    private val day = 86_400_000L
    private val clock = MutableClock(start)
    private val transport = GrantTransport()
    private val shiftLive = MutableStateFlow(false)
    private var eligible = true
    private var pendingCleared = 0
    private val store = ContributorDataSource(MemoryPreferences())
    private val credentials = CensusCredentialStore(MemoryPreferences(), XorSealer())
    private val settingsSource = DevSettingsDataSource(MemoryPreferences())
    private var deletionClear = true

    private fun TestScope.contributor(): Pair<CensusContributor, DevSettingsRepository> {
        val settings = DevSettingsRepository(settingsSource, true, StandardTestDispatcher(testScheduler), "0.234.0")
        val deletion = mock<CensusDataDeletion> { on { resumePending() } doReturn deletionClear }
        val contributor = CensusContributor(
            store = store,
            eligibility = { _, _ -> eligible },
            shifts = object : ShiftActivity { override val shiftLive = this@CensusContributorTest.shiftLive },
            settings = settings,
            credentials = credentials,
            deletion = deletion,
            lock = CensusUploadLock(),
            apis = object : CensusApiFactory(CensusSentObserver.NONE) {
                override fun create(baseUrl: String): CensusTransport = transport
            },
            pendingReviews = { pendingCleared++ },
            scope = backgroundScope,
            appVersion = "0.234.0",
            clock = clock,
            zone = { ZoneOffset.UTC },
        )
        return contributor to settings
    }

    private suspend fun enrolled(settings: DevSettingsRepository): String {
        settings.setCensusUploadEnabled(true)
        val id = credentials.mint().installId
        credentials.markEnrolled()
        return id
    }

    private fun granted(expires: String = "2026-12-13") = GrantResult.Granted("2026-11-13", expires)

    @Test fun `accept sends one grant request and stores the grant for this census ID`() = runTest {
        val (contributor, settings) = contributor()
        val id = enrolled(settings)
        transport.grantResults += granted()

        assertEquals(GrantRequestOutcome.GRANTED, contributor.accept())

        assertEquals(1, transport.grantRequests)
        val record = store.record.first()
        assertEquals(id, record.grant?.installId)
        assertEquals("2026-12-13", record.grant?.expiresDay)
        assertTrue(record.receipt!!.granted)
        assertEquals(PrivacyDisclosure.REVISION, record.receipt!!.disclosureRevision)
        assertTrue(contributor.mayUpload())
        assertEquals(ContributorEntry.ON, contributor.status.first().entry)
    }

    @Test fun `install too new is stored and sends nothing more, not eligible likewise`() = runTest {
        val (contributor, settings) = contributor()
        enrolled(settings)
        transport.grantResults += GrantResult.InstallTooNew
        assertEquals(GrantRequestOutcome.INSTALL_TOO_NEW, contributor.accept())
        assertEquals(GrantRefusal.INSTALL_TOO_NEW, store.record.first().refusal)
        assertNull(store.record.first().grant)
        assertFalse(contributor.mayUpload())

        transport.grantResults += GrantResult.NotEligible
        assertEquals(GrantRequestOutcome.NOT_ELIGIBLE, contributor.accept())
        assertEquals(GrantRefusal.NOT_ELIGIBLE, store.record.first().refusal)
        assertFalse(contributor.mayUpload())
    }

    @Test fun `grant revoked on a request ends the grant held and clears pending reviews`() = runTest {
        val (contributor, settings) = contributor()
        enrolled(settings)
        transport.grantResults += granted()
        contributor.accept()
        transport.grantResults += GrantResult.GrantRevoked

        assertEquals(GrantRequestOutcome.GRANT_REVOKED, contributor.accept())

        val record = store.record.first()
        assertNull(record.grant)
        assertEquals(ContributorEnd.REVOKED, record.ended)
        assertEquals(GrantRefusal.GRANT_REVOKED, record.refusal)
        assertEquals(1, pendingCleared)
        assertFalse(contributor.mayUpload())
    }

    @Test fun `an unreachable server stores no grant`() = runTest {
        val (contributor, settings) = contributor()
        enrolled(settings)
        transport.grantResults += GrantResult.TransportFailure("IOException")
        assertEquals(GrantRequestOutcome.UNREACHABLE, contributor.accept())
        assertNull(store.record.first().grant)
        assertNull(store.record.first().refusal)
    }

    @Test fun `no grant request during a shift, when not eligible, or while a deletion waits`() = runTest {
        val (contributor, settings) = contributor()
        enrolled(settings)
        shiftLive.value = true
        assertEquals(GrantRequestOutcome.NOT_OPEN, contributor.accept())
        shiftLive.value = false
        eligible = false
        assertEquals(GrantRequestOutcome.NOT_OPEN, contributor.accept())
        eligible = true
        deletionClear = false
        assertEquals(GrantRequestOutcome.DELETION_PENDING, contributor().first.accept())
        assertEquals(0, transport.grantRequests)
    }

    @Test fun `expiry stops contributor uploads and clears pending reviews`() = runTest {
        val (contributor, settings) = contributor()
        enrolled(settings)
        transport.grantResults += granted(expires = "2026-11-15")
        contributor.accept()
        clock.millis = start + day // 2026-11-14
        assertTrue(contributor.mayUpload())

        clock.millis = start + 2 * day // 2026-11-15, the first day it no longer works
        assertFalse(contributor.mayUpload())

        val record = store.record.first()
        assertNull(record.grant)
        assertEquals(ContributorEnd.EXPIRED, record.ended)
        assertEquals(1, pendingCleared)
    }

    @Test fun `an expired grant asks again after the next shift, never renewing by itself`() = runTest {
        val (contributor, settings) = contributor()
        enrolled(settings)
        transport.grantResults += granted(expires = "2026-11-15")
        contributor.accept()
        clock.millis = start + 2 * day

        val offered = contributor.inviteAfterShift()

        assertEquals(ContributorEnd.EXPIRED, offered?.ended)
        assertNull(store.record.first().grant)
        assertEquals(1, transport.grantRequests)
    }

    @Test fun `a revoked grant on the state read stops uploads`() = runTest {
        val (contributor, settings) = contributor()
        enrolled(settings)
        transport.grantResults += granted()
        contributor.accept()
        transport.stateResults += GrantResult.GrantRevoked

        assertFalse(contributor.refresh())

        assertEquals(ContributorEnd.REVOKED, store.record.first().ended)
        assertFalse(contributor.mayUpload())
        assertEquals(1, pendingCleared)
    }

    @Test fun `the state read updates the held grant but never creates one`() = runTest {
        val (contributor, settings) = contributor()
        enrolled(settings)
        transport.grantResults += granted()
        contributor.accept()
        transport.stateResults += granted(expires = "2026-12-12")
        assertTrue(contributor.refresh())
        assertEquals("2026-12-12", store.record.first().grant?.expiresDay)

        contributor.stop()
        transport.stateResults += granted()
        assertFalse(contributor.refresh())

        assertNull(store.record.first().grant)
        assertEquals(1, transport.stateReads)
    }

    @Test fun `turning the census off ends the grant`() = runTest {
        val (contributor, settings) = contributor()
        enrolled(settings)
        transport.grantResults += granted()
        contributor.accept()

        settings.declineCensus()

        assertFalse(contributor.mayUpload())
        assertEquals(ContributorEnd.CENSUS_OFF, store.record.first().ended)
        assertEquals(1, pendingCleared)
    }

    @Test fun `a deletion request ends the grant`() = runTest {
        val (contributor, settings) = contributor()
        enrolled(settings)
        transport.grantResults += granted()
        contributor.accept()
        clock.millis += 1_000

        settings.setCensusDeletion(CensusDeletion(clock.millis, CensusDeletion.State.PENDING))

        assertFalse(contributor.mayUpload())
        assertEquals(ContributorEnd.DATA_DELETED, store.record.first().ended)
    }

    @Test fun `a new census ID ends the grant`() = runTest {
        val (contributor, settings) = contributor()
        enrolled(settings)
        transport.grantResults += granted()
        contributor.accept()

        credentials.wipe()

        assertFalse(contributor.mayUpload())
        assertEquals(ContributorEnd.ID_CHANGED, store.record.first().ended)
    }

    @Test fun `stop ends the grant as a No thanks with a declined receipt`() = runTest {
        val (contributor, settings) = contributor()
        enrolled(settings)
        transport.grantResults += granted()
        contributor.accept()

        contributor.stop()

        val record = store.record.first()
        assertNull(record.grant)
        assertEquals(ContributorEnd.STOPPED, record.ended)
        assertEquals(InvitationAnswer.NO_THANKS, record.answer)
        assertFalse(record.receipt!!.granted)
        assertEquals(1, pendingCleared)
        assertFalse(contributor.mayUpload())
        assertNull(contributor.inviteAfterShift())
    }

    @Test fun `the invitation is offered once after a shift, never during one`() = runTest {
        val (contributor, settings) = contributor()
        enrolled(settings)
        shiftLive.value = true
        assertNull(contributor.inviteAfterShift())
        assertEquals(ContributorEntry.HIDDEN, contributor.status.first().entry)

        shiftLive.value = false
        assertNotNull(contributor.inviteAfterShift())
        assertEquals(ContributorEntry.INVITED, contributor.status.first().entry)
        assertNull(contributor.inviteAfterShift())
    }

    @Test fun `a phone that is not eligible is never invited`() = runTest {
        val (contributor, settings) = contributor()
        enrolled(settings)
        eligible = false
        assertNull(contributor.inviteAfterShift())
        assertEquals(ContributorEntry.HIDDEN, contributor.status.first().entry)
    }

    @Test fun `Not now holds the invitation for 14 days, No thanks for good`() = runTest {
        val (contributor, settings) = contributor()
        enrolled(settings)
        contributor.inviteAfterShift()
        contributor.notNow()
        clock.millis = start + 14 * day - 1
        assertNull(contributor.inviteAfterShift())
        clock.millis = start + 14 * day
        assertNotNull(contributor.inviteAfterShift())

        contributor.noThanks()
        assertFalse(store.record.first().receipt!!.granted)
        clock.millis = start + 400 * day
        assertNull(contributor.inviteAfterShift())
        assertEquals(ContributorEntry.OFF, contributor.status.first().entry)
    }
}
