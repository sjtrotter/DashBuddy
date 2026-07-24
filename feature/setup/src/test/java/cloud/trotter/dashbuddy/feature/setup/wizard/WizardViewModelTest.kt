package cloud.trotter.dashbuddy.feature.setup.wizard

import cloud.trotter.dashbuddy.core.data.settings.AppPreferencesRepository
import cloud.trotter.dashbuddy.core.data.state.AppStateRepository
import cloud.trotter.dashbuddy.core.data.strategy.StrategyRepository
import cloud.trotter.dashbuddy.core.data.vehicle.VehicleRepository
import cloud.trotter.dashbuddy.core.data.fuel.FuelPriceRepository
import cloud.trotter.dashbuddy.domain.config.OfferAutomationConfig
import cloud.trotter.dashbuddy.domain.evaluation.UserEconomy
import cloud.trotter.dashbuddy.domain.model.vehicle.FuelType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.verifyNoMoreInteractions
import org.mockito.kotlin.whenever
import org.mockito.kotlin.wheneverBlocking

/**
 * #347 — the wizard must only persist what it actually collects:
 * - Skip is a TRUE skip (first-run flag only; nothing else written).
 * - Finish preserves automation thresholds it doesn't collect (no more
 *   hardcoded-default resets on re-run) and never touches allowShopping
 *   (no wizard step collects it).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WizardViewModelTest {

    private val strategyRepository: StrategyRepository = mock()
    private val appPreferencesRepository: AppPreferencesRepository = mock()
    private val appStateRepository: AppStateRepository = mock()
    private val vehicleRepository: VehicleRepository = mock()
    private val gasPriceRepository: FuelPriceRepository = mock()

    /** Values a user would have tuned in settings — must survive a wizard pass. */
    private val tunedAutomation = OfferAutomationConfig(
        masterAutoPilotEnabled = true,
        autoAcceptEnabled = true,
        autoAcceptMinPay = 12.0,
        autoAcceptMinRatio = 3.25,
        autoDeclineEnabled = true,
        autoDeclineMaxPay = 5.25,
        autoDeclineMinRatio = 0.80,
    )

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
        whenever(strategyRepository.automationConfig).thenReturn(flowOf(tunedAutomation))
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
    fun `finish preserves automation thresholds the wizard does not collect`() = runTest {
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
        // Strategy default is MANUAL → autoDecline=false (collected); every
        // threshold must round-trip from the tuned config, not reset to defaults.
        verify(strategyRepository).updateAutomation(
            autoAccept = eq(true),
            acceptMinPay = eq(12.0),
            acceptMinRatio = eq(3.25),
            autoDecline = eq(false),
            declineMaxPay = eq(5.25),
            declineMinRatio = eq(0.80),
        )
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
}
