package cloud.trotter.log.gig.feature.setup.wizard

import cloud.trotter.log.gig.core.data.fuel.FuelPriceRepository
import cloud.trotter.log.gig.core.data.settings.AppPreferencesRepository
import cloud.trotter.log.gig.core.data.state.AppStateRepository
import cloud.trotter.log.gig.core.data.strategy.StrategyRepository
import cloud.trotter.log.gig.core.data.vehicle.Garage
import cloud.trotter.log.gig.core.data.vehicle.GarageRepository
import cloud.trotter.log.gig.core.data.vehicle.VehicleRepository
import cloud.trotter.log.gig.core.data.vehicle.VehiclesUnreadableException
import cloud.trotter.log.gig.domain.evaluation.UserEconomy
import cloud.trotter.log.gig.domain.model.vehicle.FuelType
import cloud.trotter.log.gig.domain.model.vehicle.Vehicle
import cloud.trotter.log.gig.domain.model.vehicle.VehicleClass
import cloud.trotter.log.gig.domain.model.vehicle.VehicleProfile
import cloud.trotter.log.gig.feature.setup.wizard.model.SaveFailure
import cloud.trotter.log.gig.feature.setup.wizard.model.SaveStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.isNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoMoreInteractions
import org.mockito.kotlin.whenever

/**
 * #1473: the wizard reports its save's outcome in state. "Saved" only after every write finished; a failure leaves
 * the answers as they were, and a Retry after a failure that already added the vehicle does not add a second one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WizardSaveStatusTest {
    private val strategyRepository: StrategyRepository = mock()
    private val appPreferencesRepository: AppPreferencesRepository = mock()
    private val appStateRepository: AppStateRepository = mock()
    private val vehicleRepository: VehicleRepository = mock()
    private val gasPriceRepository: FuelPriceRepository = mock()
    private val garageRepository: GarageRepository = mock()
    private val garageFlow = MutableStateFlow(Garage(emptyList(), null, true))

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** A wizard over an in-memory garage: an add appends the vehicle and makes it active, as the repository does. */
    private suspend fun TestScope.newWizard(): WizardViewModel {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        whenever(garageRepository.garage).thenReturn(garageFlow)
        whenever(garageRepository.addVehicle(isNull(), any(), any(), any())).doSuspendableAnswer { call ->
            val added = Vehicle("new-${garageFlow.value.vehicles.size}", null, 1L, call.getArgument<VehicleProfile>(2))
            garageFlow.value = Garage(garageFlow.value.vehicles + added, added.id, true)
            added
        }
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
        whenever(appPreferencesRepository.gasPriceFetchedAt).thenReturn(flowOf(null))
        whenever(appPreferencesRepository.userEconomy).thenReturn(flowOf(UserEconomy()))
        whenever(strategyRepository.protectStatsMode).thenReturn(flowOf(false))
        whenever(strategyRepository.scoringRules).thenReturn(flowOf(emptyList()))
        whenever(vehicleRepository.getYears()).thenReturn(emptyList())
        val vm = WizardViewModel(strategyRepository, appPreferencesRepository, appStateRepository,
            vehicleRepository, gasPriceRepository, garageRepository)
        testScheduler.advanceUntilIdle()
        return vm
    }

    /** Every status the wizard reports from now on, unconflated. */
    private fun TestScope.statusesOf(vm: WizardViewModel): List<SaveStatus> {
        val seen = mutableListOf<SaveStatus>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.saveStatus.toList(seen) }
        return seen
    }

    @Test
    fun `a save reports Saving until every write finishes, then Saved`() = runTest {
        val vm = newWizard()
        val seen = statusesOf(vm)

        vm.save()
        assertEquals(SaveStatus.Saving, vm.saveStatus.value)
        testScheduler.advanceUntilIdle()

        assertEquals(listOf(SaveStatus.Idle, SaveStatus.Saving, SaveStatus.Saved), seen)
        verify(appStateRepository).setFirstRunComplete()
    }

    @Test
    fun `a second save while one runs is ignored`() = runTest {
        val vm = newWizard()

        vm.save()
        vm.save()
        testScheduler.advanceUntilIdle()

        verify(garageRepository, times(1)).addVehicle(isNull(), any(), any(), any())
        verify(appStateRepository, times(1)).setFirstRunComplete()
        assertEquals(1, garageFlow.value.vehicles.size)
    }

    @Test
    fun `a vehicle failure reports Failed and never Saved`() = runTest {
        val vm = newWizard()
        whenever(garageRepository.addVehicle(isNull(), any(), any(), any())).thenThrow(VehiclesUnreadableException())
        val seen = statusesOf(vm)

        vm.save()
        testScheduler.advanceUntilIdle()

        assertEquals(SaveStatus.Failed(SaveFailure.VEHICLE), vm.saveStatus.value)
        assertFalse(SaveStatus.Saved in seen)
    }

    @Test
    fun `a settings write that throws reports Failed and leaves the answers as they were`() = runTest {
        val vm = newWizard()
        vm.updateMinPayout(7f)
        vm.vehicleDraft.updateVehicleClass(VehicleClass.SUV)
        whenever(appPreferencesRepository.mirrorEconomySettings(any(), any(), any(), any(), any(), any(), any(), anyOrNull()))
            .thenThrow(IllegalStateException("disk full"))
        val answers = vm.state.value
        val vehicle = vm.vehicleDraft.state.value
        val seen = statusesOf(vm)

        vm.save()
        testScheduler.advanceUntilIdle()

        assertEquals(SaveStatus.Failed(SaveFailure.SETTINGS), vm.saveStatus.value)
        assertFalse(SaveStatus.Saved in seen)
        assertEquals(answers, vm.state.value)
        assertEquals(vehicle, vm.vehicleDraft.state.value)
        verify(appStateRepository, never()).setFirstRunComplete()
    }

    @Test
    fun `a Retry after a settings failure saves with one vehicle`() = runTest {
        val vm = newWizard()
        whenever(appPreferencesRepository.mirrorFuelType(any()))
            .thenThrow(IllegalStateException("disk full"))
            .thenReturn(Unit)

        vm.save()
        testScheduler.advanceUntilIdle()
        assertEquals(SaveStatus.Failed(SaveFailure.SETTINGS), vm.saveStatus.value)
        assertEquals(1, garageFlow.value.vehicles.size)

        vm.save()
        testScheduler.advanceUntilIdle()

        assertEquals(SaveStatus.Saved, vm.saveStatus.value)
        assertEquals(1, garageFlow.value.vehicles.size)
        verify(garageRepository, times(1)).addVehicle(isNull(), any(), any(), any())
        verify(garageRepository).updateProfile(garageFlow.value.vehicles.single().id, garageFlow.value.vehicles.single().profile)
        verify(appStateRepository, times(1)).setFirstRunComplete()
    }

    @Test
    fun `a Retry after a vehicle failure saves with one vehicle`() = runTest {
        val vm = newWizard()
        var adds = 0
        whenever(garageRepository.addVehicle(isNull(), any(), any(), any())).doSuspendableAnswer { call ->
            if (adds++ == 0) throw VehiclesUnreadableException()
            val added = Vehicle("new-id", null, 1L, call.getArgument<VehicleProfile>(2))
            garageFlow.value = Garage(garageFlow.value.vehicles + added, added.id, true)
            added
        }

        vm.save()
        testScheduler.advanceUntilIdle()
        assertEquals(SaveStatus.Failed(SaveFailure.VEHICLE), vm.saveStatus.value)
        assertEquals(0, garageFlow.value.vehicles.size)

        vm.save()
        testScheduler.advanceUntilIdle()

        assertEquals(SaveStatus.Saved, vm.saveStatus.value)
        assertEquals(1, garageFlow.value.vehicles.size)
    }

    @Test
    fun `closing a failure returns to Idle with the answers kept`() = runTest {
        val vm = newWizard()
        vm.updateTargetHourly(31f)
        whenever(strategyRepository.updateRules(any())).thenThrow(IllegalStateException("disk full"))

        vm.save()
        testScheduler.advanceUntilIdle()
        vm.dismissSaveFailure()

        assertEquals(SaveStatus.Idle, vm.saveStatus.value)
        assertEquals(31f, vm.state.value.targetHourly, 0f)
    }

    @Test
    fun `skip writes only the first-run flag and reports no save`() = runTest {
        val vm = newWizard()
        clearInvocations(strategyRepository, appPreferencesRepository, garageRepository)
        var skipped = false

        vm.skipAndFinish { skipped = true }
        testScheduler.advanceUntilIdle()

        assert(skipped)
        verify(appStateRepository).setFirstRunComplete()
        verifyNoMoreInteractions(strategyRepository, appPreferencesRepository, garageRepository)
        assertEquals(SaveStatus.Idle, vm.saveStatus.value)
    }
}
