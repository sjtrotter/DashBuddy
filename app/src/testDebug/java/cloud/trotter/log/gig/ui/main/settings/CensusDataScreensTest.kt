package cloud.trotter.log.gig.ui.main.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import cloud.trotter.census.contract.auth.Bearer
import cloud.trotter.census.contract.auth.InstallSecret
import cloud.trotter.log.gig.core.data.census.CensusCredentialStore
import cloud.trotter.log.gig.core.data.census.CensusDataDeletion
import cloud.trotter.log.gig.core.data.census.CensusSentLog
import cloud.trotter.log.gig.core.data.census.CensusSpool
import cloud.trotter.log.gig.core.data.census.CensusUploadLock
import cloud.trotter.log.gig.core.data.census.PersistentHealthSink
import cloud.trotter.log.gig.core.data.settings.DevSettingsRepository
import cloud.trotter.log.gig.core.datastore.settings.DevSettingsDataSource
import cloud.trotter.log.gig.core.designsystem.theme.GigLogTheme
import cloud.trotter.log.gig.core.designsystem.time.LocalNowProvider
import cloud.trotter.log.gig.core.network.census.CensusApi
import cloud.trotter.log.gig.core.network.census.CensusApiFactory
import cloud.trotter.log.gig.core.network.census.CensusSentObserver
import cloud.trotter.log.gig.core.network.census.CensusTransport
import cloud.trotter.log.gig.core.network.census.DeleteResult
import cloud.trotter.log.gig.core.network.census.EnrolResult
import cloud.trotter.log.gig.core.network.census.HealthResult
import cloud.trotter.log.gig.core.network.census.PolicyResult
import cloud.trotter.log.gig.core.network.census.UploadResult
import cloud.trotter.log.gig.core.pipeline.census.NotificationSkeletonBuilder
import cloud.trotter.log.gig.core.pipeline.census.SkeletonBuilder
import cloud.trotter.log.gig.domain.capture.CensusEnvelopeSink
import cloud.trotter.log.gig.domain.capture.ReplayMetadata
import cloud.trotter.log.gig.domain.census.CensusSentBatch
import cloud.trotter.log.gig.domain.census.CensusUploadScheduler
import cloud.trotter.log.gig.domain.model.accessibility.UiNode
import cloud.trotter.log.gig.domain.model.notification.RawNotificationData
import cloud.trotter.log.gig.domain.state.Platform
import cloud.trotter.log.gig.feature.settings.CensusScreen
import cloud.trotter.log.gig.feature.settings.CensusSentScreen
import cloud.trotter.log.gig.feature.settings.CensusSentViewModel
import cloud.trotter.log.gig.feature.settings.CensusViewModel
import cloud.trotter.log.gig.feature.settings.R as SettingsR
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import java.util.TimeZone

/**
 * #1622: "Delete my census data" on the census screen (the confirmation says what is deleted and what survives; the
 * row then shows the server's confirmation) and "What gig.log sent", which renders the sent record the transport fills
 * from its own bytes, with the next upload's time left ticking on the test's clock.
 */
@RunWith(RobolectricTestRunner::class)
/* A tall window keeps every row of the lazy list composed. */
@Config(sdk = [35], qualifiers = "w411dp-h2000dp")
class CensusDataScreensTest {
    @get:Rule val compose = createComposeRule()

    private class MemoryPreferences : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(data.value).also { data.value = it }
    }

    /** Answers deletion only; any other call is a test failure. */
    private class DeletingTransport(var result: DeleteResult) : CensusTransport {
        var deletions = 0
        override suspend fun deleteData(cred: Bearer.Credential): DeleteResult = result.also { deletions++ }
        override suspend fun enrol(cred: Bearer.Credential, appVersion: String, schemaIds: List<String>): EnrolResult = error("enrol")
        override suspend fun postHealth(cred: Bearer.Credential, reportsJson: List<String>): HealthResult = error("health")
        override suspend fun policy(): PolicyResult = error("policy")
        override suspend fun uploadSkeletons(cred: Bearer.Credential, batchId: String, itemsJson: List<String>): UploadResult = error("upload")
        override suspend fun uploadEnvelopes(cred: Bearer.Credential, batchId: String, itemsJson: List<String>): UploadResult = error("upload")
    }

    private class MutableClock(var millis: Long) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = Instant.ofEpochMilli(millis)
        override fun millis(): Long = millis
    }

    private val context = RuntimeEnvironment.getApplication()
    private val repo = DevSettingsRepository(DevSettingsDataSource(MemoryPreferences()), false, Dispatchers.Unconfined, "0.234.0")
    private val credentials: CensusCredentialStore = mock()
    private val transport = DeletingTransport(DeleteResult.Deleted(rows = 12, deletedDay = "2026-11-13"))
    private val scheduler = object : CensusUploadScheduler {
        override fun enqueueNow(wifiOnly: Boolean, replaceQueued: Boolean) = Unit
        override fun scheduleDaily(runAtMillis: Long, wifiOnly: Boolean) = Unit
        override fun cancelManual() = Unit
        override fun enqueueDeletion() = Unit
    }
    private val deletion = CensusDataDeletion(
        credentials, mock<CensusSpool>(), mock<CensusEnvelopeSink>(), mock<PersistentHealthSink>(), repo, scheduler,
        CensusUploadLock(),
        object : CensusApiFactory(CensusSentObserver.NONE) {
            override fun create(baseUrl: String): CensusTransport = transport
        },
        CoroutineScope(Dispatchers.Unconfined),
    )
    private lateinit var locale: Locale
    private lateinit var timeZone: TimeZone

    @Before fun fixLocaleAndTimeZone() {
        locale = Locale.getDefault()
        timeZone = TimeZone.getDefault()
        Locale.setDefault(Locale.US)
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After fun restoreLocaleAndTimeZone() {
        Locale.setDefault(locale)
        TimeZone.setDefault(timeZone)
    }

    private fun string(id: Int) = context.getString(id)

    private fun showCensus() {
        val vm = CensusViewModel(repo, deletion, readsUnboundPlatforms = false)
        compose.setContent { GigLogTheme { CensusScreen(onBack = {}, onOpenSent = {}, viewModel = vm) } }
        compose.waitForIdle()
    }

    @Test fun `delete asks first, says what survives, then shows the server's confirmation`() {
        runBlocking { whenever(credentials.current()).thenReturn(ENROLLED) }
        showCensus()
        compose.onNodeWithText(string(SettingsR.string.census_delete_supporting)).assertExists()
        compose.onNodeWithText(string(SettingsR.string.census_delete_label)).performClick()

        compose.onNodeWithText(string(SettingsR.string.census_delete_dialog_title)).assertExists()
        compose.onNodeWithText("stored without this phone's ID", substring = true).assertExists()
        compose.onNodeWithText("at most 14 days later", substring = true).assertExists()
        compose.onNodeWithText("This doesn't turn the census off", substring = true).assertExists()
        assertEquals("nothing is sent before the confirmation", 0, transport.deletions)

        compose.onNodeWithText(string(SettingsR.string.census_delete_dialog_confirm)).performClick()
        compose.waitForIdle()

        assertEquals(1, transport.deletions)
        compose.onNodeWithText("The census server deleted 12 records on Nov 13, 2026.").assertExists()
    }

    @Test fun `cancel sends nothing`() {
        runBlocking { whenever(credentials.current()).thenReturn(ENROLLED) }
        showCensus()
        compose.onNodeWithText(string(SettingsR.string.census_delete_label)).performClick()
        compose.onNodeWithText(string(SettingsR.string.census_delete_dialog_cancel)).performClick()
        compose.waitForIdle()

        assertEquals(0, transport.deletions)
        compose.onNodeWithText(string(SettingsR.string.census_delete_dialog_title)).assertDoesNotExist()
        assertEquals(null, runBlocking { repo.censusDeletion.first() })
    }

    @Test fun `not enrolled says the server holds nothing`() {
        runBlocking { whenever(credentials.current()).thenReturn(null) }
        showCensus()
        compose.onNodeWithText(string(SettingsR.string.census_delete_label)).performClick()
        compose.onNodeWithText(string(SettingsR.string.census_delete_dialog_confirm)).performClick()
        compose.waitForIdle()

        assertEquals(0, transport.deletions)
        compose.onNodeWithText(string(SettingsR.string.census_delete_status_nothing)).assertExists()
    }

    @Test fun `an offline request says it is waiting to be sent`() {
        runBlocking { whenever(credentials.current()).thenReturn(ENROLLED) }
        transport.result = DeleteResult.TransportFailure("IOException")
        showCensus()
        compose.onNodeWithText(string(SettingsR.string.census_delete_label)).performClick()
        compose.onNodeWithText(string(SettingsR.string.census_delete_dialog_confirm)).performClick()
        compose.waitForIdle()

        compose.onNodeWithText(string(SettingsR.string.census_delete_status_pending)).assertExists()
    }

    @Test fun `before the server takes deletions, the row says the request waits, not that it failed`() {
        runBlocking { whenever(credentials.current()).thenReturn(ENROLLED) }
        transport.result = DeleteResult.NotAvailable(404)
        showCensus()
        compose.onNodeWithText(string(SettingsR.string.census_delete_label)).performClick()
        compose.onNodeWithText(string(SettingsR.string.census_delete_dialog_confirm)).performClick()
        compose.waitForIdle()

        compose.onNodeWithText(string(SettingsR.string.census_delete_status_not_yet)).assertExists()
    }

    @Test fun `the confirmation says what the server keeps`() {
        showCensus()
        compose.onNodeWithText(string(SettingsR.string.census_delete_label)).performClick()
        compose.onNodeWithText("keeps this phone's census ID", substring = true).assertExists()
        compose.onNodeWithText("which it keeps for 7 days", substring = true).assertExists()
    }

    @Test fun `the sent screen shows each item's kind, app and counts from the sent bytes, and the next upload ticks`() {
        val sentLog = CensusSentLog(context, Dispatchers.Unconfined)
        val body = CensusApi.batchBody("batch-1", listOf(screenJson(), notificationJson()))
        val expected = requireNotNull(CensusSentBatch.read(CensusSentBatch.SKELETONS, body, 0L)).items
        runBlocking {
            sentLog.record(CensusSentBatch.SKELETONS, body)
            repo.setCensusUploadEnabled(true)
            repo.setCensusNextRunAtMillis(T0 + 2 * 3_600_000L)
        }
        val clock = MutableClock(T0)
        val vm = CensusSentViewModel(sentLog, repo)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            GigLogTheme {
                CompositionLocalProvider(LocalNowProvider provides clock) { CensusSentScreen(onBack = {}, viewModel = vm) }
            }
        }
        compose.mainClock.advanceTimeBy(2_000)

        val screen = expected.single { it.kind.name == "SCREEN" }
        val notification = expected.single { it.kind.name == "NOTIFICATION" }
        assertTrue("the outline carries a node and a value", screen.nodes > 0 && screen.codes + screen.typeOnly > 0)
        compose.onNodeWithText("Screen, ${Platform.DoorDash.displayName}").assertExists()
        compose.onNodeWithText("Parts ${screen.nodes}, codes ${screen.codes}, formats ${screen.formats}, " +
            "type only ${screen.typeOnly}, left out ${screen.withheld}").assertExists()
        compose.onNodeWithText("Notification, ${Platform.DoorDash.displayName}").assertExists()
        compose.onNodeWithText("Codes ${notification.codes}, formats ${notification.formats}, " +
            "type only ${notification.typeOnly}, left out ${notification.withheld}").assertExists()
        compose.onNodeWithText("Screens 1, notifications 1, daily counts 0").assertExists()
        compose.onNodeWithText("in 2h 0m", substring = true).assertExists()

        clock.millis = T0 + 61_000
        compose.mainClock.advanceTimeBy(2_000)
        compose.onNodeWithText("in 1h 58m", substring = true).assertExists()
    }

    @Test fun `with the census off the sent screen says so and shows no next time`() {
        val vm = CensusSentViewModel(CensusSentLog(context, Dispatchers.Unconfined), repo)
        runBlocking { repo.setCensusNextRunAtMillis(T0 + 3_600_000L) }
        compose.setContent { GigLogTheme { CensusSentScreen(onBack = {}, viewModel = vm) } }
        compose.waitForIdle()

        compose.onNodeWithText(string(SettingsR.string.census_sent_next_off)).assertExists()
        compose.onNodeWithText(string(SettingsR.string.census_sent_empty)).assertExists()
        compose.onNodeWithText(string(SettingsR.string.census_sent_none)).assertExists()
    }

    private fun screenJson(): String = (SkeletonBuilder.outcome(
        UiNode(text = "Continue", children = listOf(UiNode(text = "$12.50"))), null,
        ReplayMetadata(engineVersion = 1), Platform.DoorDash, LocalDate.now(ZoneOffset.UTC),
    ) as SkeletonBuilder.Outcome.Built).json

    private fun notificationJson(): String = (NotificationSkeletonBuilder.outcome(
        RawNotificationData("Continue", null, null, null, packageName = Platform.DoorDash.packageName!!,
            postTime = 0, isClearable = true, channelId = "synthetic"),
        ReplayMetadata(engineVersion = 1), Platform.DoorDash, LocalDate.now(ZoneOffset.UTC),
    ) as NotificationSkeletonBuilder.Outcome.Built).json

    private companion object {
        const val T0 = 1_700_000_000_000L
        val ENROLLED = CensusCredentialStore.Credential(
            "12345678-1234-4123-8123-123456789abc", InstallSecret.encode(ByteArray(32)), enrolled = true,
        )
    }
}
