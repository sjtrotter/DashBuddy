package cloud.trotter.dashbuddy.feature.setup.wizard

import cloud.trotter.dashbuddy.core.data.settings.AppPreferencesRepository
import cloud.trotter.dashbuddy.core.data.state.AppStateRepository
import cloud.trotter.dashbuddy.core.data.strategy.StrategyRepository
import cloud.trotter.dashbuddy.core.data.vehicle.VehicleRepository
import cloud.trotter.dashbuddy.core.data.fuel.FuelPriceRepository
import cloud.trotter.dashbuddy.domain.evaluation.UserEconomy
import cloud.trotter.dashbuddy.domain.model.vehicle.FuelType
import cloud.trotter.dashbuddy.domain.model.vehicle.VehicleDetails
import cloud.trotter.dashbuddy.domain.model.vehicle.VehicleOption
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.After
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.verifyNoMoreInteractions
import org.mockito.kotlin.whenever

/**
 * #347 — the wizard must only persist what it actually collects:
 * - Skip is a TRUE skip (first-run flag only; nothing else written).
 * - Finish persists the strategy and scoring rules and never touches allowShopping
 *   (no wizard step collects it).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WizardViewModelTest {

    private val strategyRepository: StrategyRepository = mock()
    private val appPreferencesRepository: AppPreferencesRepository = mock()
    private val appStateRepository: AppStateRepository = mock()
    private val vehicleRepository: VehicleRepository = mock()
    private val gasPriceRepository: FuelPriceRepository = mock()

    private fun stubRepositories() {
        whenever(appPreferencesRepository.vehicleYear).thenReturn(flowOf(null))
        whenever(appPreferencesRepository.vehicleLookupAllowed).thenReturn(flowOf(false))
        whenever(appPreferencesRepository.vehicleMake).thenReturn(flowOf(null))
        whenever(appPreferencesRepository.vehicleModel).thenReturn(flowOf(null))
        whenever(appPreferencesRepository.vehicleTrim).thenReturn(flowOf(null))
        whenever(appPreferencesRepository.estimatedMpg).thenReturn(flowOf(null))
        whenever(appPreferencesRepository.fuelType).thenReturn(flowOf(FuelType.REGULAR))
        whenever(appPreferencesRepository.isGasPriceAuto).thenReturn(flowOf(false))
        whenever(appPreferencesRepository.gasPrice).thenReturn(flowOf(null))
        whenever(appPreferencesRepository.userEconomy).thenReturn(flowOf(UserEconomy()))
        whenever(strategyRepository.protectStatsMode).thenReturn(flowOf(false))
        whenever(strategyRepository.scoringRules).thenReturn(flowOf(emptyList()))
        whenever { vehicleRepository.getYears() }.thenReturn(emptyList())
        whenever { gasPriceRepository.fetchGasPriceOnly(any()) }
            .thenReturn(Result.failure(RuntimeException("offline in test")))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `init never contacts EPA without opt-in even with a saved vehicle`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        stubRepositories()
        whenever(appPreferencesRepository.vehicleYear).thenReturn(flowOf("2020"))
        whenever(appPreferencesRepository.vehicleMake).thenReturn(flowOf("Toyota"))
        whenever(appPreferencesRepository.vehicleModel).thenReturn(flowOf("Corolla"))
        val viewModel = WizardViewModel(
            strategyRepository, appPreferencesRepository, appStateRepository,
            vehicleRepository, gasPriceRepository,
        )
        testScheduler.advanceUntilIdle()

        assertEquals(false, viewModel.state.value.vehicleLookupAllowed)
        assertEquals(emptyList<String>(), viewModel.availableYears.value)
        verifyNoInteractions(vehicleRepository)
    }

    @Test
    fun `saved lookup opt-in fetches years only after settings are applied`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        stubRepositories()
        whenever(appPreferencesRepository.vehicleLookupAllowed).thenReturn(flowOf(true))
        whenever(appPreferencesRepository.vehicleYear).thenReturn(flowOf("2020"))
        whenever(vehicleRepository.getYears()).thenReturn(listOf("2020"))
        val economy = CompletableDeferred<UserEconomy>()
        whenever(appPreferencesRepository.userEconomy).thenReturn(flow { emit(economy.await()) })
        val viewModel = WizardViewModel(
            strategyRepository, appPreferencesRepository, appStateRepository,
            vehicleRepository, gasPriceRepository,
        )
        testScheduler.advanceUntilIdle()
        assertEquals(false, viewModel.state.value.vehicleLookupAllowed)
        verifyNoInteractions(vehicleRepository)

        economy.complete(UserEconomy())
        testScheduler.advanceUntilIdle()

        assertEquals("2020", viewModel.state.value.vehicleYear)
        assertEquals(true, viewModel.state.value.vehicleLookupAllowed)
        assertEquals(listOf("2020"), viewModel.availableYears.value)
        verify(vehicleRepository).getYears()
        verifyNoMoreInteractions(vehicleRepository)
    }

    @Test
    fun `allow lookup persists the choice before enabling and fetching years`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        stubRepositories()
        whenever(vehicleRepository.getYears()).thenReturn(listOf("2020"))
        val saved = CompletableDeferred<Unit>()
        whenever(appPreferencesRepository.setVehicleLookupAllowed(true)).doSuspendableAnswer {
            saved.await()
        }
        val viewModel = WizardViewModel(
            strategyRepository, appPreferencesRepository, appStateRepository,
            vehicleRepository, gasPriceRepository,
        )
        testScheduler.advanceUntilIdle()

        viewModel.allowVehicleLookup()
        testScheduler.advanceUntilIdle()
        assertEquals(false, viewModel.state.value.vehicleLookupAllowed)
        verifyNoInteractions(vehicleRepository)

        saved.complete(Unit)
        testScheduler.advanceUntilIdle()

        assertEquals(true, viewModel.state.value.vehicleLookupAllowed)
        assertEquals(listOf("2020"), viewModel.availableYears.value)
        inOrder(appPreferencesRepository, vehicleRepository) {
            verify(appPreferencesRepository).setVehicleLookupAllowed(true)
            verify(vehicleRepository).getYears()
        }
        verifyNoMoreInteractions(vehicleRepository)
    }

    @Test
    fun `selection callbacks and manual MPG entry do not contact EPA without opt-in`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        stubRepositories()
        val viewModel = WizardViewModel(
            strategyRepository, appPreferencesRepository, appStateRepository,
            vehicleRepository, gasPriceRepository,
        )
        testScheduler.advanceUntilIdle()

        viewModel.onYearSelected("2020")
        viewModel.onMakeSelected("Toyota")
        viewModel.onModelSelected("Corolla")
        viewModel.onTrimSelected("Automatic")
        viewModel.onMakeSelected(VEHICLE_NOT_LISTED)
        viewModel.updateEstimatedMpg(32f)
        testScheduler.advanceUntilIdle()

        assertEquals(false, viewModel.state.value.vehicleLookupAllowed)
        assertEquals(VEHICLE_NOT_LISTED, viewModel.state.value.vehicleMake)
        assertEquals(32f, viewModel.state.value.estimatedMpg, 0.0f)
        verifyNoInteractions(vehicleRepository)
        verify(appPreferencesRepository, org.mockito.kotlin.never()).setVehicleLookupAllowed(any())
    }

    @Test
    fun `allowed lookup follows the year make model and trim selections`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        stubRepositories()
        whenever(vehicleRepository.getYears()).thenReturn(listOf("2020"))
        whenever(vehicleRepository.getMakes("2020")).thenReturn(listOf("Toyota"))
        whenever(vehicleRepository.getModels("2020", "Toyota")).thenReturn(listOf("Corolla"))
        whenever(vehicleRepository.getVehicleOptions("2020", "Toyota", "Corolla"))
            .thenReturn(listOf(VehicleOption("123", "Automatic")))
        whenever(vehicleRepository.getVehicleDetails("123"))
            .thenReturn(VehicleDetails(32f, FuelType.REGULAR))
        val viewModel = WizardViewModel(
            strategyRepository, appPreferencesRepository, appStateRepository,
            vehicleRepository, gasPriceRepository,
        )
        testScheduler.advanceUntilIdle()

        viewModel.allowVehicleLookup()
        testScheduler.advanceUntilIdle()
        viewModel.onYearSelected("2020")
        testScheduler.advanceUntilIdle()
        viewModel.onMakeSelected("Toyota")
        testScheduler.advanceUntilIdle()
        viewModel.onModelSelected("Corolla")
        testScheduler.advanceUntilIdle()
        viewModel.onTrimSelected("Automatic")
        testScheduler.advanceUntilIdle()

        assertEquals(32f, viewModel.state.value.estimatedMpg, 0.0f)
        inOrder(vehicleRepository) {
            verify(vehicleRepository).getYears()
            verify(vehicleRepository).getMakes("2020")
            verify(vehicleRepository).getModels("2020", "Toyota")
            verify(vehicleRepository).getVehicleOptions("2020", "Toyota", "Corolla")
            verify(vehicleRepository).getVehicleDetails("123")
        }
        verifyNoMoreInteractions(vehicleRepository)
    }

    @Test
    fun `skip writes nothing but the first-run flag`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        stubRepositories()
        val viewModel = WizardViewModel(
            strategyRepository, appPreferencesRepository, appStateRepository,
            vehicleRepository, gasPriceRepository,
        )
        testScheduler.advanceUntilIdle() // let init's loads settle
        clearInvocations(strategyRepository, appPreferencesRepository)

        var completed = false
        viewModel.skipAndFinish { completed = true }
        testScheduler.advanceUntilIdle()

        assert(completed)
        verify(appStateRepository).setFirstRunComplete()
        verifyNoMoreInteractions(strategyRepository)
        verifyNoMoreInteractions(appPreferencesRepository)
    }

    @Test
    fun `finish persists strategy and scoring rules without touching allowShopping`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        stubRepositories()
        val viewModel = WizardViewModel(
            strategyRepository, appPreferencesRepository, appStateRepository,
            vehicleRepository, gasPriceRepository,
        )
        testScheduler.advanceUntilIdle()

        var completed = false
        viewModel.saveAndFinish { completed = true }
        testScheduler.advanceUntilIdle()

        assert(completed)
        verify(strategyRepository).setProtectStatsMode(false)
        verify(strategyRepository).updateRules(emptyList())
        verify(strategyRepository, org.mockito.kotlin.never()).setAllowShopping(any())
        verify(appStateRepository).setFirstRunComplete()
    }

    @Test
    fun `finish saves default gas price when no price is saved and auto is off`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        stubRepositories()
        val viewModel = WizardViewModel(
            strategyRepository, appPreferencesRepository, appStateRepository,
            vehicleRepository, gasPriceRepository,
        )
        testScheduler.advanceUntilIdle()

        assertEquals(false, viewModel.state.value.isGasPriceAuto)
        assertEquals(3.50f, viewModel.state.value.gasPrice, 0.0f)
        viewModel.saveAndFinish { }
        testScheduler.advanceUntilIdle()

        verify(appPreferencesRepository).updateEconomySettings(
            "", "", "", "", 0.0f, false, 3.50f,
        )
        verify(appStateRepository).setFirstRunComplete()
        verifyNoInteractions(gasPriceRepository)
    }

    @Test
    fun `skip never reads or fetches anything new`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        stubRepositories()
        val viewModel = WizardViewModel(
            strategyRepository, appPreferencesRepository, appStateRepository,
            vehicleRepository, gasPriceRepository,
        )
        testScheduler.advanceUntilIdle()
        clearInvocations(vehicleRepository, gasPriceRepository)

        viewModel.skipAndFinish { }
        testScheduler.advanceUntilIdle()

        verifyNoInteractions(vehicleRepository)
        verifyNoMoreInteractions(gasPriceRepository)
    }
    @Test
    fun `no startup gas fetch before all settings finish loading`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        stubRepositories()
        whenever(appPreferencesRepository.isGasPriceAuto).thenReturn(flowOf(true))
        val economy = CompletableDeferred<UserEconomy>()
        whenever(appPreferencesRepository.userEconomy).thenReturn(flow { emit(economy.await()) })
        val viewModel = WizardViewModel(
            strategyRepository, appPreferencesRepository, appStateRepository,
            vehicleRepository, gasPriceRepository,
        )
        testScheduler.advanceUntilIdle()
        assertEquals(false, viewModel.state.value.isGasPriceAuto)
        verifyNoInteractions(gasPriceRepository)

        economy.complete(UserEconomy())
        testScheduler.advanceUntilIdle()
        FuelType.entries.forEach { verify(gasPriceRepository).fetchGasPriceOnly(it) }
        verifyNoMoreInteractions(gasPriceRepository)
    }

    @Test
    fun `saved false prevents startup gas fetch after delayed settings load`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        stubRepositories()
        val auto = CompletableDeferred<Boolean>()
        whenever(appPreferencesRepository.isGasPriceAuto).thenReturn(flow { emit(auto.await()) })
        val viewModel = WizardViewModel(
            strategyRepository, appPreferencesRepository, appStateRepository,
            vehicleRepository, gasPriceRepository,
        )
        testScheduler.advanceUntilIdle()
        verifyNoInteractions(gasPriceRepository)
        auto.complete(false)
        testScheduler.advanceUntilIdle()
        assertEquals(false, viewModel.state.value.isGasPriceAuto)
        verifyNoInteractions(gasPriceRepository)
    }

    @Test
    fun `saved true fetches once per fuel type after delayed settings load`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        stubRepositories()
        val auto = CompletableDeferred<Boolean>()
        whenever(appPreferencesRepository.isGasPriceAuto).thenReturn(flow { emit(auto.await()) })
        val viewModel = WizardViewModel(
            strategyRepository, appPreferencesRepository, appStateRepository,
            vehicleRepository, gasPriceRepository,
        )
        testScheduler.advanceUntilIdle()
        verifyNoInteractions(gasPriceRepository)
        auto.complete(true)
        testScheduler.advanceUntilIdle()
        assertEquals(true, viewModel.state.value.isGasPriceAuto)
        FuelType.entries.forEach { verify(gasPriceRepository).fetchGasPriceOnly(it) }
        verifyNoMoreInteractions(gasPriceRepository)
    }

    @Test
    fun `turning auto pricing on invokes the existing fuel type fetch loop`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        stubRepositories()
        val auto = CompletableDeferred<Boolean>()
        whenever(appPreferencesRepository.isGasPriceAuto).thenReturn(flow { emit(auto.await()) })
        val viewModel = WizardViewModel(
            strategyRepository, appPreferencesRepository, appStateRepository,
            vehicleRepository, gasPriceRepository,
        )
        testScheduler.advanceUntilIdle()
        auto.complete(false)
        testScheduler.advanceUntilIdle()
        verifyNoInteractions(gasPriceRepository)
        viewModel.toggleAutoGasPrice(true)
        testScheduler.advanceUntilIdle()
        assertEquals(true, viewModel.state.value.isGasPriceAuto)
        FuelType.entries.forEach { verify(gasPriceRepository).fetchGasPriceOnly(it) }
        verifyNoMoreInteractions(gasPriceRepository)
    }
}
