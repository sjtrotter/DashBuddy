package cloud.trotter.dashbuddy.ui.components.economy

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import cloud.trotter.dashbuddy.core.designsystem.theme.DashBuddyTheme
import cloud.trotter.dashbuddy.domain.model.vehicle.*
import cloud.trotter.dashbuddy.domain.evaluation.UserEconomy
import cloud.trotter.dashbuddy.domain.evaluation.SharedEconomy
import cloud.trotter.dashbuddy.domain.evaluation.VehicleEconomyInputs
import cloud.trotter.dashbuddy.core.data.settings.VehicleCostTransforms
import androidx.lifecycle.viewModelScope
import cloud.trotter.dashbuddy.ui.main.settings.EconomySettingsViewModel
import cloud.trotter.dashbuddy.core.data.settings.AppPreferencesRepository
import cloud.trotter.dashbuddy.core.data.strategy.StrategyRepository
import cloud.trotter.dashbuddy.core.data.vehicle.Garage
import cloud.trotter.dashbuddy.core.data.vehicle.GarageRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.verify
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FinishedNumberInputTest {
    @get:Rule val compose = createComposeRule()
    private val commits = mutableListOf<Double>()

    private fun showCurrency() {
        compose.setContent { DashBuddyTheme {
            val focus = LocalFocusManager.current
            Column {
                CurrencyInput("Cost", 0.0, { commits += it })
                Button(onClick = { focus.clearFocus() }) { Text("Finish") }
            }
        } }
    }

    @Test fun `2 then 25 commits only 25 once on focus loss`() {
        showCurrency()
        compose.onNodeWithText("Cost").performTextReplacement("2")
        compose.onNodeWithText("Cost").performTextReplacement("25")
        compose.runOnIdle { assertTrue(commits.isEmpty()) }
        compose.onNodeWithText("Finish").performClick()
        compose.runOnIdle { assertEquals(listOf(25.0), commits) }
    }

    @Test fun `IME Done commits once including its subsequent blur`() {
        showCurrency()
        compose.onNodeWithText("Cost").performTextReplacement("25")
        compose.onNodeWithText("Cost").performImeAction()
        compose.onNodeWithText("Finish").performClick()
        compose.runOnIdle { assertEquals(listOf(25.0), commits) }
    }

    @Test fun `leaving composition commits valid local text once`() {
        var visible by mutableStateOf(true)
        compose.setContent { DashBuddyTheme { if (visible) CurrencyInput("Cost", 0.0, { commits += it }) } }
        compose.onNodeWithText("Cost").performTextReplacement("25")
        compose.runOnIdle { visible = false }
        compose.runOnIdle { assertEquals(listOf(25.0), commits) }
    }

    @Test fun `invalid focused text shows errors and blur restores stored text without committing`() {
        showCurrency()
        for (input in listOf("invalid", "", "-1", "NaN", "Infinity")) {
            compose.onNodeWithText("Cost").performTextReplacement(input)
            compose.onNodeWithText("Cost").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))
            compose.onNodeWithText("Finish").performClick()
            compose.onNodeWithText("Cost").assertTextContains("0")
            compose.runOnIdle { assertTrue(commits.isEmpty()) }
        }
    }

    @Test fun `pair commits only the edited half and preserves a fractional stored interval`() {
        val pairs = mutableListOf<Pair<Double?, Double?>>()
        var stored by mutableStateOf<VehicleProfile>(GasCar(wear = WearProfile(oilCost = 60.0, oilIntervalMi = 5000.25)))
        compose.setContent { DashBuddyTheme {
            PairedCurrencyAndIntervalInput("Cost", stored.wear.oilCost!!, "Miles", stored.wear.oilIntervalMi!!,
                onValueChange = { cost, miles ->
                    pairs += cost to miles
                    stored = VehicleCostTransforms.oil(stored, cost, miles)
                })
        } }
        compose.onNodeWithText("Cost").performTextReplacement("65")
        compose.onNodeWithText("Cost").performImeAction()
        compose.runOnIdle {
            assertEquals(listOf(65.0 to null), pairs)
            assertEquals(5000.25, stored.wear.oilIntervalMi)
        }
        compose.onNodeWithText("Miles").performTextReplacement("6000")
        compose.onNodeWithText("Miles").performImeAction()
        compose.runOnIdle { assertEquals(listOf(65.0 to null, null to 6000.0), pairs) }
    }

    @Test fun `pair focus blur and disposal without edits write nothing`() {
        val pairs = mutableListOf<Pair<Double?, Double?>>()
        var visible by mutableStateOf(true)
        compose.setContent { DashBuddyTheme {
            if (visible) PairedCurrencyAndIntervalInput("Cost", 60.0, "Miles", 5000.25,
                onValueChange = { cost, miles -> pairs += cost to miles })
        } }
        compose.onNodeWithText("Cost").performClick()
        compose.onNodeWithText("Miles").performClick()
        compose.onNodeWithText("Miles").performImeAction()
        compose.runOnIdle { visible = false }
        compose.runOnIdle { assertTrue(pairs.isEmpty()) }
    }

    @Test fun `pair commits a valid edit even when the other half is invalid`() {
        val pairs = mutableListOf<Pair<Double?, Double?>>()
        compose.setContent { DashBuddyTheme {
            PairedCurrencyAndIntervalInput("Cost", 60.0, "Miles", 5000.25,
                onValueChange = { cost, miles -> pairs += cost to miles })
        } }
        compose.onNodeWithText("Cost").performTextReplacement("invalid")
        compose.onNodeWithText("Miles").performTextReplacement("6000")
        compose.onNodeWithText("Miles").performImeAction()
        compose.runOnIdle { assertEquals(listOf(null to 6000.0), pairs) }
    }

    @Test fun `outside value follows stored value only while unfocused`() {
        var stored by mutableDoubleStateOf(10.0)
        compose.setContent { DashBuddyTheme { CurrencyInput("Cost", stored, { commits += it }) } }
        compose.onNodeWithText("Cost").performTextReplacement("25.")
        compose.runOnIdle { stored = 99.0 }
        compose.onNodeWithText("Cost").assertTextContains("25.")
        compose.onNodeWithText("Cost").performImeAction()
        compose.runOnIdle { assertEquals(listOf(25.0), commits); stored = 88.0 }
        compose.onNodeWithText("Cost").assertTextContains("88")
    }

    @Test fun `MPG commits on disposal and rejects zero`() {
        var visible by mutableStateOf(true)
        compose.setContent { DashBuddyTheme { if (visible) VehicleMpgAndFuelInputs(20.0, FuelType.REGULAR,
            { commits += it }, {}) } }
        compose.onNodeWithText("Combined MPG").performTextReplacement("0")
        compose.onNodeWithText("Combined MPG").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))
        compose.onNodeWithText("Combined MPG").performImeAction()
        compose.runOnIdle { assertTrue(commits.isEmpty()) }
        compose.onNodeWithText("Combined MPG").performTextReplacement("25")
        compose.runOnIdle { visible = false }
        compose.runOnIdle { assertEquals(listOf(25.0), commits) }
    }

    @Test fun `unmodified focused text follows the outside value`() {
        var stored by mutableDoubleStateOf(10.0)
        compose.setContent { DashBuddyTheme { CurrencyInput("Cost", stored, { commits += it }) } }
        compose.onNodeWithText("Cost").performClick()
        compose.runOnIdle { stored = 99.0 }
        compose.onNodeWithText("Cost").assertTextContains("99")
        compose.onNodeWithText("Cost").performImeAction()
        compose.onNodeWithText("Cost").assertTextContains("99")
        compose.runOnIdle { assertTrue(commits.isEmpty()) }
    }
    @Test fun `currency focus blur Done and disposal without an edit save nothing`() {
        var visible by mutableStateOf(true)
        compose.setContent { DashBuddyTheme {
            if (visible) CurrencyInput("Cost", 10.0, { commits += it })
        } }
        compose.onNodeWithText("Cost").requestFocus()
        compose.onNodeWithText("Cost").performImeAction()
        compose.onNodeWithText("Cost").requestFocus()
        compose.runOnIdle { visible = false }
        compose.runOnIdle { assertTrue(commits.isEmpty()) }
    }

    @Test fun `same numeric currency commit follows a later outside value when unfocused`() {
        var stored by mutableDoubleStateOf(10.0)
        compose.setContent { DashBuddyTheme { CurrencyInput("Cost", stored, { commits += it; stored = it }) } }
        compose.onNodeWithText("Cost").performTextReplacement("10.0")
        compose.onNodeWithText("Cost").performImeAction()
        compose.runOnIdle { assertEquals(listOf(10.0), commits); stored = 99.0 }
        compose.onNodeWithText("Cost").assertTextContains("99")
    }

    @Test fun `unmodified preset MPG stays a preset after a later class change`() {
        var profile by mutableStateOf<VehicleProfile>(GasCar())
        var visible by mutableStateOf(true)
        compose.setContent { DashBuddyTheme {
            if (visible) {
                val economy = UserEconomy.from(VehicleEconomyInputs.from(profile, SharedEconomy()), SharedEconomy())
                val focus = LocalFocusManager.current
                Column {
                    VehicleMpgAndFuelInputs(economy.vehicleMpg, FuelType.REGULAR,
                        { commits += it; profile = VehicleCostTransforms.mpg(profile, it) }, {})
                    Button(onClick = { focus.clearFocus() }) { Text("Finish") }
                }
            }
        } }
        compose.onNodeWithText("Combined MPG").requestFocus()
        compose.onNodeWithText("Finish").performClick()
        compose.onNodeWithText("Combined MPG").requestFocus()
        compose.onNodeWithText("Combined MPG").performImeAction()
        compose.runOnIdle {
            assertTrue(commits.isEmpty())
            assertNull(profile.gasMpg)
            profile = (profile as GasCar).copy(vehicleClass = GasCarClass.SUV)
        }
        compose.onNodeWithText("Combined MPG").assertTextContains(VehicleClass.SUV.defaultMpg.toString())
        compose.onNodeWithText("Combined MPG").requestFocus()
        compose.runOnIdle { visible = false }
        compose.runOnIdle { assertTrue(commits.isEmpty()); assertNull(profile.gasMpg) }
    }

    @Test fun `depreciation switch calls only include and price commits only the edited value`() {
        val includes = mutableListOf<Boolean>()
        val values = mutableListOf<Pair<Double?, Double?>>()
        compose.setContent { DashBuddyTheme {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                EconomyEditor(economy = UserEconomy(includeDepreciation = true),
                    onTiresChange = { _, _ -> }, onOilChange = { _, _ -> },
                    onBrakesChange = { _, _ -> }, onFluidsChange = { _, _ -> },
                    onMiscChange = { _, _ -> }, onDepreciationIncludeChange = { includes += it },
                    onDepreciationValuesChange = { price, miles -> values += price to miles },
                    onInsuranceChange = {}, onRegistrationChange = {}, onPhoneChange = { _, _, _ -> },
                    onExpectedAnnualMilesChange = {})
            }
        } }
        compose.onNodeWithText("Depreciation").performScrollTo().performClick()
        compose.onNode(isToggleable()).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(false), includes); assertTrue(values.isEmpty()) }
        compose.onNodeWithText("Purchase price").performScrollTo().performTextReplacement("12000")
        compose.onNodeWithText("Purchase price").performImeAction()
        compose.onNode(isToggleable()).performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(listOf(false, false), includes)
            assertEquals(listOf(12000.0 to null), values)
        }
    }

    @Test fun `composition disposal commit reaches the repository after economy scope cancellation`() {
        val preferences: AppPreferencesRepository = mock()
        whenever(preferences.userEconomy).thenReturn(flowOf(UserEconomy()))
        whenever(preferences.fuelType).thenReturn(flowOf(FuelType.REGULAR))
        val garage: GarageRepository = mock()
        whenever(garage.garage).thenReturn(flowOf(Garage(emptyList(), null, true)))
        val strategy: StrategyRepository = mock()
        whenever(strategy.evaluationConfig).thenReturn(MutableStateFlow(null))
        val release = CompletableDeferred<Unit>()
        val landed = CompletableDeferred<Unit>()
        runBlocking {
            whenever(preferences.updateInsuranceDelta(65.0)).doSuspendableAnswer {
                release.await()
                landed.complete(Unit)
                Unit
            }
        }
        lateinit var vm: EconomySettingsViewModel
        var visible by mutableStateOf(true)
        compose.runOnIdle { vm = EconomySettingsViewModel(preferences, strategy, garage) }
        compose.setContent { DashBuddyTheme {
            if (visible) CurrencyInput("Cost", 10.0, { vm.setInsurance(it) })
        } }
        compose.onNodeWithText("Cost").performTextReplacement("65")
        compose.runOnIdle { vm.viewModelScope.cancel(); visible = false }
        compose.runOnIdle {
            runBlocking { verify(preferences).updateInsuranceDelta(65.0) }
            assertFalse(landed.isCompleted)
            release.complete(Unit)
        }
        compose.waitUntil { landed.isCompleted }
    }


}
