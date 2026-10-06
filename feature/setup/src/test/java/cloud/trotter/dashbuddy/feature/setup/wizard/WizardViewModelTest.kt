package cloud.trotter.dashbuddy.feature.setup.wizard

import cloud.trotter.dashbuddy.core.data.settings.AppPreferencesRepository
import cloud.trotter.dashbuddy.core.data.state.AppStateRepository
import cloud.trotter.dashbuddy.core.data.strategy.StrategyRepository
import cloud.trotter.dashbuddy.core.data.vehicle.VehicleRepository
import cloud.trotter.dashbuddy.core.data.fuel.FuelPriceRepository
import cloud.trotter.dashbuddy.domain.evaluation.UserEconomy
import cloud.trotter.dashbuddy.domain.model.vehicle.FuelType
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
