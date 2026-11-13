package cloud.trotter.log.gig.ui.main.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import cloud.trotter.log.gig.core.data.census.CensusDataDeletion
import cloud.trotter.log.gig.core.data.settings.DevSettingsRepository
import cloud.trotter.log.gig.core.datastore.settings.DevSettingsDataSource
import cloud.trotter.log.gig.core.designsystem.theme.GigLogTheme
import cloud.trotter.log.gig.domain.capability.ConsentReceipt
import cloud.trotter.log.gig.domain.census.CensusChoices
import cloud.trotter.log.gig.domain.census.CensusConsent
import cloud.trotter.log.gig.domain.census.CensusConsentReceipt
import cloud.trotter.log.gig.domain.state.Platform
import cloud.trotter.log.gig.feature.settings.CensusScreen
import cloud.trotter.log.gig.feature.settings.CensusViewModel
import cloud.trotter.log.gig.feature.settings.R as SettingsR
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
 * #1621: the census opt-in over the real repository. Turning the switch on shows the disclosure and writes nothing;
 * only Accept turns the census on, with a receipt for this endpoint and the choices on screen; Not now records a
 * decline. The screen shows no time, so these tests need no clock.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CensusDisclosureTest {
    @get:Rule val compose = createComposeRule()

    private class MemoryPreferences : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(data.value).also { data.value = it }
    }

    private val source = DevSettingsDataSource(MemoryPreferences())
    private val repo = DevSettingsRepository(source, true, Dispatchers.Unconfined, "0.233.1")
    private val context = RuntimeEnvironment.getApplication()
    private fun string(id: Int) = context.getString(id)

    private fun show() {
        val deletion = mock<CensusDataDeletion>()
        whenever(deletion.inProgress).thenReturn(MutableStateFlow(false))
        val vm = CensusViewModel(repo, deletion, readsUnboundPlatforms = false)
        compose.setContent { GigLogTheme { CensusScreen(onBack = {}, onOpenSent = {}, viewModel = vm) } }
        compose.waitForIdle()
    }

    private fun censusSwitch() = compose.onNode(isToggleable() and hasText(string(SettingsR.string.census_switch_label)))
    private fun disclosureHeading() = compose.onNodeWithText(string(SettingsR.string.census_disclosure_heading))
    private fun consent() = runBlocking { repo.censusConsent.first() }
    private fun receipt() = runBlocking { source.censusConsent.first().receipt }

    @Test
    fun `turning it on shows the disclosure and writes nothing until Accept`() {
        show()
        censusSwitch().assertIsOff().performClick()
        disclosureHeading().assertIsDisplayed()
        compose.onNodeWithText("one-way function", substring = true).assertExists()
        compose.onNodeWithText("between you and that company", substring = true).assertExists()
        assertNull("opening the disclosure writes nothing", receipt())
        assertFalse(runBlocking { repo.enabled.first() })

        compose.onNodeWithText(string(SettingsR.string.census_disclosure_accept)).performClick()
        compose.waitForIdle()

        assertEquals(CensusConsent.ON, consent())
        val stored = requireNotNull(receipt())
        assertTrue(stored.receipt.granted)
        assertEquals(runBlocking { repo.baseUrl.first() }, stored.endpoint)
        assertEquals("0.233.1", stored.receipt.appVersion)
        assertEquals(CensusChoices(Platform.boundPlatforms.toSet(), wifiOnly = true), runBlocking { repo.censusChoices.first() })
        censusSwitch().assertIsOn()
    }

    @Test
    fun `Not now records a decline and leaves it off`() {
        show()
        censusSwitch().performClick()
        compose.onNodeWithText(string(SettingsR.string.consent_front_door_not_now)).performClick()
        compose.waitForIdle()

        assertEquals(CensusConsent.OFF, consent())
        assertFalse(requireNotNull(receipt()).receipt.granted)
        censusSwitch().assertIsOff()
    }

    @Test
    fun `turning it off records a decline without showing the disclosure`() {
        runBlocking { repo.setCensusUploadEnabled(true) }
        show()
        censusSwitch().assertIsOn().performClick()
        compose.waitForIdle()

        disclosureHeading().assertDoesNotExist()
        assertEquals(CensusConsent.OFF, consent())
        assertFalse(requireNotNull(receipt()).receipt.granted)
    }

    @Test
    fun `a changed endpoint shows the switch off with the reason, and Accept binds the new endpoint`() {
        runBlocking {
            source.setCensusUploadEnabled(
                true, CensusConsentReceipt(ConsentReceipt(1L, "0.230.0", 19, granted = true), "https://census.old.example"),
            )
        }
        show()
        censusSwitch().assertIsOff()
        compose.onNodeWithText(string(SettingsR.string.census_endpoint_changed)).assertIsDisplayed()

        censusSwitch().performClick()
        compose.onNodeWithText(string(SettingsR.string.census_disclosure_accept)).performClick()
        compose.waitForIdle()
        assertEquals(CensusConsent.ON, consent())
    }

    @Test
    fun `the choices save as they change and the test build says what it sends`() {
        show()
        compose.onNodeWithText(string(SettingsR.string.census_note_test_build)).assertIsDisplayed()
        compose.onNode(isToggleable() and hasText(string(SettingsR.string.census_wifi_only_label))).assertIsOn().performClick()
        compose.onNode(isToggleable() and hasText(Platform.Uber.displayName)).assertIsOn().performClick()
        compose.waitForIdle()

        assertEquals(CensusChoices(setOf(Platform.DoorDash), wifiOnly = false), runBlocking { repo.censusChoices.first() })
        assertEquals(CensusConsent.OFF, consent())
    }
}
