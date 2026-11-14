package cloud.trotter.log.gig.ui.main.settings

import androidx.compose.ui.test.hasText
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
import cloud.trotter.log.gig.core.data.census.CensusSpool
import cloud.trotter.log.gig.core.data.census.CensusUploadLock
import cloud.trotter.log.gig.core.data.census.KeystoreSealer
import cloud.trotter.log.gig.core.data.census.PersistentHealthSink
import cloud.trotter.log.gig.core.data.settings.DevSettingsRepository
import cloud.trotter.log.gig.core.data.wipe.AppStorageClearer
import cloud.trotter.log.gig.core.data.wipe.PhoneWipe
import cloud.trotter.log.gig.core.datastore.settings.DevSettingsDataSource
import cloud.trotter.log.gig.core.designsystem.theme.GigLogTheme
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
import cloud.trotter.log.gig.feature.settings.R as SettingsR
import cloud.trotter.log.gig.feature.settings.WipePhoneHost
import cloud.trotter.log.gig.feature.settings.WipePhoneViewModel
import cloud.trotter.log.gig.ui.ScreenshotData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * #1579: Settings > Data & privacy > Delete everything on this phone, end to end below the OS: the real
 * [WipePhoneViewModel], [PhoneWipe] and #1622's [CensusDataDeletion] over a transport that counts requests, with
 * Android's clear and the Keystore replaced by recorders. The confirmation says what goes and what stays; an enrolled
 * phone asks the census server first and wipes on its confirmation; a pending deletion offers Delete now or Wait, and
 * Wait deletes nothing; a phone with no census ID sends nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h2000dp")
class WipePhoneDialogTest {
    @get:Rule val compose = createComposeRule()

    private class MemoryPreferences : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(data.value).also { data.value = it }
    }

    /** Answers deletion only; any other call is a test failure. */
    private class DeletingTransport(var result: DeleteResult) : CensusTransport {
        @Volatile var deletions = 0
        override suspend fun deleteData(cred: Bearer.Credential): DeleteResult = result.also { deletions++ }
        override suspend fun enrol(cred: Bearer.Credential, appVersion: String, schemaIds: List<String>): EnrolResult = error("enrol")
        override suspend fun postHealth(cred: Bearer.Credential, reportsJson: List<String>): HealthResult = error("health")
        override suspend fun policy(): PolicyResult = error("policy")
        override suspend fun uploadSkeletons(cred: Bearer.Credential, batchId: String, itemsJson: List<String>): UploadResult = error("upload")
        override suspend fun uploadEnvelopes(cred: Bearer.Credential, batchId: String, itemsJson: List<String>): UploadResult = error("upload")
    }

    private val context = RuntimeEnvironment.getApplication()
    private val repo = DevSettingsRepository(DevSettingsDataSource(MemoryPreferences()), false, Dispatchers.Unconfined, "0.234.0")
    private val credentials: CensusCredentialStore = mock()
    private val transport = DeletingTransport(DeleteResult.Deleted(rows = 12, deletedDay = "2026-11-13"))
    @Volatile private var networkClients = 0
    private val scheduler = object : CensusUploadScheduler {
        override fun enqueueNow(wifiOnly: Boolean, replaceQueued: Boolean) = Unit
        override fun scheduleDaily(runAtMillis: Long, wifiOnly: Boolean) = Unit
        override fun cancelManual() = Unit
        override fun enqueueDeletion() = Unit
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = CensusUploadLock()
    private val deletion = CensusDataDeletion(
        credentials, mock<CensusSpool>(), mock<CensusEnvelopeSink>(), mock<PersistentHealthSink>(), repo, scheduler,
        lock,
        object : CensusApiFactory(CensusSentObserver.NONE) {
            override fun create(baseUrl: String): CensusTransport = transport.also { networkClients++ }
        },
        scope,
        "0.234.0",
    )
    /** Written from the wipe's coroutine, read by the test thread. */
    private val wiped: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
    private val sealer = object : KeystoreSealer() {
        override fun reset() {
            wiped += "census key"
        }
    }
    private val clearer = AppStorageClearer { wiped += "app storage"; true }

    private fun string(id: Int) = context.getString(id)
    private fun text(id: Int) = compose.onNodeWithText(string(id))

    private fun show(enrolled: Boolean) {
        whenever(credentials.installId).thenReturn(flowOf(ENROLLED.installId.takeIf { enrolled }))
        runBlocking { whenever(credentials.current()).thenReturn(ENROLLED.takeIf { enrolled }) }
        val vm = WipePhoneViewModel(PhoneWipe(credentials, deletion, lock, sealer, clearer, scope))
        compose.setContent {
            GigLogTheme {
                SettingsHomeContent(
                    isDevUnlocked = false, garage = ScreenshotData.garage, userEconomy = ScreenshotData.userEconomy,
                    onNavigate = {}, onBack = {}, onDeleteEverything = vm::open,
                )
                WipePhoneHost(vm)
            }
        }
        compose.waitForIdle()
    }

    private fun openConfirmation() {
        text(SettingsR.string.wipe_label).performClick()
        compose.waitUntil(5_000) { exists(SettingsR.string.wipe_confirm_title) }
    }

    private fun exists(id: Int): Boolean =
        compose.onAllNodes(hasText(string(id))).fetchSemanticsNodes().isNotEmpty()

    @Test fun `the confirmation says what goes and what stays, and Cancel deletes nothing`() {
        show(enrolled = false)
        openConfirmation()
        text(SettingsR.string.wipe_confirm_goes).assertExists()
        text(SettingsR.string.wipe_confirm_stays).assertExists()
        text(SettingsR.string.wipe_confirm_after).assertExists()
        text(SettingsR.string.wipe_confirm_census).assertDoesNotExist()

        text(SettingsR.string.wipe_cancel).performClick()
        compose.waitForIdle()
        text(SettingsR.string.wipe_confirm_title).assertDoesNotExist()
        assertTrue(wiped.isEmpty())
    }

    @Test fun `a phone with no census ID wipes without a network call`() {
        show(enrolled = false)
        openConfirmation()
        text(SettingsR.string.wipe_confirm_button).performClick()
        compose.waitUntil(5_000) { "app storage" in wiped }

        assertEquals(listOf("census key", "app storage"), wiped)
        assertEquals("no census client is even created", 0, networkClients)
        assertEquals(0, transport.deletions)
    }

    @Test fun `an enrolled phone asks the census server first and wipes on its confirmation`() {
        show(enrolled = true)
        openConfirmation()
        text(SettingsR.string.wipe_confirm_census).assertExists()
        text(SettingsR.string.wipe_confirm_button).performClick()

        compose.waitUntil(5_000) { exists(SettingsR.string.wipe_census_confirmed) }
        assertEquals(1, transport.deletions)
        compose.waitUntil(PhoneWipe.CONFIRMED_NOTICE_MILLIS + 5_000) { "app storage" in wiped }
        assertEquals(listOf("census key", "app storage"), wiped)
    }

    @Test fun `a deletion the server can't take yet offers the choice, and Wait deletes nothing`() {
        transport.result = DeleteResult.NotAvailable(404)
        show(enrolled = true)
        openConfirmation()
        text(SettingsR.string.wipe_confirm_button).performClick()

        compose.waitUntil(5_000) { exists(SettingsR.string.wipe_census_title) }
        text(SettingsR.string.wipe_census_not_yet).assertExists()
        text(SettingsR.string.wipe_census_now).assertExists()
        text(SettingsR.string.wipe_census_wait).assertExists()
        text(SettingsR.string.wipe_census_confirmed).assertDoesNotExist()

        text(SettingsR.string.wipe_census_wait_button).performClick()
        compose.waitForIdle()
        text(SettingsR.string.wipe_census_title).assertDoesNotExist()
        assertTrue("Wait deletes nothing", wiped.isEmpty())
        val request = runBlocking { repo.censusDeletion.first() }
        assertEquals("the census request stays pending and keeps retrying", CensusDeletion.State.PENDING, request?.state)
    }

    @Test fun `Delete now after a pending deletion wipes, having said the server keeps the uploads`() {
        transport.result = DeleteResult.TransportFailure("IOException")
        show(enrolled = true)
        openConfirmation()
        text(SettingsR.string.wipe_confirm_button).performClick()

        compose.waitUntil(5_000) { exists(SettingsR.string.wipe_census_unreachable) }
        text(SettingsR.string.wipe_census_now).assertExists()
        assertTrue(wiped.isEmpty())

        text(SettingsR.string.wipe_census_delete_now).performClick()
        compose.waitUntil(10_000) { "app storage" in wiped }
        assertEquals(listOf("census key", "app storage"), wiped)
        assertEquals(1, transport.deletions)
    }

    private companion object {
        val ENROLLED = CensusCredentialStore.Credential(
            "12345678-1234-4123-8123-123456789abc", InstallSecret.encode(ByteArray(32)), enrolled = true,
        )
    }
}
