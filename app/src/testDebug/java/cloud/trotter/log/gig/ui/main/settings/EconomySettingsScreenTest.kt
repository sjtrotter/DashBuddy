package cloud.trotter.log.gig.ui.main.settings

import cloud.trotter.log.gig.core.datastore.settings.StoredPreference
import kotlinx.coroutines.runBlocking
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import cloud.trotter.log.gig.core.data.settings.AppPreferencesRepository
import cloud.trotter.log.gig.core.data.strategy.StrategyRepository
import cloud.trotter.log.gig.core.data.vehicle.*
import cloud.trotter.log.gig.core.designsystem.theme.GigLogTheme
import cloud.trotter.log.gig.domain.evaluation.UserEconomy
import cloud.trotter.log.gig.domain.model.vehicle.Vehicle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EconomySettingsScreenTest {
    @get:Rule val compose = createComposeRule()
    private var opened: String? = null
    private var garageOpened = false
    private lateinit var vm: EconomySettingsViewModel
    private fun show(active: Vehicle?, storedPreferences: AppPreferencesRepository? = null) {
        val garage: GarageRepository = mock { on { garage } doReturn flowOf(Garage(listOfNotNull(active), active?.id, true)) }
        val preferences: AppPreferencesRepository = storedPreferences ?: mock {
            on { userEconomy } doReturn flowOf(UserEconomy())
            on { isGasPriceAuto } doReturn flowOf(true)
        }
        val strategy: StrategyRepository = mock { on { evaluationConfig } doReturn MutableStateFlow(null) }
        compose.runOnIdle { vm = EconomySettingsViewModel(preferences, strategy, garage) }
        compose.setContent { GigLogTheme {
            EconomySettingsScreen({}, { garageOpened = true }, { opened = it }, vm)
        } }
        compose.waitForIdle()
    }
    @Test fun `shared settings have no vehicle cost editors and active row opens its details`() {
        show(Vehicle("car-id", "Current car", 1))
        compose.onNodeWithText("Gas price").assertExists()
        compose.onNodeWithText("Electricity price").assertExists()
        for (label in listOf("Tires", "Oil changes", "Brakes", "Depreciation", "Insurance", "Combined MPG", "Vehicle class")) {
            compose.onNodeWithText(label).assertDoesNotExist()
        }
        compose.onNodeWithText("Current car").performClick()
        compose.waitUntil { opened != null }
        assertEquals("car-id", opened)
        assertFalse(garageOpened)
        // The same host can be revisited after navigating forward to vehicle details.
        compose.onNodeWithText("Reset to defaults").assertIsEnabled()
    }
    @Test fun `no vehicle row opens Garage`() {
        show(null)
        compose.onNodeWithText("No vehicle").performClick()
        compose.waitUntil { garageOpened }
        assertNull(opened)
    }

    @Test fun `finished phone lines offers snackbar undo restoring shown lines and retaining siblings`() {
        val economy = MutableStateFlow(UserEconomy(phonePlanTotal = 125.0, phonePlanLines = 2, phoneBusinessPercent = 45.0))
        val preferences: AppPreferencesRepository = mock {
            on { userEconomy } doReturn economy
            on { isGasPriceAuto } doReturn flowOf(false)
        }
        runBlocking {
            whenever(preferences.updatePhoneLines(any())).doAnswer {
                val previous = economy.value.phonePlanLines
                economy.value = economy.value.copy(phonePlanLines = it.getArgument(0))
                StoredPreference(previous, true)
            }
            whenever(preferences.restorePhoneLines(any(), any(), any())).doAnswer {
                val previous = it.getArgument<StoredPreference<Int>>(0)
                economy.value = economy.value.copy(phonePlanLines = requireNotNull(previous.value))
                true
            }
        }
        show(null, preferences)
        compose.onNodeWithText("Phone & data").performScrollTo().performClick()
        val input = compose.onNode(hasSetTextAction() and hasText("Lines"))
        input.performScrollTo().performTextReplacement("3")
        compose.onNodeWithText("Lines saved").assertDoesNotExist()
        input.performImeAction()
        compose.onNodeWithText("Lines saved").assertIsDisplayed()
        compose.onNodeWithText("Undo").performClick()
        compose.waitUntil { economy.value.phonePlanLines == 2 }
        input.assertTextContains("2")
        assertEquals(125.0, economy.value.phonePlanTotal, 0.0)
        assertEquals(45.0, economy.value.phoneBusinessPercent, 0.0)
        compose.onNodeWithText("Lines saved").assertDoesNotExist()
    }

}
