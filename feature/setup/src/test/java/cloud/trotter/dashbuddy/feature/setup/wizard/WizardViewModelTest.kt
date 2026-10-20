package cloud.trotter.dashbuddy.feature.setup.wizard

import cloud.trotter.dashbuddy.feature.setup.wizard.model.toUserEconomy
import cloud.trotter.dashbuddy.feature.setup.wizard.model.toVehicleProfile
import cloud.trotter.dashbuddy.core.data.settings.AppPreferencesRepository
import cloud.trotter.dashbuddy.core.data.state.AppStateRepository
import cloud.trotter.dashbuddy.core.data.strategy.StrategyRepository
import cloud.trotter.dashbuddy.core.data.vehicle.VehicleRepository
import cloud.trotter.dashbuddy.core.data.vehicle.Garage
import cloud.trotter.dashbuddy.core.data.vehicle.VehiclesUnreadableException
import cloud.trotter.dashbuddy.core.data.vehicle.GarageRepository
import cloud.trotter.dashbuddy.domain.model.vehicle.Vehicle
import cloud.trotter.dashbuddy.domain.model.vehicle.*
import cloud.trotter.dashbuddy.core.data.fuel.FuelPriceRepository
import cloud.trotter.dashbuddy.domain.evaluation.EconomyField
import cloud.trotter.dashbuddy.feature.setup.wizard.model.WizardStep
import cloud.trotter.dashbuddy.domain.evaluation.VehicleEconomyInputs
import cloud.trotter.dashbuddy.domain.evaluation.UserEconomy
import cloud.trotter.dashbuddy.domain.model.vehicle.FuelType
import cloud.trotter.dashbuddy.domain.model.vehicle.VehicleClass
import cloud.trotter.dashbuddy.domain.model.vehicle.VehicleDetails
import cloud.trotter.dashbuddy.domain.model.vehicle.VehicleOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.After
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull
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
    private val garageRepository: GarageRepository = mock()

    private suspend fun stubRepositories() {
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
            vehicleRepository, gasPriceRepository, garageRepository,
        )
        testScheduler.advanceUntilIdle()

        assertEquals(false, viewModel.vehicleDraft.state.value.lookupAllowed)
        assertEquals(emptyList<String>(), viewModel.vehicleDraft.state.value.availableYears)
        verifyNoInteractions(vehicleRepository)
    }

    @Test
    fun `saved vehicle without opt-in shows MPG and finish preserves identity and MPG`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        stubRepositories()
        whenever(appPreferencesRepository.vehicleYear).thenReturn(flowOf("2020"))
        whenever(appPreferencesRepository.vehicleMake).thenReturn(flowOf("Toyota"))
        whenever(appPreferencesRepository.vehicleModel).thenReturn(flowOf("Corolla"))
        whenever(appPreferencesRepository.vehicleTrim).thenReturn(flowOf("Automatic"))
        whenever(appPreferencesRepository.estimatedMpg).thenReturn(flowOf(32f))
        val viewModel = WizardViewModel(
            strategyRepository, appPreferencesRepository, appStateRepository,
            vehicleRepository, gasPriceRepository, garageRepository,
        )
        testScheduler.advanceUntilIdle()

        assertEquals(false, viewModel.vehicleDraft.state.value.lookupAllowed)
        assertEquals(true, viewModel.vehicleDraft.state.value.manualMpgEntry)
        assertEquals("2020", viewModel.vehicleDraft.state.value.year)
        assertEquals("Toyota", viewModel.vehicleDraft.state.value.make)
        assertEquals("Corolla", viewModel.vehicleDraft.state.value.model)
        assertEquals("Automatic", viewModel.vehicleDraft.state.value.trim)
        assertEquals(32f, viewModel.vehicleDraft.state.value.mpg, 0.0f)

        viewModel.saveAndFinish { }
        testScheduler.advanceUntilIdle()

        verify(appPreferencesRepository).mirrorEconomySettings(
            "2020", "Toyota", "Corolla", "Automatic", 32f, false, 3.50f,
        )
        verify(appStateRepository).setFirstRunComplete()
        verifyNoInteractions(vehicleRepository)
    }

    @Test
    fun `manual MPG entry leaves saved identity untouched and never contacts EPA`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        stubRepositories()
        whenever(appPreferencesRepository.vehicleYear).thenReturn(flowOf("2020"))
        whenever(appPreferencesRepository.vehicleMake).thenReturn(flowOf("Toyota"))
        whenever(appPreferencesRepository.vehicleModel).thenReturn(flowOf("Corolla"))
        whenever(appPreferencesRepository.vehicleTrim).thenReturn(flowOf("Automatic"))
        whenever(appPreferencesRepository.estimatedMpg).thenReturn(flowOf(32f))
        val viewModel = WizardViewModel(
            strategyRepository, appPreferencesRepository, appStateRepository,
            vehicleRepository, gasPriceRepository, garageRepository,
        )
        testScheduler.advanceUntilIdle()
        val originalState = viewModel.state.value
        val originalVehicle = viewModel.vehicleDraft.state.value

        viewModel.vehicleDraft.onManualMpgEntry()
        testScheduler.advanceUntilIdle()

        assertEquals(originalState, viewModel.state.value)
        assertEquals(originalVehicle.copy(manualMpgEntry = true), viewModel.vehicleDraft.state.value)
        verifyNoInteractions(vehicleRepository)
        verify(appPreferencesRepository, org.mockito.kotlin.never()).setVehicleLookupAllowed(any())
    }

    @Test
    fun `allowed lookup with no years still allows manual MPG entry`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        stubRepositories()
        whenever(appPreferencesRepository.vehicleLookupAllowed).thenReturn(flowOf(true))
        val viewModel = WizardViewModel(
            strategyRepository, appPreferencesRepository, appStateRepository,
            vehicleRepository, gasPriceRepository, garageRepository,
        )
        testScheduler.advanceUntilIdle()

        assertEquals(true, viewModel.vehicleDraft.state.value.lookupAllowed)
        assertEquals(emptyList<String>(), viewModel.vehicleDraft.state.value.availableYears)
        assertEquals(false, viewModel.vehicleDraft.state.value.manualMpgEntry)
        val originalState = viewModel.state.value
        val originalVehicle = viewModel.vehicleDraft.state.value

        viewModel.vehicleDraft.onManualMpgEntry()
        testScheduler.advanceUntilIdle()

        assertEquals(originalState, viewModel.state.value)
        assertEquals(originalVehicle.copy(manualMpgEntry = true), viewModel.vehicleDraft.state.value)
        viewModel.vehicleDraft.updateEstimatedMpg(32f)
        assertEquals(32f, viewModel.vehicleDraft.state.value.mpg, 0.0f)
        verify(vehicleRepository).getYears()
        verifyNoMoreInteractions(vehicleRepository)
    }

    @Test
    fun `saved lookup opt-in with E-bike never contacts EPA at startup`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        stubRepositories()
        whenever(appPreferencesRepository.vehicleLookupAllowed).thenReturn(flowOf(true))
        whenever(appPreferencesRepository.userEconomy)
            .thenReturn(flowOf(UserEconomy(vehicleClass = VehicleClass.E_BIKE)))
        val viewModel = WizardViewModel(
            strategyRepository, appPreferencesRepository, appStateRepository,
            vehicleRepository, gasPriceRepository, garageRepository,
        )
        testScheduler.advanceUntilIdle()

        assertEquals(true, viewModel.vehicleDraft.state.value.lookupAllowed)
        assertEquals(VehicleClass.E_BIKE, viewModel.vehicleDraft.state.value.vehicleClass)
        assertEquals(emptyList<String>(), viewModel.vehicleDraft.state.value.availableYears)
        verifyNoInteractions(vehicleRepository)

        viewModel.vehicleDraft.updateVehicleClass(VehicleClass.SEDAN)
        testScheduler.advanceUntilIdle()

        verify(vehicleRepository).getYears()
        verifyNoMoreInteractions(vehicleRepository)
    }

    @Test
    fun `saved lookup opt-in fetches years only after settings are applied`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        stubRepositories()
        whenever(appPreferencesRepository.vehicleLookupAllowed).thenReturn(flowOf(true))
        whenever(appPreferencesRepository.vehicleYear).thenReturn(flowOf("2020"))
        whenever(appPreferencesRepository.vehicleMake).thenReturn(flowOf("Toyota"))
        whenever(appPreferencesRepository.vehicleModel).thenReturn(flowOf("Corolla"))
        whenever(appPreferencesRepository.vehicleTrim).thenReturn(flowOf("Automatic"))
        whenever(appPreferencesRepository.estimatedMpg).thenReturn(flowOf(32f))
        whenever(vehicleRepository.getYears()).thenReturn(listOf("2020"))
        val economy = CompletableDeferred<UserEconomy>()
        whenever(appPreferencesRepository.userEconomy).thenReturn(flow { emit(economy.await()) })
        val viewModel = WizardViewModel(
            strategyRepository, appPreferencesRepository, appStateRepository,
            vehicleRepository, gasPriceRepository, garageRepository,
        )
        testScheduler.advanceUntilIdle()
        assertEquals(false, viewModel.vehicleDraft.state.value.lookupAllowed)
        verifyNoInteractions(vehicleRepository)

        economy.complete(UserEconomy())
        testScheduler.advanceUntilIdle()

        assertEquals("2020", viewModel.vehicleDraft.state.value.year)
        assertEquals(true, viewModel.vehicleDraft.state.value.lookupAllowed)
        assertEquals(true, viewModel.vehicleDraft.state.value.manualMpgEntry)
        assertEquals(32f, viewModel.vehicleDraft.state.value.mpg, 0.0f)
        assertEquals(listOf("2020"), viewModel.vehicleDraft.state.value.availableYears)
        verify(vehicleRepository).getYears()
        verifyNoMoreInteractions(vehicleRepository)
    }

    @Test
    fun `a delayed stored lookup denial does not overwrite a new grant`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        stubRepositories()
        val storedPermission = CompletableDeferred<Boolean>()
        whenever(appPreferencesRepository.vehicleLookupAllowed).thenReturn(flow { emit(storedPermission.await()) })
        whenever(vehicleRepository.getYears()).thenReturn(listOf("2020"))
        val viewModel = WizardViewModel(
            strategyRepository, appPreferencesRepository, appStateRepository,
            vehicleRepository, gasPriceRepository, garageRepository,
        )
        testScheduler.runCurrent()

        viewModel.allowVehicleLookup()
        testScheduler.runCurrent()
        assertTrue(viewModel.vehicleDraft.state.value.lookupAllowed)
        storedPermission.complete(false)
        testScheduler.advanceUntilIdle()

        assertTrue(viewModel.vehicleDraft.state.value.lookupAllowed)
        assertEquals(listOf("2020"), viewModel.vehicleDraft.state.value.availableYears)
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
            vehicleRepository, gasPriceRepository, garageRepository,
        )
        testScheduler.advanceUntilIdle()

        viewModel.allowVehicleLookup()
        testScheduler.advanceUntilIdle()
        assertEquals(false, viewModel.vehicleDraft.state.value.lookupAllowed)
        verifyNoInteractions(vehicleRepository)

        saved.complete(Unit)
        testScheduler.advanceUntilIdle()

        assertEquals(true, viewModel.vehicleDraft.state.value.lookupAllowed)
        assertEquals(listOf("2020"), viewModel.vehicleDraft.state.value.availableYears)
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
            vehicleRepository, gasPriceRepository, garageRepository,
        )
        testScheduler.advanceUntilIdle()

        viewModel.vehicleDraft.onYearSelected("2020")
        viewModel.vehicleDraft.onMakeSelected("Toyota")
        viewModel.vehicleDraft.onModelSelected("Corolla")
        viewModel.vehicleDraft.onTrimSelected("Automatic")
        viewModel.vehicleDraft.onManualMpgEntry()
        viewModel.vehicleDraft.updateEstimatedMpg(32f)
        testScheduler.advanceUntilIdle()

        assertEquals(false, viewModel.vehicleDraft.state.value.lookupAllowed)
        assertEquals("", viewModel.vehicleDraft.state.value.make)
        assertEquals(true, viewModel.vehicleDraft.state.value.manualMpgEntry)
        assertEquals(32f, viewModel.vehicleDraft.state.value.mpg, 0.0f)
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
            vehicleRepository, gasPriceRepository, garageRepository,
        )
        testScheduler.advanceUntilIdle()

        viewModel.allowVehicleLookup()
        testScheduler.advanceUntilIdle()
        viewModel.vehicleDraft.onYearSelected("2020")
        testScheduler.advanceUntilIdle()
        viewModel.vehicleDraft.onMakeSelected("Toyota")
        testScheduler.advanceUntilIdle()
        viewModel.vehicleDraft.onModelSelected("Corolla")
        testScheduler.advanceUntilIdle()
        viewModel.vehicleDraft.onTrimSelected("Automatic")
        testScheduler.advanceUntilIdle()

        assertEquals(32f, viewModel.vehicleDraft.state.value.mpg, 0.0f)
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
    fun `a trim lookup that answers after a type change changes nothing`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        stubRepositories()
        whenever(vehicleRepository.getYears()).thenReturn(listOf("2024"))
        whenever(vehicleRepository.getMakes("2024")).thenReturn(listOf("Hyundai"))
        whenever(vehicleRepository.getModels("2024", "Hyundai")).thenReturn(listOf("Ioniq 6"))
        whenever(vehicleRepository.getVehicleOptions("2024", "Hyundai", "Ioniq 6"))
            .thenReturn(listOf(VehicleOption("ev", "Electric")))
        whenever(vehicleRepository.getVehicleDetails("ev")).doSuspendableAnswer {
            kotlinx.coroutines.delay(5_000)
            VehicleDetails(combinedMpg = 132f, fuelType = FuelType.ELECTRICITY)
        }
        val vm = WizardViewModel(strategyRepository, appPreferencesRepository, appStateRepository,
            vehicleRepository, gasPriceRepository, garageRepository)
        testScheduler.advanceUntilIdle()
        vm.allowVehicleLookup()
        testScheduler.advanceUntilIdle()
        vm.vehicleDraft.onYearSelected("2024")
        testScheduler.advanceUntilIdle()
        vm.vehicleDraft.onMakeSelected("Hyundai")
        testScheduler.advanceUntilIdle()
        vm.vehicleDraft.onModelSelected("Ioniq 6")
        testScheduler.advanceUntilIdle()
        vm.vehicleDraft.onTrimSelected("Electric")
        vm.vehicleDraft.updateVehicleType(VehicleType.BICYCLE)
        testScheduler.advanceUntilIdle()

        val vehicle = vm.vehicleDraft.state.value
        assertTrue(vehicle.profile is Bicycle)
        assertEquals(false, EconomyField.KWH_PER_100_MI in vehicle.userSetFields)
        assertEquals(false, EconomyField.VEHICLE_MPG in vehicle.userSetFields)
    }

    @Test
    fun `an EPA electric trim converts MPGe and saves an electric profile`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        stubRepositories()
        whenever(vehicleRepository.getYears()).thenReturn(listOf("2024"))
        whenever(vehicleRepository.getMakes("2024")).thenReturn(listOf("Hyundai"))
        whenever(vehicleRepository.getModels("2024", "Hyundai")).thenReturn(listOf("Ioniq 6"))
        whenever(vehicleRepository.getVehicleOptions("2024", "Hyundai", "Ioniq 6"))
            .thenReturn(listOf(VehicleOption("ev", "Electric")))
        whenever(vehicleRepository.getVehicleDetails("ev"))
            .thenReturn(VehicleDetails(combinedMpg = 132f, fuelType = FuelType.ELECTRICITY))
        val vm = WizardViewModel(strategyRepository, appPreferencesRepository, appStateRepository,
            vehicleRepository, gasPriceRepository, garageRepository)
        testScheduler.advanceUntilIdle()
        vm.allowVehicleLookup()
        testScheduler.advanceUntilIdle()
        vm.vehicleDraft.onYearSelected("2024")
        testScheduler.advanceUntilIdle()
        vm.vehicleDraft.onMakeSelected("Hyundai")
        testScheduler.advanceUntilIdle()
        vm.vehicleDraft.onModelSelected("Ioniq 6")
        testScheduler.advanceUntilIdle()
        vm.vehicleDraft.updateEstimatedMpg(30f)
        vm.updateTireCost(900.0); vm.updateTireLifetime(45_000.0)
        vm.updateInsuranceDelta(25.0)
        vm.updateFuelType(FuelType.PREMIUM)
        vm.updateGasPrice(4.25f)
        vm.vehicleDraft.onTrimSelected("Electric")
        testScheduler.advanceUntilIdle()

        val state = vm.state.value
        val vehicle = vm.vehicleDraft.state.value
        assertTrue(vehicle.profile is ElectricCar)
        assertEquals(25.53, (vehicle.profile as ElectricCar).kwhPer100Mi!!, 0.01)
        assertEquals(25.53, vehicle.kwhPer100Mi, 0.01)
        assertTrue(EconomyField.VEHICLE_MPG !in vehicle.userSetFields)
        assertTrue(EconomyField.KWH_PER_100_MI in vehicle.userSetFields)
        assertTrue(WizardStep.GAS_PRICE !in vm.steps.value)
        assertEquals(FuelType.PREMIUM, state.fuelType)
        assertEquals(4.25f, state.gasPrice, 0.0f)
        assertEquals(VehiclePresets.electricCar.fixed.purchasePrice!!, state.purchasePrice, 0.0)
        assertEquals(VehiclePresets.electricCar.wear.brakesIntervalMi!!, state.brakesIntervalMi, 0.0)
        assertEquals(900.0, state.toVehicleProfile(vehicle).wear.tireSetCost)
        assertEquals(25.0, state.toVehicleProfile(vehicle).fixed.insuranceDeltaPerMonth)
        assertNull(vehicle.profile.gasFuel)
        vm.saveAndFinish { }
        testScheduler.advanceUntilIdle()
        val saved = argumentCaptor<VehicleProfile>()
        verify(garageRepository).addVehicle(isNull(), any(), saved.capture(), any())
        assertTrue(saved.firstValue is ElectricCar)
        assertEquals(25.53, (saved.firstValue as ElectricCar).kwhPer100Mi!!, 0.01)
        assertEquals("2024", saved.firstValue.year)
        assertEquals("Hyundai", saved.firstValue.make)
        assertEquals("Ioniq 6", saved.firstValue.model)
        assertNull(saved.firstValue.gasFuel)
        verify(appPreferencesRepository).mirrorFuelType(FuelType.PREMIUM)
    }

    @Test
    fun `an EPA electric trim without MPGe follows the electric consumption preset`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        stubRepositories()
        whenever(appPreferencesRepository.vehicleLookupAllowed).thenReturn(flowOf(true))
        whenever(vehicleRepository.getYears()).thenReturn(listOf("2024"))
        whenever(appPreferencesRepository.vehicleYear).thenReturn(flowOf("2024"))
        whenever(appPreferencesRepository.vehicleMake).thenReturn(flowOf("Hyundai"))
        whenever(vehicleRepository.getVehicleOptions("2024", "Hyundai", "Ioniq 6"))
            .thenReturn(listOf(VehicleOption("ev", "Electric")))
        whenever(vehicleRepository.getVehicleDetails("ev"))
            .thenReturn(VehicleDetails(combinedMpg = null, fuelType = FuelType.ELECTRICITY))
        val vm = WizardViewModel(strategyRepository, appPreferencesRepository, appStateRepository,
            vehicleRepository, gasPriceRepository, garageRepository)
        testScheduler.advanceUntilIdle()
        vm.vehicleDraft.onModelSelected("Ioniq 6")
        testScheduler.advanceUntilIdle()
        vm.vehicleDraft.updateEstimatedMpg(30f)
        vm.vehicleDraft.onTrimSelected("Electric")
        testScheduler.advanceUntilIdle()
        val vehicle = vm.vehicleDraft.state.value
        assertTrue(vehicle.profile is ElectricCar)
        assertNull((vehicle.profile as ElectricCar).kwhPer100Mi)
        assertEquals(VehiclePresets.EV_KWH_PER_100_MI, vehicle.kwhPer100Mi, 0.0)
        assertTrue(EconomyField.KWH_PER_100_MI !in vehicle.userSetFields)
        assertTrue(EconomyField.VEHICLE_MPG !in vehicle.userSetFields)
        assertTrue(WizardStep.GAS_PRICE !in vm.steps.value)
    }

    @Test
    fun `skip writes nothing but the first-run flag`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        stubRepositories()
        val viewModel = WizardViewModel(
            strategyRepository, appPreferencesRepository, appStateRepository,
            vehicleRepository, gasPriceRepository, garageRepository,
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
            vehicleRepository, gasPriceRepository, garageRepository,
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
            vehicleRepository, gasPriceRepository, garageRepository,
        )
        testScheduler.advanceUntilIdle()

        assertEquals(false, viewModel.state.value.isGasPriceAuto)
        assertEquals(3.50f, viewModel.state.value.gasPrice, 0.0f)
        viewModel.saveAndFinish { }
        testScheduler.advanceUntilIdle()

        verify(appPreferencesRepository).mirrorEconomySettings(
            "", "", "", "", VehicleClass.SEDAN.defaultMpg.toFloat(), false, 3.50f,
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
            vehicleRepository, gasPriceRepository, garageRepository,
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
            vehicleRepository, gasPriceRepository, garageRepository,
        )
        testScheduler.advanceUntilIdle()
        assertEquals(false, viewModel.state.value.isGasPriceAuto)
        verifyNoInteractions(gasPriceRepository)

        economy.complete(UserEconomy())
        testScheduler.advanceUntilIdle()
        FuelType.entries.filter { it != FuelType.ELECTRICITY }.forEach { verify(gasPriceRepository).fetchGasPriceOnly(it) }
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
            vehicleRepository, gasPriceRepository, garageRepository,
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
            vehicleRepository, gasPriceRepository, garageRepository,
        )
        testScheduler.advanceUntilIdle()
        verifyNoInteractions(gasPriceRepository)
        auto.complete(true)
        testScheduler.advanceUntilIdle()
        assertEquals(true, viewModel.state.value.isGasPriceAuto)
        FuelType.entries.filter { it != FuelType.ELECTRICITY }.forEach { verify(gasPriceRepository).fetchGasPriceOnly(it) }
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
            vehicleRepository, gasPriceRepository, garageRepository,
        )
        testScheduler.advanceUntilIdle()
        auto.complete(false)
        testScheduler.advanceUntilIdle()
        verifyNoInteractions(gasPriceRepository)
        viewModel.toggleAutoGasPrice(true)
        testScheduler.advanceUntilIdle()
        assertEquals(true, viewModel.state.value.isGasPriceAuto)
        FuelType.entries.filter { it != FuelType.ELECTRICITY }.forEach { verify(gasPriceRepository).fetchGasPriceOnly(it) }
        verifyNoMoreInteractions(gasPriceRepository)
    }

    @Test
    fun `tapping the selected type keeps every typed override`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        stubRepositories()
        val active = Vehicle("active", "Tuned car", 1L, GasCar(GasCarClass.SUV, mpg = 28.0,
            wear = WearProfile(tireSetCost = 950.0), fixed = FixedCosts(false, insuranceDeltaPerMonth = 35.0)))
        whenever(garageRepository.garage).thenReturn(flowOf(Garage(listOf(active), active.id, true)))
        val shared = cloud.trotter.dashbuddy.domain.evaluation.SharedEconomy()
        whenever(appPreferencesRepository.userEconomy).thenReturn(flowOf(UserEconomy.from(
            VehicleEconomyInputs.from(active.profile, shared), shared)))
        val vm = WizardViewModel(strategyRepository, appPreferencesRepository, appStateRepository,
            vehicleRepository, gasPriceRepository, garageRepository)
        testScheduler.advanceUntilIdle()
        val before = vm.state.value
        val vehicleBefore = vm.vehicleDraft.state.value
        vm.vehicleDraft.updateVehicleType(VehicleType.CAR)
        assertEquals(before, vm.state.value)
        assertEquals(vehicleBefore, vm.vehicleDraft.state.value)
        vm.saveAndFinish { }
        testScheduler.advanceUntilIdle()
        val saved = argumentCaptor<VehicleProfile>()
        verify(garageRepository).updateProfile(eq(active.id), saved.capture())
        assertEquals(28.0, saved.firstValue.gasMpg)
        assertEquals(950.0, saved.firstValue.wear.tireSetCost)
        assertEquals(false, saved.firstValue.fixed.includeDepreciation)
        assertEquals(35.0, saved.firstValue.fixed.insuranceDeltaPerMonth)
    }

    @Test
    fun `a half-set pair keeps the other half following the preset across a class change`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        stubRepositories()
        val active = Vehicle("active", "Migrated sedan", 1L, GasCar(GasCarClass.SEDAN,
            wear = WearProfile(tireSetCost = 900.0)))
        whenever(garageRepository.garage).thenReturn(flowOf(Garage(listOf(active), active.id, true)))
        val shared = cloud.trotter.dashbuddy.domain.evaluation.SharedEconomy()
        whenever(appPreferencesRepository.userEconomy).thenReturn(flowOf(UserEconomy.from(
            VehicleEconomyInputs.from(active.profile, shared), shared)))
        val vm = WizardViewModel(strategyRepository, appPreferencesRepository, appStateRepository,
            vehicleRepository, gasPriceRepository, garageRepository)
        testScheduler.advanceUntilIdle()

        vm.vehicleDraft.updateVehicleClass(VehicleClass.SUV)

        val state = vm.state.value
        val vehicle = vm.vehicleDraft.state.value
        assertNull(state.toVehicleProfile(vehicle).wear.tireLifetimeMi)
        assertEquals(900.0, state.toVehicleProfile(vehicle).wear.tireSetCost)
        assertEquals(VehiclePresets.cars.getValue(GasCarClass.SUV).wear.tireLifetimeMi!!,
            state.toUserEconomy(vehicle).tireLifetimeMi, 0.0)
    }

    @Test
    fun `wizard saves electric scooter and bicycle without sentinel MPG`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        stubRepositories()
        val vm = WizardViewModel(strategyRepository, appPreferencesRepository, appStateRepository,
            vehicleRepository, gasPriceRepository, garageRepository)
        testScheduler.advanceUntilIdle()
        vm.vehicleDraft.updateVehicleType(VehicleType.SCOOTER)
        vm.vehicleDraft.updatePowertrain(Powertrain.ELECTRIC)
        vm.vehicleDraft.updateConsumption(2.5)
        vm.vehicleDraft.updateElectricityPrice(.22)
        vm.saveAndFinish { }
        testScheduler.advanceUntilIdle()
        val scooter = argumentCaptor<VehicleProfile>()
        verify(garageRepository).addVehicle(isNull(), any(), scooter.capture(), any())
        assertTrue(scooter.firstValue is ScooterMoped)
        assertEquals(Powertrain.ELECTRIC, (scooter.firstValue as ScooterMoped).powertrain)
        assertEquals(2.5, (scooter.firstValue as ScooterMoped).kwhPer100Mi)
        assertEquals(.0055, vm.state.value.toUserEconomy(vm.vehicleDraft.state.value).fuelCostPerMile, 1e-12)
        clearInvocations(garageRepository)
        vm.vehicleDraft.updateVehicleType(VehicleType.BICYCLE)
        vm.saveAndFinish { }
        testScheduler.advanceUntilIdle()
        val bicycle = argumentCaptor<VehicleProfile>()
        verify(garageRepository).addVehicle(isNull(), any(), bicycle.capture(), any())
        assertTrue(bicycle.firstValue is Bicycle)
        assertNull(bicycle.firstValue.gasMpg)
    }

    @Test
    fun `the vehicle step writes the active vehicle's profile`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        stubRepositories()
        val active = Vehicle(
            id = "active-id",
            name = "My car",
            createdAt = 1L,
            profile = LegacyProfile(
                year = "2021", make = "Honda", model = "Civic",
                mpg = 31.0, vehicleClass = VehicleClass.SEDAN, fuelType = FuelType.REGULAR,
            ).toTyped(),
        )
        whenever(garageRepository.garage).thenReturn(flowOf(Garage(listOf(active), active.id, true)))
        val viewModel = WizardViewModel(
            strategyRepository, appPreferencesRepository, appStateRepository,
            vehicleRepository, gasPriceRepository, garageRepository,
        )
        testScheduler.advanceUntilIdle()
        assertEquals("Honda", viewModel.vehicleDraft.state.value.make)

        viewModel.vehicleDraft.updateVehicleClass(VehicleClass.SUV)
        viewModel.vehicleDraft.updateEstimatedMpg(28f)
        viewModel.updateFuelType(FuelType.PREMIUM)
        viewModel.saveAndFinish { }
        testScheduler.advanceUntilIdle()

        val profile = argumentCaptor<VehicleProfile>()
        verify(garageRepository).updateProfile(eq(active.id), profile.capture())
        val saved = profile.firstValue
        assertEquals("2021", saved.year)
        assertEquals("Honda", saved.make)
        assertEquals("Civic", saved.model)
        assertEquals(VehicleClass.SUV, saved.legacyClass)
        assertEquals(28.0, saved.gasMpg!!, 0.0)
        assertEquals(FuelType.PREMIUM, saved.gasFuel)
        verify(garageRepository, org.mockito.kotlin.never()).addVehicle(isNull(), any(), any(), any())
    }

    @Test
    fun `a switch during the save does not retarget it`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        stubRepositories()
        val first = Vehicle("first-id", "First", 1L, LegacyProfile(make = "Honda", mpg = 31.0).toTyped())
        val second = Vehicle("second-id", "Second", 2L, LegacyProfile(make = "Toyota", mpg = 50.0).toTyped())
        val garage = MutableStateFlow(Garage(listOf(first, second), first.id, true))
        whenever(garageRepository.garage).thenReturn(garage)
        val saving = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        whenever(garageRepository.updateProfile(any(), any<VehicleProfile>())).doSuspendableAnswer { call ->
            val id = call.getArgument<String>(0)
            val profile = call.getArgument<VehicleProfile>(1)
            saving.complete(Unit)
            resume.await()
            garage.value = garage.value.copy(vehicles = garage.value.vehicles.map {
                if (it.id == id) it.copy(profile = profile) else it
            })
            true
        }
        val viewModel = WizardViewModel(
            strategyRepository, appPreferencesRepository, appStateRepository,
            vehicleRepository, gasPriceRepository, garageRepository,
        )
        testScheduler.advanceUntilIdle()
        viewModel.vehicleDraft.updateVehicleClass(VehicleClass.SUV)
        viewModel.vehicleDraft.updateEstimatedMpg(28f)
        viewModel.updateFuelType(FuelType.PREMIUM)
        viewModel.updateTireCost(720.0); viewModel.updateTireLifetime(48_000.0)
        var completed = false
        viewModel.saveAndFinish { completed = true }
        saving.await()
        garage.value = garage.value.copy(activeId = second.id)
        resume.complete(Unit)
        testScheduler.advanceUntilIdle()

        assertEquals(true, completed)
        val saved = garage.value.vehicles.first { it.id == first.id }.profile
        assertEquals("Honda", saved.make)
        assertEquals(VehicleClass.SUV, saved.legacyClass)
        assertEquals(28.0, saved.gasMpg)
        assertEquals(FuelType.PREMIUM, saved.gasFuel)
        assertEquals(720.0, saved.wear.tireSetCost)
        assertEquals(viewModel.state.value.tireLifetimeMi, saved.wear.tireLifetimeMi)
        assertEquals(second, garage.value.vehicles.first { it.id == second.id })
        assertEquals(second.id, garage.value.activeId)
        verify(garageRepository).updateProfile(eq(first.id), any<VehicleProfile>())
        verify(appPreferencesRepository).mirrorFuelType(FuelType.PREMIUM)
        verify(appPreferencesRepository).mirrorVehicleClass(VehicleClass.SUV)
        verify(appPreferencesRepository).mirrorUserSetEconomy(any())
    }

    @Test
    fun `wizard creates an active vehicle when the garage only contains a retired vehicle`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        stubRepositories()
        val retired = Vehicle("retired-id", "Old car", 1L, retiredAt = 2L)
        whenever(garageRepository.garage).thenReturn(flowOf(Garage(listOf(retired), null, true)))
        val viewModel = WizardViewModel(
            strategyRepository, appPreferencesRepository, appStateRepository,
            vehicleRepository, gasPriceRepository, garageRepository,
        )
        testScheduler.advanceUntilIdle()

        viewModel.saveAndFinish { }
        testScheduler.advanceUntilIdle()

        verify(garageRepository).addVehicle(isNull(), any(), any(), any())
        verify(garageRepository, org.mockito.kotlin.never()).updateProfile(any(), any<VehicleProfile>())
    }

    @Test
    fun `no active vehicle makes the wizard's car the active one`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        stubRepositories()
        val parked = Vehicle("parked-id", "Parked", 1L)
        whenever(garageRepository.garage).thenReturn(flowOf(Garage(listOf(parked), null, true)))
        val viewModel = WizardViewModel(
            strategyRepository, appPreferencesRepository, appStateRepository,
            vehicleRepository, gasPriceRepository, garageRepository,
        )
        testScheduler.advanceUntilIdle()

        viewModel.vehicleDraft.updateVehicleClass(VehicleClass.SUV)
        viewModel.vehicleDraft.updateEstimatedMpg(28f)
        viewModel.updateTireCost(720.0); viewModel.updateTireLifetime(48_000.0)
        viewModel.saveAndFinish { }
        testScheduler.advanceUntilIdle()

        val profile = argumentCaptor<VehicleProfile>()
        verify(garageRepository).addVehicle(isNull(), any(), profile.capture(), any())
        assertEquals(VehicleClass.SUV, profile.firstValue.legacyClass)
        assertEquals(28.0, profile.firstValue.gasMpg)
        assertEquals(720.0, profile.firstValue.wear.tireSetCost)
        assertEquals(48_000.0, profile.firstValue.wear.tireLifetimeMi)
        verify(garageRepository, org.mockito.kotlin.never()).updateProfile(any(), any<VehicleProfile>())
        verify(appStateRepository).setFirstRunComplete()
    }

    @Test
    fun `save targets the vehicle active when save starts`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        stubRepositories()
        val first = Vehicle("first-id", "First", 1L)
        val second = Vehicle("second-id", "Second", 2L)
        whenever(garageRepository.garage).thenReturn(flowOf(Garage(listOf(first, second), first.id, true)))
        val viewModel = WizardViewModel(
            strategyRepository, appPreferencesRepository, appStateRepository,
            vehicleRepository, gasPriceRepository, garageRepository,
        )
        testScheduler.advanceUntilIdle()
        whenever(garageRepository.garage).thenReturn(flowOf(Garage(listOf(first, second), second.id, true)))

        viewModel.saveAndFinish { }
        testScheduler.advanceUntilIdle()

        verify(garageRepository).updateProfile(eq(second.id), any<VehicleProfile>())
        verify(garageRepository, org.mockito.kotlin.never()).updateProfile(eq(first.id), any<VehicleProfile>())
        verify(garageRepository, org.mockito.kotlin.never()).addVehicle(isNull(), any(), any(), any())
        verify(appStateRepository).setFirstRunComplete()
    }

    @Test
    fun `an unreadable garage does not stop the wizard from finishing`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        stubRepositories()
        whenever(garageRepository.garage).thenReturn(flowOf(Garage(emptyList(), null, false)))
        val viewModel = WizardViewModel(
            strategyRepository, appPreferencesRepository, appStateRepository,
            vehicleRepository, gasPriceRepository, garageRepository,
        )
        testScheduler.advanceUntilIdle()
        var completed = false
        viewModel.saveAndFinish { completed = true }
        testScheduler.advanceUntilIdle()

        assertEquals(true, completed)
        verify(garageRepository, org.mockito.kotlin.never()).addVehicle(isNull(), any(), any(), any())
        verify(garageRepository, org.mockito.kotlin.never()).updateProfile(any(), any<VehicleProfile>())
        verify(appPreferencesRepository).mirrorUserSetEconomy(any())
        verify(appPreferencesRepository).mirrorEconomySettings(any(), any(), any(), any(), any(), any(), any())
        verify(appStateRepository).setFirstRunComplete()
        assertEquals(listOf(Unit), viewModel.vehicleSaveFailed.replayCache)
        viewModel.vehicleSaveFailureHandled()
        assertEquals(emptyList<Unit>(), viewModel.vehicleSaveFailed.replayCache)
    }

    @Test
    fun `a failed garage write still mirrors and finishes`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        stubRepositories()
        val active = Vehicle("active", null, 1L)
        whenever(garageRepository.garage).thenReturn(flowOf(Garage(listOf(active), active.id, true)))
        whenever(garageRepository.updateProfile(any(), any<VehicleProfile>()))
            .thenThrow(IllegalStateException("private vehicle details"))
        val viewModel = WizardViewModel(
            strategyRepository, appPreferencesRepository, appStateRepository,
            vehicleRepository, gasPriceRepository, garageRepository,
        )
        testScheduler.advanceUntilIdle()
        var completed = false
        viewModel.saveAndFinish { completed = true }
        testScheduler.advanceUntilIdle()
        assertEquals(true, completed)
        verify(appPreferencesRepository).mirrorUserSetEconomy(any())
        verify(appStateRepository).setFirstRunComplete()
        assertEquals(listOf(Unit), viewModel.vehicleSaveFailed.replayCache)
    }

    @Test
    fun `a failed vehicle creation still finishes but cancellation propagates`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        stubRepositories()
        whenever(garageRepository.addVehicle(isNull(), any(), any(), any()))
            .thenThrow(VehiclesUnreadableException())
        val viewModel = WizardViewModel(
            strategyRepository, appPreferencesRepository, appStateRepository,
            vehicleRepository, gasPriceRepository, garageRepository,
        )
        testScheduler.advanceUntilIdle()
        var completions = 0
        viewModel.saveAndFinish { completions++ }
        testScheduler.advanceUntilIdle()
        assertEquals(1, completions)
        verify(appStateRepository).setFirstRunComplete()
        viewModel.vehicleSaveFailureHandled()
        clearInvocations(appPreferencesRepository, appStateRepository)
        whenever(garageRepository.addVehicle(isNull(), any(), any(), any()))
            .thenThrow(CancellationException("cancelled"))
        viewModel.saveAndFinish { completions++ }
        testScheduler.advanceUntilIdle()
        assertEquals(1, completions)
        verifyNoInteractions(appPreferencesRepository, appStateRepository)
        assertEquals(emptyList<Unit>(), viewModel.vehicleSaveFailed.replayCache)
    }

    @Test
    fun `an active profile with zero mpg uses the economy fallback`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        stubRepositories()
        val active = Vehicle("active", null, 1L, LegacyProfile(mpg = 0.0).toTyped())
        whenever(garageRepository.garage).thenReturn(flowOf(Garage(listOf(active), active.id, true)))
        whenever(appPreferencesRepository.userEconomy).thenReturn(flowOf(UserEconomy(vehicleMpg = 32.0)))
        val viewModel = WizardViewModel(
            strategyRepository, appPreferencesRepository, appStateRepository,
            vehicleRepository, gasPriceRepository, garageRepository,
        )
        testScheduler.advanceUntilIdle()
        assertEquals(32f, viewModel.vehicleDraft.state.value.mpg, 0f)
    }
    @Test fun `rerunning setup with a different type adds a new active vehicle and preserves the old identity`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        stubRepositories()
        val old = Vehicle("old", "Original", 1, GasCar(year = "2020", make = "Honda", model = "Civic"))
        whenever(garageRepository.garage).thenReturn(flowOf(Garage(listOf(old), old.id, true)))
        val vm = WizardViewModel(strategyRepository, appPreferencesRepository, appStateRepository,
            vehicleRepository, gasPriceRepository, garageRepository)
        testScheduler.advanceUntilIdle()
        vm.vehicleDraft.updateVehicleType(VehicleType.BICYCLE)
        vm.saveAndFinish {}
        testScheduler.advanceUntilIdle()
        verify(garageRepository).addVehicle(isNull(), any(), eq(Bicycle()),
            eq(cloud.trotter.dashbuddy.core.data.vehicle.ActivateOn.ALWAYS))
        verify(garageRepository, org.mockito.kotlin.never()).updateProfile(any(), any<VehicleProfile>())
        assertEquals("2020", old.profile.year)
        assertEquals("Honda", old.profile.make)
        assertEquals("Civic", old.profile.model)
    }

    @Test fun `rerun with a different year make model adds instead of replacing identity`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        stubRepositories()
        val old = Vehicle("old", "Original", 1, GasCar(year = "2020", make = "Honda", model = "Civic"))
        whenever(garageRepository.garage).thenReturn(flowOf(Garage(listOf(old), old.id, true)))
        val vm = WizardViewModel(strategyRepository, appPreferencesRepository, appStateRepository,
            vehicleRepository, gasPriceRepository, garageRepository)
        testScheduler.advanceUntilIdle()
        // The draft can select a different identity after opting into lookup.
        vm.vehicleDraft.load(vm.vehicleDraft.state.value.copy(year = "2021", make = "Toyota", model = "Corolla"))
        vm.saveAndFinish {}
        testScheduler.advanceUntilIdle()
        val profile = argumentCaptor<VehicleProfile>()
        verify(garageRepository).addVehicle(isNull(), any(), profile.capture(),
            eq(cloud.trotter.dashbuddy.core.data.vehicle.ActivateOn.ALWAYS))
        assertEquals("2021", profile.firstValue.year)
        assertEquals("Toyota", profile.firstValue.make)
        assertEquals("Corolla", profile.firstValue.model)
        verify(garageRepository, org.mockito.kotlin.never()).updateProfile(any(), any<VehicleProfile>())
    }

}
