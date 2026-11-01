package cloud.trotter.log.gig.ui.main.setup.wizard

import cloud.trotter.log.gig.core.data.fuel.FuelPriceRepository
import cloud.trotter.log.gig.core.data.settings.AppPreferencesRepository
import cloud.trotter.log.gig.core.data.state.AppStateRepository
import cloud.trotter.log.gig.core.data.strategy.StrategyRepository
import cloud.trotter.log.gig.core.data.vehicle.Garage
import cloud.trotter.log.gig.core.data.vehicle.GarageRepository
import cloud.trotter.log.gig.core.data.vehicle.VehicleRepository
import cloud.trotter.log.gig.domain.evaluation.UserEconomy
import cloud.trotter.log.gig.domain.model.vehicle.FuelType
import cloud.trotter.log.gig.domain.model.vehicle.Vehicle
import cloud.trotter.log.gig.domain.model.vehicle.VehicleProfile
import cloud.trotter.log.gig.feature.setup.wizard.WizardViewModel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.mockito.kotlin.any
import org.mockito.kotlin.isNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * A real [WizardViewModel] over mocked repositories (#1447), for the wizard screenshot and the
 * navigation test. Without every read stubbed the ViewModel's `init` throws. The stubs are
 * `WizardViewModelTest.stubRepositories()`'s, which is suspend, hence the `runBlocking`.
 */
internal fun wizardViewModelForTest(): WizardViewModel {
    val strategyRepository: StrategyRepository = mock()
    val appPreferencesRepository: AppPreferencesRepository = mock()
    val appStateRepository: AppStateRepository = mock()
    val vehicleRepository: VehicleRepository = mock()
    val gasPriceRepository: FuelPriceRepository = mock()
    val garageRepository: GarageRepository = mock()
    stubWizardRepositories(
        strategyRepository, appPreferencesRepository, vehicleRepository, gasPriceRepository, garageRepository,
    )
    return WizardViewModel(
        strategyRepository, appPreferencesRepository, appStateRepository,
        vehicleRepository, gasPriceRepository, garageRepository,
    )
}

private fun stubWizardRepositories(
    strategyRepository: StrategyRepository,
    appPreferencesRepository: AppPreferencesRepository,
    vehicleRepository: VehicleRepository,
    gasPriceRepository: FuelPriceRepository,
    garageRepository: GarageRepository,
) {
    runBlocking {
        whenever(garageRepository.garage).thenReturn(flowOf(Garage(emptyList(), null, true)))
        whenever(garageRepository.addVehicle(isNull(), any(), any(), any())).thenReturn(Vehicle("new-id", null, 1L))
        whenever(garageRepository.updateProfile(any(), any<VehicleProfile>())).thenReturn(true)
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
}
