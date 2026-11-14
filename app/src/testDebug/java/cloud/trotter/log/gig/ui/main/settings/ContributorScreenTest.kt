package cloud.trotter.log.gig.ui.main.settings

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import cloud.trotter.census.contract.auth.Bearer
import cloud.trotter.census.contract.auth.InstallSecret
import cloud.trotter.log.gig.core.data.census.CensusCredentialStore
import cloud.trotter.log.gig.core.data.census.CensusDataDeletion
import cloud.trotter.log.gig.core.data.census.CensusUploadLock
import cloud.trotter.log.gig.core.data.census.contributor.CensusContributor
import cloud.trotter.log.gig.core.data.census.contributor.ContributorEligibilityReader
import cloud.trotter.log.gig.core.data.settings.DevSettingsRepository
import cloud.trotter.log.gig.core.database.analytics.ContributorActivityDao
import cloud.trotter.log.gig.core.database.analytics.ShiftSpanRow
import cloud.trotter.log.gig.core.datastore.census.ContributorDataSource
import cloud.trotter.log.gig.core.datastore.settings.DevSettingsDataSource
import cloud.trotter.log.gig.core.designsystem.theme.GigLogTheme
import cloud.trotter.log.gig.core.network.census.CensusApiFactory
import cloud.trotter.log.gig.core.network.census.CensusSentObserver
import cloud.trotter.log.gig.core.network.census.CensusTransport
import cloud.trotter.log.gig.core.network.census.DeleteResult
import cloud.trotter.log.gig.core.network.census.EnrolResult
import cloud.trotter.log.gig.core.network.census.GrantResult
import cloud.trotter.log.gig.core.network.census.HealthResult
import cloud.trotter.log.gig.core.network.census.PolicyResult
import cloud.trotter.log.gig.core.network.census.UploadResult
import cloud.trotter.log.gig.domain.census.contributor.ContributorEntry
import cloud.trotter.log.gig.domain.census.contributor.InvitationAnswer
import cloud.trotter.log.gig.domain.census.contributor.ShiftActivity
import cloud.trotter.log.gig.domain.census.CensusConsent
import cloud.trotter.log.gig.domain.state.Platform
import cloud.trotter.log.gig.feature.settings.CensusContent
import cloud.trotter.log.gig.feature.settings.CensusPlatformRow
import cloud.trotter.log.gig.feature.settings.CensusUiState
import cloud.trotter.log.gig.feature.settings.ContributorScreen
import cloud.trotter.log.gig.feature.settings.ContributorViewModel
import cloud.trotter.log.gig.feature.settings.R as SettingsR
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import java.util.TimeZone

/**
 * #1625: the reviewer screen over the real [CensusContributor]: the second disclosure says what reviewing means, Accept
 * sends the one grant request, Not now and No thanks are stored and close the screen, a shift hides Accept, a 403 is
 * shown as it is, and Stop ends the grant. The screen reads no time (the grant's day comes from the server), so these
 * tests need no Compose clock; the contributor's own clock is fixed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ContributorScreenTest {
    @get:Rule val compose = createComposeRule()

    private class MemoryPreferences : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(data.value).also { data.value = it }
    }

    private class FixedClock(private val millis: Long) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = Instant.ofEpochMilli(millis)
    }

    private class GrantTransport : CensusTransport {
        val results = ArrayDeque<GrantResult>()
        var requests = 0
        override suspend fun requestContributorGrant(cred: Bearer.Credential): GrantResult = results.removeFirst().also { requests++ }
        override suspend fun contributorGrant(cred: Bearer.Credential): GrantResult = error("state")
        override suspend fun enrol(cred: Bearer.Credential, appVersion: String, schemaIds: List<String>): EnrolResult = error("enrol")
        override suspend fun postHealth(cred: Bearer.Credential, reportsJson: List<String>): HealthResult = error("health")
        override suspend fun policy(): PolicyResult = error("policy")
        override suspend fun uploadSkeletons(cred: Bearer.Credential, batchId: String, itemsJson: List<String>): UploadResult = error("upload")
        override suspend fun uploadEnvelopes(cred: Bearer.Credential, batchId: String, itemsJson: List<String>): UploadResult = error("upload")
        override suspend fun deleteData(cred: Bearer.Credential): DeleteResult = error("delete")
    }

    private val now = Instant.parse("2026-11-13T20:00:00Z").toEpochMilli()
    private val day = 86_400_000L
    private val context = RuntimeEnvironment.getApplication()
    private val repo = DevSettingsRepository(DevSettingsDataSource(MemoryPreferences()), false, Dispatchers.Unconfined, "0.234.0")
    private val store = ContributorDataSource(MemoryPreferences())
    private val transport = GrantTransport()
    private val shiftLive = MutableStateFlow(false)
    private val credentials: CensusCredentialStore = mock()
    private val activity: ContributorActivityDao = mock()
    private val deletion: CensusDataDeletion = mock()
    private var closed = 0
    private lateinit var locale: Locale
    private lateinit var timeZone: TimeZone

    @Before fun setUp() {
        locale = Locale.getDefault()
        timeZone = TimeZone.getDefault()
        Locale.setDefault(Locale.US)
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        runBlocking {
            repo.setCensusUploadEnabled(true)
            whenever(credentials.current()).thenReturn(ENROLLED)
            whenever(credentials.installId).thenReturn(flowOf(ENROLLED.installId))
            whenever(credentials.enrolledAtMillis(any())).thenReturn(now - 10 * day)
            whenever(activity.shiftsSince(any())).thenReturn((0..2).map { ShiftSpanRow(now - it * day - 7_200_000L, now - it * day) })
            whenever(activity.endedShiftCount()).thenReturn(3)
            whenever(deletion.resumePending()).thenReturn(true)
        }
    }

    @After fun tearDown() {
        Locale.setDefault(locale)
        TimeZone.setDefault(timeZone)
    }

    private fun string(id: Int) = context.getString(id)

    private fun show() {
        val contributor = CensusContributor(
            store, ContributorEligibilityReader(repo, credentials, activity),
            object : ShiftActivity { override val shiftLive = this@ContributorScreenTest.shiftLive },
            repo, credentials, deletion, CensusUploadLock(),
            object : CensusApiFactory(CensusSentObserver.NONE) {
                override fun create(baseUrl: String): CensusTransport = transport
            },
            {}, CoroutineScope(Dispatchers.Unconfined), "0.234.0", FixedClock(now),
        )
        compose.setContent { GigLogTheme { ContributorScreen(onBack = { closed++ }, viewModel = ContributorViewModel(contributor)) } }
        compose.waitForIdle()
    }

    private fun text(substring: String) = compose.onNodeWithText(substring, substring = true)
    private fun record() = runBlocking { store.record.first() }

    @Test fun `the disclosure says what reviewing means and Accept sends one request and shows the grant`() {
        show()
        text(string(SettingsR.string.census_review_heading)).assertExists()
        text("at most 3 a day").assertExists()
        text("covered before you see a screen").assertExists()
        text("label what a field is").assertExists()
        text("only after other drivers confirm the same word").assertExists()
        text("Ask the developer sends").assertExists()
        text("never your shift counts").assertExists()
        text("tied to your reviewer permission, for up to 30 days").assertExists()
        text("stop at any time").assertExists()
        text("between you and that company").assertExists()
        assertEquals("reading the disclosure sends nothing", 0, transport.requests)

        transport.results += GrantResult.Granted("2026-11-13", "2026-12-13")
        compose.onNodeWithText(string(SettingsR.string.census_review_accept)).performClick()
        compose.waitForIdle()

        assertEquals(1, transport.requests)
        text("Reviewing is on. The permission ends Dec 13, 2026.").assertExists()
        compose.onNodeWithText(string(SettingsR.string.census_review_stop)).assertExists()
        compose.onNodeWithText(string(SettingsR.string.census_review_accept)).assertDoesNotExist()
    }

    @Test fun `Not now is stored and closes the screen`() {
        show()
        compose.onNodeWithText(string(SettingsR.string.consent_front_door_not_now)).performClick()
        compose.waitForIdle()
        assertEquals(1, closed)
        assertEquals(InvitationAnswer.NOT_NOW, record().answer)
        assertEquals(0, transport.requests)
    }

    @Test fun `No thanks is stored with a declined receipt and closes the screen`() {
        show()
        compose.onNodeWithText(string(SettingsR.string.census_review_no_thanks)).performClick()
        compose.waitForIdle()
        assertEquals(1, closed)
        assertEquals(InvitationAnswer.NO_THANKS, record().answer)
        assertTrue(record().receipt?.granted == false)
        assertEquals(0, transport.requests)
    }

    @Test fun `during a shift there is no Accept`() {
        shiftLive.value = true
        show()
        compose.onNodeWithText(string(SettingsR.string.census_review_accept)).assertDoesNotExist()
        text(string(SettingsR.string.census_review_status_shift)).assertExists()
    }

    @Test fun `install too new is shown as the server said it`() {
        show()
        transport.results += GrantResult.InstallTooNew
        compose.onNodeWithText(string(SettingsR.string.census_review_accept)).performClick()
        compose.waitForIdle()
        text(string(SettingsR.string.census_review_status_too_new)).assertExists()
        assertEquals(null, record().grant)
    }

    @Test fun `a revoked grant is shown as the server said it`() {
        show()
        transport.results += GrantResult.GrantRevoked
        compose.onNodeWithText(string(SettingsR.string.census_review_accept)).performClick()
        compose.waitForIdle()
        text(string(SettingsR.string.census_review_status_revoked)).assertExists()
    }

    @Test fun `Stop reviewing ends the grant and leaves Accept to start again`() {
        show()
        transport.results += GrantResult.Granted("2026-11-13", "2026-12-13")
        compose.onNodeWithText(string(SettingsR.string.census_review_accept)).performClick()
        compose.waitForIdle()

        compose.onNodeWithText(string(SettingsR.string.census_review_stop)).performClick()
        compose.waitForIdle()

        assertEquals(null, record().grant)
        text(string(SettingsR.string.census_review_status_off)).assertExists()
        compose.onNodeWithText(string(SettingsR.string.census_review_accept)).assertExists()
        compose.onNodeWithText(string(SettingsR.string.consent_front_door_not_now)).assertDoesNotExist()
    }

    @Test fun `the census screen shows the invitation row and opens the reviewer screen`() {
        var opened = 0
        compose.setContent {
            GigLogTheme {
                CensusContent(
                    state = CensusUiState(
                        consent = CensusConsent.ON,
                        platforms = listOf(CensusPlatformRow(Platform.DoorDash, true)),
                        wifiOnly = true, serverHost = "census.example", testBuild = false,
                        reviewer = ContributorEntry.INVITED,
                    ),
                    onBack = {}, onAccept = {}, onDecline = {}, onWifiOnly = {}, onPlatform = { _, _ -> },
                    onOpenSent = {}, onDelete = {}, onOpenReviewer = { opened++ },
                )
            }
        }
        compose.onNodeWithText(string(SettingsR.string.census_review_row_invited)).performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(1, opened)
    }

    private companion object {
        val ENROLLED = CensusCredentialStore.Credential(
            "12345678-1234-4123-8123-123456789abc", InstallSecret.encode(ByteArray(32)), enrolled = true,
        )
    }
}
